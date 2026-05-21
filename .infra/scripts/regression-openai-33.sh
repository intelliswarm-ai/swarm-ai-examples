#!/usr/bin/env bash
#
# SwarmAI 1.0.25 ship-readiness regression — 33 workflows on OpenAI.
#
# Drives 33 framework-internal example workflows through gpt-4.1-mini to
# verify the 1.0.25 changes (LlmPricingModel four-tier cache pricing,
# SpringAiLlmClient + StreamingLlmClient SPI in core, ContextOptimizer,
# BudgetTracker cache-aware accounting) didn't regress anything user-visible.
#
# Usage:
#   ./regression-openai-33.sh                     # run all 33, default 180s/workflow
#   ./regression-openai-33.sh --list              # show the 33 workflow names
#   WORKFLOW_TIMEOUT_SEC=300 ./regression-openai-33.sh
#   OPENAI_WORKFLOW_MODEL=gpt-4.1 ./regression-openai-33.sh
#
# Requirements:
#   - OPENAI_API_KEY exported (or in <repo>/.env)
#   - swarm-ai 1.0.25-SNAPSHOT installed: cd ../swarm-ai && mvn -DskipTests install
#
# This script disables intelliswarm.ai telemetry by default and runs the JAR
# directly so it doesn't redirect through run.sh's Ollama bootstrap.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
JAR="$PROJECT_DIR/target/swarmai-examples-1.0.0-SNAPSHOT.jar"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[REGRESSION]${NC} $*"; }
warn()  { echo -e "${YELLOW}[REGRESSION]${NC} $*"; }
fail()  { echo -e "${RED}[REGRESSION]${NC} $*"; }
phase() { echo -e "${CYAN}>>>${NC} $*"; }

# --- The 33: framework-internal workflows that should run on OpenAI alone ---
# Skipped on purpose: anything that needs Alpha Vantage / Finnhub / Wolfram /
# Jira / Pinecone / S3 / Kafka / Gmail / Slack / EODHD / weather API / Chroma /
# pgvector / a running REST server, plus image-gen (DALL·E billing).
WORKFLOWS=(
    "tool-calling|What is 15 percent of 2340?"
    "agent-handoff|AI orchestration"
    "context-variables|multi-agent frameworks"
    "multi-turn|Java concurrency"
    "streaming|Spring Boot"
    "memory|RAG patterns"
    "multi-provider|Spring AI"
    "error-handling|"
    "evaluator-optimizer|prompt engineering"
    "agent-testing|output quality"
    "agent-debate|open source AI"
    "visualization|"
    "codebase-analysis|"
    "data-pipeline|"
    "self-improving|framework design"
    "self-evolving|multi-agent AI frameworks"
    "audited-research|observability"
    "governed-pipeline|data quality"
    "secure-ops|access control"
    "multi-language|AI agents"
    "wikipedia|Apollo program"
    "arxiv|reinforcement learning"
    "typed-output|"
    "plan-loop|design a CLI todo app"
    "bandit-learning|"
    "self-improving-plan-loop|build a CSV diff tool"
    "task-list|"
    "system-reminders|"
    "slash-commands|"
    "edit-discipline|"
    "tool-search|file edit tools"
    "typed-message-history|"
    "deferred-tool-loading|"
)

WORKFLOW_TIMEOUT_SEC="${WORKFLOW_TIMEOUT_SEC:-180}"
OPENAI_WORKFLOW_MODEL="${OPENAI_WORKFLOW_MODEL:-gpt-4.1-mini}"

for arg in "$@"; do
    case "$arg" in
        --list)
            echo "33 OpenAI regression workflows:"
            for entry in "${WORKFLOWS[@]}"; do echo "  - ${entry%%|*}"; done
            exit 0 ;;
        -h|--help) head -20 "$0" | tail -n +2 | sed 's/^#//'; exit 0 ;;
    esac
done

# Load .env if present so OPENAI_API_KEY is picked up.
if [ -f "$PROJECT_DIR/.env" ]; then
    while IFS='=' read -r key value; do
        case "$key" in
            ''|'#'*) continue ;;
            *) [ -z "${!key:-}" ] && export "$key=$value" ;;
        esac
    done < <(grep -E '^[A-Z_][A-Z0-9_]*=' "$PROJECT_DIR/.env")
fi

if [ -z "${OPENAI_API_KEY:-}" ]; then
    fail "OPENAI_API_KEY is not set. Export it or add to $PROJECT_DIR/.env"
    exit 2
fi

# Ensure JAR is fresh.
if [ ! -f "$JAR" ] || [ -n "$(find "$PROJECT_DIR/.infra/src" -newer "$JAR" 2>/dev/null | head -1)" ]; then
    info "Building examples JAR..."
    (cd "$PROJECT_DIR" && mvn -q -DskipTests package) || { fail "build failed"; exit 3; }
fi

# Snapshot the jar so a concurrent rebuild can't yank it mid-run.
JAR_SNAPSHOT="$(mktemp -t swarmai-regression-XXXXX.jar)"
cp "$JAR" "$JAR_SNAPSHOT"
trap 'rm -f "$JAR_SNAPSHOT"' EXIT

info "====================================================="
info "  SwarmAI 1.0.25 Ship-Readiness Regression"
info "====================================================="
info "  Workflows:    ${#WORKFLOWS[@]}"
info "  Model:        $OPENAI_WORKFLOW_MODEL"
info "  Per-workflow: ${WORKFLOW_TIMEOUT_SEC}s timeout"
info "  Profile:      openai-mini"
info "  Telemetry:    DISABLED"
info "====================================================="

FAILED=()
PASSED=()
START_TIME=$(date +%s)

for entry in "${WORKFLOWS[@]}"; do
    name="${entry%%|*}"
    arg="${entry#*|}"
    [ "$arg" = "$entry" ] && arg=""

    phase "[$((${#PASSED[@]} + ${#FAILED[@]} + 1))/${#WORKFLOWS[@]}] $name ${arg:+(arg: $arg)}"

    set +e
    timeout "$WORKFLOW_TIMEOUT_SEC" java \
        -Dspring.profiles.active=openai-mini \
        -DOPENAI_WORKFLOW_MODEL="$OPENAI_WORKFLOW_MODEL" \
        -Dswarmai.self-improving.telemetry-enabled=false \
        -jar "$JAR_SNAPSHOT" "$name" $arg > "/tmp/regression-${name}.log" 2>&1
    rc=$?
    set -e

    if [ $rc -eq 0 ]; then
        info "  PASS  $name"
        PASSED+=("$name")
    elif [ $rc -eq 124 ]; then
        warn "  TIMEOUT  $name (>${WORKFLOW_TIMEOUT_SEC}s)"
        FAILED+=("$name (timeout)")
    else
        fail "  FAIL  $name (exit=$rc, log: /tmp/regression-${name}.log)"
        FAILED+=("$name (exit=$rc)")
    fi
done

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo ""
info "====================================================="
info "  Done in ${DURATION}s"
info "====================================================="
info "  PASSED:  ${#PASSED[@]} / ${#WORKFLOWS[@]}"
if [ ${#FAILED[@]} -gt 0 ]; then
    warn "  FAILED:  ${#FAILED[@]}"
    for f in "${FAILED[@]}"; do warn "    - $f"; done
fi

# Non-zero exit if anything failed, so CI catches regressions.
[ ${#FAILED[@]} -eq 0 ]
