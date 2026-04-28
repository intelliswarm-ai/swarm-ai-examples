#!/usr/bin/env bash
#
# SwarmAI Cheap-Model Sweep
#
# Runs a curated list of examples on OpenAI gpt-4o-mini (or any model you set
# via OPENAI_WORKFLOW_MODEL). Each run captures stdout/stderr to
# `output/sweep/<workflow>.log` and reports pass/fail + duration at the end.
#
# Use this script when you want to:
#   - validate that examples still work end-to-end after a framework upgrade
#   - measure relative quality of a cheaper model variant before promoting it
#   - iterate on examples and re-run the lot in one shot
#
# Usage:
#   ./.infra/scripts/cheap-model-sweep.sh                  # default sweep
#   ./.infra/scripts/cheap-model-sweep.sh --list           # show curated list
#   ./.infra/scripts/cheap-model-sweep.sh --quick          # smaller subset
#   OPENAI_WORKFLOW_MODEL=gpt-4.1-mini ./...sh             # try a different cheap model
#
# Cost: ~$0.005-0.020 per workflow on gpt-4o-mini for typical inputs. The full
# sweep is well under $0.50.
#
# Requirements:
#   - OPENAI_API_KEY in .env (sourced by run.sh)
#   - the project JAR must be buildable (Maven on the path)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
LOG_DIR="$PROJECT_DIR/output/sweep"

# --- Colors ---
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[SWEEP]${NC} $*"; }
warn()  { echo -e "${YELLOW}[SWEEP]${NC} $*"; }
fail()  { echo -e "${RED}[SWEEP]${NC} $*"; }
phase() { echo -e "${CYAN}>>>${NC} $*"; }

# --- Curated lists ------------------------------------------------------------
# Each entry: "workflow-name|optional-arg".
# Workflows here are chosen to exercise distinct framework features without
# requiring infrastructure (Chroma, Pinecone, S3, Kafka, REST servers).
FULL_SWEEP=(
    "tool-calling|What is 15 percent of 2340?"
    "agent-handoff|what is event sourcing in one paragraph"
    "context-variables|microservices observability"
    "multi-turn|the impact of LLMs on testing"
    "memory|RAG patterns"
    "multi-provider|Spring AI"
    "error-handling|"
    "evaluator-optimizer|prompt engineering"
    "agent-debate|the value of strict typing"
    "visualization|"
    "codebase-analysis|"
    "self-improving|framework design"
    "self-evolving|multi-agent AI frameworks"
    "audited-research|observability"
    "governed-pipeline|data quality"
    "multi-language|AI agents"
)

QUICK_SWEEP=(
    "tool-calling|What is 15 percent of 2340?"
    "agent-handoff|what is event sourcing"
    "context-variables|microservices"
    "self-evolving|orchestration"
)

# Per-workflow timeout (gpt-4o-mini is fast; 5 min is plenty for any single workflow).
WORKFLOW_TIMEOUT_SEC="${WORKFLOW_TIMEOUT_SEC:-300}"

QUICK=false

# --- Parse args ---
for arg in "$@"; do
    case "$arg" in
        --quick) QUICK=true ;;
        --list)
            echo "Full sweep:"
            for entry in "${FULL_SWEEP[@]}"; do echo "  - ${entry%%|*}"; done
            echo ""
            echo "Quick sweep (--quick):"
            for entry in "${QUICK_SWEEP[@]}"; do echo "  - ${entry%%|*}"; done
            exit 0 ;;
        -h|--help)
            head -28 "$0" | tail -n +2 | sed 's/^#//'
            exit 0 ;;
    esac
done

if [ "$QUICK" = "true" ]; then
    WORKFLOWS=("${QUICK_SWEEP[@]}")
else
    WORKFLOWS=("${FULL_SWEEP[@]}")
fi

# --- Preflight ---
mkdir -p "$LOG_DIR"
MODEL="${OPENAI_WORKFLOW_MODEL:-gpt-4o-mini}"

echo ""
info "====================================================="
info "  SwarmAI Cheap-Model Sweep"
info "====================================================="
info "  Workflows: ${#WORKFLOWS[@]}"
info "  Model:     $MODEL"
info "  Profile:   openai-mini"
info "  Logs:      $LOG_DIR"
info "  Timeout:   ${WORKFLOW_TIMEOUT_SEC}s per workflow"
info "====================================================="
echo ""

# Build once up front so the per-workflow runs can use SKIP_BUILD=1.
info "Building project JAR..."
( cd "$PROJECT_DIR" && mvn package -DskipTests -o -q ) || {
    fail "Build failed — fix compile errors before sweeping."
    exit 1
}

# --- Run each workflow ---
SUCCEEDED=()
FAILED=()
SWEEP_START=$(date +%s)

for entry in "${WORKFLOWS[@]}"; do
    name="${entry%%|*}"
    arg="${entry#*|}"
    log="$LOG_DIR/${name}.log"
    start=$(date +%s)

    phase "Running: $name ${arg:+(arg: $arg)}"
    # --kill-after escalates to SIGKILL 30s after SIGTERM, in case the JVM
    # child process ignores the signal that timeout sends to run.sh.
    if SPRING_PROFILES_ACTIVE=openai-mini SKIP_BUILD=1 \
         timeout --kill-after=30s "$WORKFLOW_TIMEOUT_SEC" \
         "$PROJECT_DIR/run.sh" "$name" $arg > "$log" 2>&1; then
        dur=$(( $(date +%s) - start ))
        info "  PASS: $name  (${dur}s)"
        SUCCEEDED+=("$name|${dur}s")
    else
        dur=$(( $(date +%s) - start ))
        fail "  FAIL: $name  (${dur}s, see $log)"
        FAILED+=("$name|${dur}s")
    fi
    echo ""
done

SWEEP_DUR=$(( $(date +%s) - SWEEP_START ))

# --- Summary ---
echo ""
info "====================================================="
info "  Sweep Complete in ${SWEEP_DUR}s"
info "====================================================="
info "  Passed:  ${#SUCCEEDED[@]} / ${#WORKFLOWS[@]}"
for entry in "${SUCCEEDED[@]}"; do
    info "    ✓ ${entry%|*}  ${entry#*|}"
done
if [ ${#FAILED[@]} -gt 0 ]; then
    warn "  Failed:  ${#FAILED[@]}"
    for entry in "${FAILED[@]}"; do
        warn "    ✗ ${entry%|*}  ${entry#*|}"
    done
fi

# --- Persist results to history TSV so you can track progress over time -------
HISTORY="$LOG_DIR/sweep-history.tsv"
if [ ! -f "$HISTORY" ]; then
    printf "timestamp\tmodel\tworkflows\tpassed\tfailed\tduration_sec\tfailing_workflows\n" > "$HISTORY"
fi
ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
failing_csv="$(printf '%s,' "${FAILED[@]%|*}" | sed 's/,$//')"
printf "%s\t%s\t%d\t%d\t%d\t%d\t%s\n" \
    "$ts" "$MODEL" "${#WORKFLOWS[@]}" "${#SUCCEEDED[@]}" "${#FAILED[@]}" "$SWEEP_DUR" "$failing_csv" \
    >> "$HISTORY"

# Show a small trend (last 5 runs) so the user sees movement.
echo ""
info "History (last 5 sweeps — append-only at $HISTORY):"
( head -1 "$HISTORY"; tail -5 "$HISTORY" ) | column -t -s $'\t' | sed 's/^/  /'

if [ ${#FAILED[@]} -gt 0 ]; then
    warn ""
    warn "  Inspect failure logs in $LOG_DIR/"
    exit 1
fi
