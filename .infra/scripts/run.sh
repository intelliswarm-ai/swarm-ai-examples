#!/usr/bin/env bash
#
# SwarmAI Example Runner
# Starts Ollama in Docker (if not running), pulls the model, builds the JAR, and runs an example.
#
# Usage:
#   ./scripts/run.sh bare-minimum
#   ./scripts/run.sh tool-calling "What is 15% of 2340?"
#   ./scripts/run.sh customer-support "I was charged twice"
#   ./scripts/run.sh stock-analysis TSLA
#   ./scripts/run.sh --list                    # show all available examples
#   ./scripts/run.sh --setup                   # just start Ollama + pull model, don't run anything
#
# Environment:
#   OLLAMA_MODEL    Model to use (default: mistral:latest)
#   OLLAMA_HOST     Ollama base URL (default: auto-detect or http://localhost:11434)
#   SKIP_BUILD      Set to 1 to skip Maven build
#   STUDIO          Set to 1 to keep Studio UI alive after workflow
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR="$PROJECT_DIR/target/swarmai-examples-1.0.0-SNAPSHOT.jar"
OLLAMA_MODEL="${OLLAMA_MODEL:-mistral:latest}"
OLLAMA_CONTAINER="swarmai-ollama"
STUDIO="${STUDIO:-0}"

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# --- Detect Ollama ---
detect_ollama() {
    # 1. Check if OLLAMA_HOST is already set
    if [ -n "${OLLAMA_HOST:-}" ]; then
        if curl -s --connect-timeout 2 "$OLLAMA_HOST/api/tags" > /dev/null 2>&1; then
            info "Using OLLAMA_HOST=$OLLAMA_HOST"
            return 0
        fi
    fi

    # 2. Check localhost (native install or Docker)
    if curl -s --connect-timeout 2 "http://localhost:11434/api/tags" > /dev/null 2>&1; then
        OLLAMA_HOST="http://localhost:11434"
        info "Found Ollama at $OLLAMA_HOST"
        return 0
    fi

    # 3. Check WSL gateway (Windows Ollama)
    local gateway
    gateway=$(ip route show default 2>/dev/null | awk '{print $3}')
    if [ -n "$gateway" ] && curl -s --connect-timeout 2 "http://$gateway:11434/api/tags" > /dev/null 2>&1; then
        OLLAMA_HOST="http://$gateway:11434"
        info "Found Ollama on Windows host at $OLLAMA_HOST"
        return 0
    fi

    return 1
}

# --- Start Ollama in Docker ---
start_ollama_docker() {
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${OLLAMA_CONTAINER}$"; then
        info "Ollama container already running"
        OLLAMA_HOST="http://localhost:11434"
        return 0
    fi

    # Remove stopped container if exists
    docker rm -f "$OLLAMA_CONTAINER" 2>/dev/null || true

    info "Starting Ollama in Docker..."
    docker run -d \
        --name "$OLLAMA_CONTAINER" \
        -p 11434:11434 \
        -v ollama_data:/root/.ollama \
        ollama/ollama:latest > /dev/null

    # Wait for Ollama to be ready
    info "Waiting for Ollama to start..."
    local retries=30
    while [ $retries -gt 0 ]; do
        if curl -s --connect-timeout 1 "http://localhost:11434/api/tags" > /dev/null 2>&1; then
            OLLAMA_HOST="http://localhost:11434"
            info "Ollama is ready at $OLLAMA_HOST"
            return 0
        fi
        sleep 2
        retries=$((retries - 1))
    done

    error "Ollama failed to start within 60 seconds"
    docker logs "$OLLAMA_CONTAINER" 2>&1 | tail -10
    return 1
}

# --- Pull model ---
ensure_model() {
    local models
    models=$(curl -s "$OLLAMA_HOST/api/tags" 2>/dev/null)

    if echo "$models" | grep -q "\"$OLLAMA_MODEL\""; then
        info "Model $OLLAMA_MODEL is available"
        return 0
    fi

    info "Pulling model $OLLAMA_MODEL (this may take a few minutes)..."
    curl -s "$OLLAMA_HOST/api/pull" -d "{\"name\": \"$OLLAMA_MODEL\"}" | while read -r line; do
        local status
        status=$(echo "$line" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status',''))" 2>/dev/null || echo "")
        if [ -n "$status" ]; then
            echo -ne "\r  ${CYAN}$status${NC}                    "
        fi
    done
    echo ""
    info "Model $OLLAMA_MODEL is ready"
}

# --- Build JAR ---
build_jar() {
    if [ "${SKIP_BUILD:-0}" = "1" ] && [ -f "$JAR" ]; then
        info "Skipping build (SKIP_BUILD=1)"
        return 0
    fi

    if [ ! -f "$JAR" ]; then
        info "Building project..."
    else
        info "Rebuilding project..."
    fi

    (cd "$PROJECT_DIR" && mvn clean package -DskipTests -q)
    info "Build complete"
}

# --- List examples ---
list_examples() {
    echo ""
    echo "SwarmAI Examples"
    echo "================"
    echo ""
    echo "Basic (start here):"
    echo "  bare-minimum              Simplest: 1 agent, 1 task, no tools"
    echo "  tool-calling [PROBLEM]    Single agent with CalculatorTool"
    echo "  agent-handoff [TOPIC]     Two agents with output dependency"
    echo "  context-variables [TOPIC] Three agents sharing context"
    echo "  multi-turn [TOPIC]        Deep reasoning with maxTurns=5"
    echo ""
    echo "Features:"
    echo "  streaming [TOPIC]            Reactive multi-turn with progress hooks"
    echo "  rag-research <QUERY>         Knowledge retrieval + grounded report"
    echo "  customer-support [QUERY]     Routing + handoff via SwarmGraph"
    echo "  error-handling               Failures, budget limits, timeouts"
    echo "  memory [TOPIC]               Shared memory across agents"
    echo "  human-loop [TOPIC]           Approval gates + revision loops"
    echo "  multi-provider [TOPIC]       Cross-temperature/model comparison"
    echo "  evaluator-optimizer [TOPIC]  Generate, evaluate, optimize loop"
    echo ""
    echo "Production:"
    echo "  stock-analysis <TICKER>      Financial analysis (needs API key)"
    echo "  competitive-analysis <QUERY> Multi-agent research (needs API key)"
    echo "  due-diligence <TICKER>       Company due diligence (needs API key)"
    echo "  codebase-analysis [PATH]     Code architecture analysis"
    echo "  data-pipeline [FILE]         Data profiling and insights"
    echo "  self-improving <QUERY>       Dynamic tool generation"
    echo "  enterprise-governed <QUERY>  Governance + budget + tenancy"
    echo ""
    echo "Composite:"
    echo "  audited-research <QUERY>     Multi-turn + hooks + observability"
    echo "  governed-pipeline <QUERY>    Composite + checkpoints + budget"
    echo "  secure-ops <QUERY>           Tiered permissions + compliance"
    echo ""
    echo "Swarm:"
    echo "  pentest-swarm <TARGET>       Parallel pentest agents"
    echo "  competitive-swarm <QUERY>    Parallel company analysis"
    echo "  investment-swarm <QUERY>     Multi-company investment analysis"
    echo ""
    echo "Usage: ./scripts/run.sh <example> [args...]"
    echo ""
}

# --- Main ---
main() {
    if [ $# -eq 0 ] || [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
        list_examples
        exit 0
    fi

    if [ "${1:-}" = "--list" ]; then
        list_examples
        exit 0
    fi

    # Detect or start Ollama
    if ! detect_ollama; then
        warn "Ollama not found. Starting via Docker..."
        if ! command -v docker &> /dev/null; then
            error "Neither Ollama nor Docker found. Install one of them first."
            exit 1
        fi
        start_ollama_docker
    fi

    # Pull model
    ensure_model

    if [ "${1:-}" = "--setup" ]; then
        info "Setup complete. Ollama is running at $OLLAMA_HOST with model $OLLAMA_MODEL"
        exit 0
    fi

    # Build
    build_jar

    # Run
    local workflow="$1"
    shift
    local studio_flag="false"
    [ "$STUDIO" = "1" ] && studio_flag="true"

    info "Running: $workflow $*"
    echo ""

    # Build extra args for specific workflows
    local extra_args=""
    if [ "$workflow" = "customer-support-app" ]; then
        extra_args="--swarmai.customer-support.enabled=true"
        studio_flag="true"  # Keep server alive for REST API
    fi
    if [ "$workflow" = "rag-app" ]; then
        # Enable the RAG app beans and configure Chroma vector store + Ollama embeddings
        extra_args="--swarmai.rag-app.enabled=true"
        extra_args="$extra_args --spring.ai.vectorstore.chroma.client.host=localhost"
        extra_args="$extra_args --spring.ai.vectorstore.chroma.client.port=8000"
        extra_args="$extra_args --spring.ai.vectorstore.chroma.collection-name=swarmai-rag"
        extra_args="$extra_args --spring.ai.ollama.embedding.model=nomic-embed-text"
        extra_args="$extra_args --spring.ai.ollama.embedding.enabled=true"
        studio_flag="true"  # Keep server alive for REST API

        # Check if Chroma is running
        if ! curl -s --connect-timeout 2 "http://localhost:8000/api/v1/heartbeat" > /dev/null 2>&1 && \
           ! curl -s --connect-timeout 2 "http://localhost:8000/api/v2/heartbeat" > /dev/null 2>&1; then
            warn "Chroma vector store not detected at localhost:8000"
            warn "RAG app will work with keyword search only (no semantic search)"
            warn "To enable semantic search: docker run -d --name swarmai-chroma -p 8000:8000 ghcr.io/chroma-core/chroma:latest"
        else
            info "Chroma vector store detected at localhost:8000"
        fi
    fi

    java -jar "$JAR" "$workflow" "$@" \
        --spring.ai.ollama.base-url="$OLLAMA_HOST" \
        --spring.ai.ollama.chat.options.model="$OLLAMA_MODEL" \
        --swarmai.studio-keep-alive="$studio_flag" \
        $extra_args
}

main "$@"
