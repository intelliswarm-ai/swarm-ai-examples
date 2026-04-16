#!/usr/bin/env bash
#
# SwarmAI Regression Runner
#
# Runs a curated list of offline-safe examples on local Mistral 7B, one after
# another. Each example POSTs its telemetry rollup to intelliswarm.ai when it
# exits (PER_WORKFLOW mode), so the public community ledger counter ticks up
# in near-real-time.
#
# Usage:
#   ./scripts/regression.sh                 # default: honest thresholds
#   ./scripts/regression.sh --demo          # demo-thresholds profile (proposals WILL fire)
#   ./scripts/regression.sh --list          # show the curated workflow list
#   ./scripts/regression.sh --no-telemetry  # local only, don't POST to website
#
# Requirements:
#   - Ollama running locally with mistral:7b pulled (auto-handled by run.sh)
#   - Network access to https://intelliswarm.ai (unless --no-telemetry)
#
# What you should see:
#   - Ledger counters before/after, with a diff
#   - For each example: the ASCII self-improvement summary (observations,
#     proposals, categories)
#   - Final console diff showing what this regression run contributed
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
LEDGER_URL="${LEDGER_URL:-https://intelliswarm.ai/api/v1/self-improving/ledger}"

# --- Colors ---
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[REGRESSION]${NC} $*"; }
warn()  { echo -e "${YELLOW}[REGRESSION]${NC} $*"; }
phase() { echo -e "${CYAN}>>>${NC} $*"; }

# --- Curated list: offline-safe on Mistral 7B ---
# Each entry: "workflow-name|optional-arg". Workflow names match the Java Main
# dispatch (see .infra/src/main/java/.../SwarmAIExamplesApplication.java), not
# the friendly shell aliases in run.sh.
# Skipped: anything needing Alpha Vantage / Google / Finnhub / Chroma / REST servers.
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
    "enterprise-governed|compliance"
    "audited-research|observability"
    "governed-pipeline|data quality"
    "secure-ops|access control"
    "multi-language|AI agents"
)

# Per-workflow timeout. Mistral 7B on modest hardware can take 3-10 min per
# workflow, and multi-turn / parallel swarms run longer.
WORKFLOW_TIMEOUT_SEC="${WORKFLOW_TIMEOUT_SEC:-900}"

DEMO_MODE=false
NO_TELEMETRY=false

# --- Parse args ---
for arg in "$@"; do
    case "$arg" in
        --demo) DEMO_MODE=true ;;
        --list)
            echo "Curated regression workflows (offline-safe on Mistral 7B):"
            for entry in "${WORKFLOWS[@]}"; do
                echo "  - ${entry%%|*}"
            done
            exit 0 ;;
        --no-telemetry) NO_TELEMETRY=true ;;
        -h|--help)
            head -30 "$0" | tail -n +2 | sed 's/^#//'
            exit 0 ;;
    esac
done

# --- Fetch ledger snapshot (before) ---
fetch_ledger() {
    local label="$1"
    info "$label snapshot from $LEDGER_URL"
    if command -v jq &>/dev/null; then
        curl -sS --max-time 10 "$LEDGER_URL" | jq -c '{
            installations: .coverage.reportingInstallations,
            runs: .inputs.totalWorkflowRuns,
            tokens: .inputs.totalTokensInvested,
            observations: .inputs.totalObservationsCollected,
            proposals: .outputs.totalProposalsGenerated,
            tier1: .outputs.totalTier1AutoEligible,
            tier2: .outputs.totalTier2PRsFiled,
            tier3: .outputs.totalTier3Proposals
        }' 2>/dev/null || echo "  (ledger fetch failed)"
    else
        curl -sS --max-time 10 "$LEDGER_URL" 2>/dev/null || echo "  (ledger fetch failed)"
    fi
    echo ""
}

# --- Build profile args ---
PROFILE_ARGS="--spring.profiles.active=ollama"
if [ "$DEMO_MODE" = "true" ]; then
    PROFILE_ARGS="--spring.profiles.active=ollama,demo-thresholds"
    warn "Demo-thresholds profile active — proposal thresholds are artificially low."
    warn "Proposals produced here are NOT production-quality. See application-demo-thresholds.yml."
fi
TELEMETRY_ARGS=""
if [ "$NO_TELEMETRY" = "true" ]; then
    TELEMETRY_ARGS="--swarmai.self-improving.telemetry-enabled=false"
    warn "Telemetry disabled — this run will not be visible on intelliswarm.ai/ledger"
fi

# --- Preflight ---
echo ""
info "====================================================="
info "  SwarmAI Regression Suite"
info "====================================================="
info "  Workflows: ${#WORKFLOWS[@]}"
info "  Profile:   $PROFILE_ARGS"
info "  Telemetry: $([ "$NO_TELEMETRY" = "true" ] && echo DISABLED || echo "PER_WORKFLOW → $LEDGER_URL")"
info "====================================================="
echo ""

if [ "$NO_TELEMETRY" = "false" ]; then
    fetch_ledger "BEFORE"
fi

# --- Run each workflow ---
# Delegate to the standard run.sh, which handles Ollama bootstrap + JAR build.
# Each java invocation is a fresh JVM — @PreDestroy flushes telemetry on exit.
FAILED=()
for entry in "${WORKFLOWS[@]}"; do
    name="${entry%%|*}"
    arg="${entry#*|}"

    phase "Running: $name ${arg:+(arg: $arg)}"
    # Per-workflow timeout (default 15 min, override with WORKFLOW_TIMEOUT_SEC).
    if ! SPRING_PROFILES_ACTIVE="${PROFILE_ARGS#--spring.profiles.active=}" \
         timeout "$WORKFLOW_TIMEOUT_SEC" "$SCRIPT_DIR/run.sh" "$name" $arg $TELEMETRY_ARGS; then
        warn "  FAILED or timed out: $name"
        FAILED+=("$name")
    fi
    echo ""
done

# --- Summary ---
echo ""
info "====================================================="
info "  Regression Complete"
info "====================================================="
info "  Succeeded: $(( ${#WORKFLOWS[@]} - ${#FAILED[@]} )) / ${#WORKFLOWS[@]}"
if [ ${#FAILED[@]} -gt 0 ]; then
    warn "  Failed:    ${FAILED[*]}"
fi

if [ "$NO_TELEMETRY" = "false" ]; then
    # CloudFront caches the ledger for ~5 min, so the "after" snapshot may lag.
    # Show it anyway; if numbers haven't moved, try again in a minute.
    echo ""
    info "Note: ledger API is cached at CloudFront (5 min TTL). If numbers below"
    info "      don't reflect this run yet, wait ~60s and curl the endpoint again."
    echo ""
    fetch_ledger "AFTER (may be cache-stale)"
fi

info "Done."
