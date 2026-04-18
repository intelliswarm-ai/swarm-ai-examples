#!/usr/bin/env bash
# Record a real demo trace (swarm + baseline) for (slug, model) pairs.
# NOTHING HERE IS SIMULATED — every trace on intelliswarm.ai comes from this script.
#
# Usage:
#   ./demo-recorder/record-demo.sh <slug> [model] [workflow-args...]
#
#   ./demo-recorder/record-demo.sh stock-market-analysis gpt-4o AAPL
#   ./demo-recorder/record-demo.sh launch gpt-4o                 # curated launch demo, no per-workflow args
#
# Workflow args (e.g. a stock ticker, a research topic) are passed through to the
# example's run.sh unchanged. Make sure prompt.md matches the arg so both swarm
# and baseline sides answer the same question.
#
# Supported providers and required env:
#   ollama      (local)     — no env needed; run.sh auto-pulls the model
#   openai      (gpt-*)     — OPENAI_API_KEY must be exported
#   anthropic   (claude-*)  — ANTHROPIC_API_KEY must be exported
#
# Force re-record (overwrites existing traces): FORCE=1
# Override the framework version written into the path: SWARMAI_VERSION=1.0.6

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$PROJECT_DIR/target/swarmai-examples-1.0.0-SNAPSHOT.jar"
OUT_DIR="${SWARMAI_DEMO_OUT_DIR:-$PROJECT_DIR/demos}"
FW_VERSION="${SWARMAI_VERSION:-1.0.5}"

# Auto-load .env from the examples repo root so recordings pick up OPENAI_API_KEY
# (and anything else provider-specific) without the user having to `export` manually.
# `set -a` exports every variable touched while sourcing.
if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$PROJECT_DIR/.env"
    set +a
fi

LAUNCH_DEMOS=(
    # The single curated demo published on intelliswarm.ai/demos. Other examples
    # remain as top-level source dirs but are not wired through demo-recorder.
    "stock-market-analysis"
)

slug_to_example() {
    case "$1" in
        stock-market-analysis) echo "stock-analysis" ;;
        *) echo "$1" ;;
    esac
}

model_display_name() {
    case "$1" in
        gpt-4o)             echo "GPT-4o" ;;
        gpt-4o-mini)        echo "GPT-4o mini" ;;
        gpt-5)              echo "GPT-5" ;;
        gpt-5-mini)         echo "GPT-5 mini" ;;
        gpt-5.4)            echo "GPT-5.4" ;;
        gpt-5.4-mini)       echo "GPT-5.4 mini" ;;
        o1)                 echo "o1" ;;
        o1-mini)            echo "o1-mini" ;;
        claude-sonnet-4-6)  echo "Claude Sonnet 4.6" ;;
        claude-opus-4-6)    echo "Claude Opus 4.6" ;;
        # local models kept out of default list — re-add here if needed
        mistral-ollama|llama-*|qwen-*) echo "$1 (Ollama)" ;;
        *)                  echo "$1" ;;
    esac
}

model_provider() {
    case "$1" in
        *ollama*|mistral*|llama*) echo "ollama" ;;
        gpt-*|o1*|o3*)            echo "openai" ;;
        claude-*)                 echo "anthropic" ;;
        *)                        echo "unknown" ;;
    esac
}

# Pre-flight: fail fast before spinning up a JVM if required credentials are missing.
preflight() {
    local provider="$1"
    local model="$2"
    case "$provider" in
        openai)
            if [ -z "${OPENAI_API_KEY:-}" ]; then
                echo "ERROR: OPENAI_API_KEY is not set. Required to record against '$model'." >&2
                echo "       export OPENAI_API_KEY=sk-... and rerun." >&2
                exit 1
            fi
            ;;
        anthropic)
            if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
                echo "ERROR: ANTHROPIC_API_KEY is not set. Required to record against '$model'." >&2
                exit 1
            fi
            ;;
        ollama) : ;;  # run.sh handles ollama itself
        unknown)
            echo "ERROR: unknown provider for model '$model'." >&2
            exit 1
            ;;
    esac
}

record_pair() {
    local slug="$1"
    local model="$2"
    shift 2
    local workflow_args=("$@")   # forwarded to run.sh / example workflow
    local display; display="$(model_display_name "$model")"
    local provider; provider="$(model_provider "$model")"
    local example; example="$(slug_to_example "$slug")"

    preflight "$provider" "$model"

    local run_dir="$OUT_DIR/$slug/runs/$model/$FW_VERSION"
    local swarm_file="$run_dir/$slug.json"
    local baseline_file="$run_dir/baseline.json"

    echo "═══ recording $slug × $model ($provider, fw=$FW_VERSION) ═══"

    # ---- swarm side (runs the full example via run.sh, recorder listens to SwarmEvents) ----
    if [ -f "$swarm_file" ] && [ "${FORCE:-0}" != "1" ]; then
        echo "→ $slug.json exists; skip (FORCE=1 to overwrite)"
    else
        # Common env for the recorder bean
        local -a common_env=(
            SWARMAI_DEMO_RECORD=true
            SWARMAI_DEMO_SLUG="$slug"
            SWARMAI_DEMO_MODEL="$model"
            SWARMAI_DEMO_MODEL_DISPLAY_NAME="$display"
            SWARMAI_DEMO_PROVIDER="$provider"
            SWARMAI_DEMO_OUT_DIR="$OUT_DIR"
            SWARMAI_DEMO_FRAMEWORK_VERSION="$FW_VERSION"
        )
        local -a provider_env=()
        case "$provider" in
            openai)
                provider_env=(
                    SPRING_PROFILES_ACTIVE=openai
                    OPENAI_WORKFLOW_MODEL="$model"
                )
                ;;
            ollama)
                provider_env=(SPRING_PROFILES_ACTIVE=ollama)
                ;;
        esac

        env "${common_env[@]}" "${provider_env[@]}" "$PROJECT_DIR/run.sh" "$example" "${workflow_args[@]}"
    fi

    # ---- baseline side (same prompt, same model, no framework) ----
    if [ -f "$baseline_file" ] && [ "${FORCE:-0}" != "1" ]; then
        echo "→ baseline.json exists; skip"
    else
        local -a baseline_args=(
            "--swarmai.demo.slug=$slug"
            "--swarmai.demo.model=$model"
            "--swarmai.demo.model-display-name=$display"
            "--swarmai.demo.provider=$provider"
            "--swarmai.demo.out-dir=$OUT_DIR"
            "--swarmai.demo.framework-version=$FW_VERSION"
        )
        local spring_profile="ollama"
        case "$provider" in
            openai)
                spring_profile="openai"
                baseline_args+=("--spring.ai.openai.chat.options.model=$model")
                ;;
            ollama)
                baseline_args+=(
                    "--spring.ai.ollama.base-url=${OLLAMA_HOST:-http://localhost:11434}"
                    "--spring.ai.ollama.chat.options.model=${OLLAMA_MODEL:-mistral:latest}"
                )
                ;;
        esac

        (
            cd "$PROJECT_DIR"
            mvn -q spring-boot:run \
                -Dspring-boot.run.main-class=ai.intelliswarm.examples.demorecorder.BaselineRunner \
                -Dspring-boot.run.profiles="$spring_profile" \
                -Dspring-boot.run.arguments="${baseline_args[*]}"
        )
    fi
}

# --- dispatch ---

target="${1:-}"
model="${2:-mistral-ollama}"
# Everything after positional 2 is treated as per-workflow args forwarded to run.sh
if [ $# -gt 2 ]; then
    shift 2
    WORKFLOW_ARGS=("$@")
else
    WORKFLOW_ARGS=()
fi

if [ -z "$target" ]; then
    echo "usage: $0 <slug|launch> [model] [workflow-args...]"
    exit 1
fi

if [ ! -f "$JAR" ]; then
    echo "Building project JAR ($JAR not found)…"
    (cd "$PROJECT_DIR" && mvn package -DskipTests -q)
fi

if [ "$target" = "launch" ] || [ "$target" = "all" ]; then
    echo "Recording the ${#LAUNCH_DEMOS[@]} curated launch demos (not the full example repo):"
    printf '  - %s\n' "${LAUNCH_DEMOS[@]}"
    if [ ${#WORKFLOW_ARGS[@]} -gt 0 ]; then
        echo "Workflow args forwarded to every demo: ${WORKFLOW_ARGS[*]}"
    fi
    echo
    for s in "${LAUNCH_DEMOS[@]}"; do
        record_pair "$s" "$model" "${WORKFLOW_ARGS[@]}"
    done
else
    record_pair "$target" "$model" "${WORKFLOW_ARGS[@]}"
fi

echo
echo "Done. Traces under: $OUT_DIR"
echo "Next: sync to website — cp -r $OUT_DIR/* ../intelliswarm.ai/website/src/assets/demos/"
