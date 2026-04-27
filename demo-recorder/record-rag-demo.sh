#!/usr/bin/env bash
# Record swarm + baseline traces for the rag-knowledge-base demo across one or more models.
# Mirrors record-demo.sh's output schema (so the website plays the trace unchanged) but
# uses RagDemoRunner instead of run.sh — RagPipeline isn't a SwarmGraph workflow.
#
# Usage:
#   ./record-rag-demo.sh                     # all default models
#   ./record-rag-demo.sh gpt-4o gpt-5.4-mini # specific models
#
# Required env (auto-loaded from swarm-ai-examples/.env if present):
#   OPENAI_API_KEY   for any gpt-* model

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SLUG="rag-knowledge-base"
OUT_DIR="${SWARMAI_DEMO_OUT_DIR:-$PROJECT_DIR/demos}"
FW_VERSION="${SWARMAI_VERSION:-1.0.13}"

if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$PROJECT_DIR/.env"
    set +a
fi

# Default model list — both go on the website
MODELS=("$@")
if [ ${#MODELS[@]} -eq 0 ]; then
    MODELS=("gpt-4o" "gpt-5.4-mini")
fi

display_name() {
    case "$1" in
        gpt-4o)        echo "GPT-4o" ;;
        gpt-4o-mini)   echo "GPT-4o mini" ;;
        gpt-5)         echo "GPT-5" ;;
        gpt-5-mini)    echo "GPT-5 mini" ;;
        gpt-5.4)       echo "GPT-5.4" ;;
        gpt-5.4-mini)  echo "GPT-5.4 mini" ;;
        *)             echo "$1" ;;
    esac
}

provider_for() {
    case "$1" in
        gpt-*|o1*|o3*) echo "openai" ;;
        *)             echo "ollama" ;;
    esac
}

run_one() {
    local model="$1"
    local display; display="$(display_name "$model")"
    local provider; provider="$(provider_for "$model")"

    if [ "$provider" = "openai" ] && [ -z "${OPENAI_API_KEY:-}" ]; then
        echo "ERROR: OPENAI_API_KEY missing — cannot record $model" >&2
        return 1
    fi

    local run_dir="$OUT_DIR/$SLUG/runs/$model/$FW_VERSION"
    mkdir -p "$run_dir"
    echo "═══ recording $SLUG × $model ($provider, fw=$FW_VERSION) ═══"

    local -a common_args=(
        "--swarmai.demo.slug=$SLUG"
        "--swarmai.demo.model=$model"
        "--swarmai.demo.model-display-name=$display"
        "--swarmai.demo.provider=$provider"
        "--swarmai.demo.out-dir=$OUT_DIR"
        "--swarmai.demo.framework-version=$FW_VERSION"
    )
    local profile="$provider"
    [ "$provider" = "openai" ] && common_args+=("--spring.ai.openai.chat.options.model=$model")

    # ---- swarm side ----
    if [ -f "$run_dir/$SLUG.json" ] && [ "${FORCE:-0}" != "1" ]; then
        echo "→ $SLUG.json exists; skip (FORCE=1 to overwrite)"
    else
        ( cd "$PROJECT_DIR" && mvn -q spring-boot:run \
            -Dspring-boot.run.main-class=ai.intelliswarm.examples.demorecorder.RagDemoRunner \
            -Dspring-boot.run.profiles="$profile" \
            -Dspring-boot.run.arguments="${common_args[*]}" )
    fi

    # ---- baseline side (no retrieval, raw LLM) ----
    if [ -f "$run_dir/baseline.json" ] && [ "${FORCE:-0}" != "1" ]; then
        echo "→ baseline.json exists; skip"
    else
        ( cd "$PROJECT_DIR" && mvn -q spring-boot:run \
            -Dspring-boot.run.main-class=ai.intelliswarm.examples.demorecorder.BaselineRunner \
            -Dspring-boot.run.profiles="$profile" \
            -Dspring-boot.run.arguments="${common_args[*]}" )
    fi
}

for m in "${MODELS[@]}"; do
    run_one "$m"
done

echo
echo "Done. Traces under: $OUT_DIR/$SLUG/runs/"
echo "Sync to website: cp -r $OUT_DIR/$SLUG ../intelliswarm.ai/website/src/assets/demos/"
