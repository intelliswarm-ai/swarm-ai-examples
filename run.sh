#!/usr/bin/env bash
#
# SwarmAI Example Runner
# Starts Ollama (if not running), pulls the model, builds the JAR, and runs an example.
#
# Usage:
#   ./run.sh bare-minimum
#   ./run.sh customer-support "I was charged twice"
#   ./run.sh stock-analysis TSLA
#   ./run.sh --list                    # show all available examples
#   ./run.sh --setup                   # just start Ollama + pull model
#
# Environment:
#   OLLAMA_MODEL    Model to use (default: mistral:latest)
#   OLLAMA_HOST     Ollama base URL (default: auto-detect or http://localhost:11434)
#   SKIP_BUILD      Set to 1 to skip Maven build
#   STUDIO          Set to 1 to keep Studio UI alive after workflow
#   DEMO            Set to 1 to silence Spring Boot startup chatter (root logger → WARN,
#                   examples + workflow-runner stay at INFO). Recommended for demos.
#

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$PROJECT_DIR/target/swarmai-examples-1.0.0-SNAPSHOT.jar"

# Load .env if present so shell-level checks (e.g. OPENAI_API_KEY) match what
# Spring Boot will see via ParentDirDotenvPostProcessor at runtime. Pre-existing
# shell env vars take precedence so users can override via prefix:
#   SPRING_PROFILES_ACTIVE=openai-mini ./run.sh ...
if [ -f "$PROJECT_DIR/.env" ]; then
    while IFS='=' read -r key value; do
        case "$key" in
            ''|'#'*) continue ;;
            *)
                # Only set if not already in the environment
                if [ -z "${!key:-}" ]; then
                    export "$key=$value"
                fi
                ;;
        esac
    done < <(grep -E '^[A-Z_][A-Z0-9_]*=' "$PROJECT_DIR/.env")
fi
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
    if [ -n "${OLLAMA_HOST:-}" ]; then
        if curl -s --connect-timeout 2 "$OLLAMA_HOST/api/tags" > /dev/null 2>&1; then
            info "Using OLLAMA_HOST=$OLLAMA_HOST"
            return 0
        fi
    fi
    if curl -s --connect-timeout 2 "http://localhost:11434/api/tags" > /dev/null 2>&1; then
        OLLAMA_HOST="http://localhost:11434"
        info "Found Ollama at $OLLAMA_HOST"
        return 0
    fi
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
    docker rm -f "$OLLAMA_CONTAINER" 2>/dev/null || true
    info "Starting Ollama in Docker..."
    docker run -d --name "$OLLAMA_CONTAINER" -p 11434:11434 -v ollama_data:/root/.ollama ollama/ollama:latest > /dev/null
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
    info "Building project..."
    (cd "$PROJECT_DIR" && mvn clean package -DskipTests -q)
    info "Build complete"
}

# --- List examples ---
list_examples() {
    echo ""
    echo "SwarmAI Examples"
    echo "================"
    echo ""
    echo "Getting Started:"
    echo "  bare-minimum              Simplest: 1 agent, 1 task, no tools"
    echo "  tool-calling [PROBLEM]    Single agent with CalculatorTool"
    echo "  agent-handoff [TOPIC]     Two agents with output dependency"
    echo "  context-variables [TOPIC] Three agents sharing context"
    echo "  multi-turn [TOPIC]        Deep reasoning with maxTurns=5"
    echo ""
    echo "Features:"
    echo "  streaming [TOPIC]            Reactive multi-turn with progress hooks"
    echo "  rag-research <QUERY>         RAG knowledge retrieval + grounded report"
    echo "  customer-support [QUERY]     SwarmGraph routing + agent handoff"
    echo "  error-handling               Failures, budget limits, timeouts"
    echo "  memory [TOPIC]               Conversation memory persistence"
    echo "  human-loop [TOPIC]           Approval gates + revision loops"
    echo "  multi-provider [TOPIC]       Cross-model/temperature comparison"
    echo "  evaluator-optimizer [TOPIC]  Generate, evaluate, optimize loop"
    echo "  agent-testing                Unit tests with mock ChatClient"
    echo "  agent-debate [TOPIC]         Multi-agent debate discussion"
    echo "  multi-language [TEXT]         Multi-language translation"
    echo "  scheduled [TOPIC]            Cron-scheduled monitoring"
    echo "  visualization [TOPIC]        Mermaid workflow visualization"
    echo "  yaml-dsl [TOPIC]             YAML workflow definition"
    echo ""
    echo "Production Workflows:"
    echo "  stock-analysis <TICKER>      Financial analysis (needs API key)"
    echo "  competitive-analysis <QUERY> Competitive market analysis"
    echo "  due-diligence <TICKER>       Investment due diligence"
    echo "  mcp-research <QUERY>         MCP Model Context Protocol research"
    echo "  iterative-memo <QUERY>       Iterative investment memo refinement"
    echo "  codebase-analysis [PATH]     Code architecture analysis"
    echo "  web-research <QUERY>         Web search research pipeline"
    echo "  data-pipeline [FILE]         Data processing pipeline"
    echo ""
    echo "Advanced:"
    echo "  self-improving <QUERY>       Self-improving agent learning"
    echo "  enterprise-governed <QUERY>  Enterprise governance + SPI hooks"
    echo "  enterprise-self-improving    Enterprise self-improving workflow"
    echo "  deep-rl [TOPIC]              Deep reinforcement learning (DQN)"
    echo "  plan-loop                    Evolving plan loop: policies + LlmReplanner + Subagent (1.0.19+)"
    echo "  bandit-learning              Visible bandit learning curve + promotion gate (1.0.19+, no LLM)"
    echo "  self-improving-plan-loop     Closed-loop self-improvement: bandit changes policy decisions over time (1.0.19+)"
    echo "  task-list                    Agent-driven todo list with task_create / task_update tools (1.0.19+)"
    echo "  system-reminders             SystemReminderChannel: harness-injected <system-reminder> blocks per turn (1.0.19+)"
    echo "  ask-user-question            Agent pauses to ask the user a structured question via ask_user_question tool (1.0.19+)"
    echo "  slash-commands               User-invokable skills mid-conversation via /<name> syntax (1.0.19+)"
    echo "  edit-discipline              Read-before-Edit invariant + unique-match + stale-read (1.0.19+)"
    echo "  tool-search                  Deferred tool loading: agent has only tool_search to discover the catalog (1.0.19+)"
    echo "  typed-message-history        Sealed-record typed conversation log with type-safe queries (1.0.19+)"
    echo "  deferred-tool-loading        True deferred loading: tool_search + tool_load multi-turn orchestration (1.0.19+)"
    echo "  background-tasks             Async LLM dispatch via background_spawn/list/output/stop (1.0.19+)"
    echo "  wait-for-signal              Pause-resume: agent blocks on wait_for_signal until external signal arrives (1.0.21+)"
    echo "  codex-skill-creation [GAP]   Use Codex CLI to create SwarmAI skills on the fly via CodingAgentSkillGenerator (1.0.21+)"
    echo ""
    echo "Swarm Patterns:"
    echo "  pentest-swarm <TARGET>       Security penetration testing swarm"
    echo "  competitive-swarm <QUERY>    Competitive research parallel swarm"
    echo "  investment-swarm <QUERY>     Investment analysis parallel swarm"
    echo ""
    echo "Composite:"
    echo "  audited-research <QUERY>     Audit trail research pipeline"
    echo "  governed-pipeline <QUERY>    Governed pipeline with checkpoints"
    echo "  secure-ops <QUERY>           Secure operations compliance"
    echo ""
    echo "New-Tool Showcase (one example per tool — see each README.md for keys / Docker):"
    echo "  wikipedia [SUBJECT]          Research agent — Wikipedia (no key)"
    echo "  wolfram [QUESTION]           Math/science — WOLFRAM_APPID"
    echo "  arxiv [TOPIC]                Paper search — arXiv (no key)"
    echo "  weather [CITY]               Forecast — OPENWEATHER_API_KEY"
    echo "  jira [JQL]                   Issue triage — JIRA_BASE_URL + EMAIL + API_TOKEN"
    echo "  pinecone                     Vector RAG round-trip — PINECONE_API_KEY + INDEX_HOST"
    echo "  s3 [BUCKET]                  Object round-trip — AWS creds or LocalStack"
    echo "  openapi [SPEC_URL]           Universal API client — any OpenAPI 3.x spec"
    echo "  spring-data [QUESTION]       Domain query via JpaRepository (embedded H2)"
    echo "  kafka [TOPIC]                Event publish — KAFKA_BOOTSTRAP_SERVERS"
    echo "  image-gen [PROMPT]           DALL-E image gen — OPENAI_API_KEY"
    echo "  desktop-tidy [FOLDER]        Tidy a folder via the cross-platform os_filesystem tool (Win/Mac/Linux + 1.0.13+)"
    echo ""
    echo "Applications (REST APIs):"
    echo "  customer-support-app         Customer support REST API (port 8080)"
    echo "  rag-app                      RAG knowledge base REST API (port 8080)"
    echo ""
    echo "Usage: ./run.sh <example> [args...]"
    echo ""
    echo "LLM provider:"
    echo "  Default profile is 'ollama' (local, free, no API keys required)."
    echo "  Switch to OpenAI with SPRING_PROFILES_ACTIVE before the call:"
    echo "    SPRING_PROFILES_ACTIVE=openai-mini ./run.sh tool-calling      # gpt-4o-mini  (recommended, ~95% cheaper)"
    echo "    SPRING_PROFILES_ACTIVE=openai      ./run.sh tool-calling      # gpt-4o"
    echo "    SPRING_PROFILES_ACTIVE=openai-o3   ./run.sh tool-calling      # o3-mini  (reasoning model)"
    echo ""
    echo "Sweep runner:"
    echo "  ./.infra/scripts/cheap-model-sweep.sh --quick   # smoke 4 examples on gpt-4o-mini"
    echo "  ./.infra/scripts/cheap-model-sweep.sh           # full 16-example sweep on gpt-4o-mini"
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

    # Profile detection — if SPRING_PROFILES_ACTIVE points to an OpenAI profile,
    # skip Ollama entirely. Lets users run with cheap models via:
    #   SPRING_PROFILES_ACTIVE=openai-mini ./run.sh agent-handoff
    local profile="${SPRING_PROFILES_ACTIVE:-ollama}"
    # DEMO=1 → silence Spring Boot/Hibernate/Hikari startup chatter so the
    # example's own output is the first thing the user sees. The demo profile
    # layers cleanly on top of any model profile.
    if [ "${DEMO:-0}" = "1" ] && [[ "$profile" != *,demo* && "$profile" != demo* ]]; then
        profile="$profile,demo"
    fi
    local using_openai="false"
    case "$profile" in
        openai|openai-mini|openai-o3)
            using_openai="true"
            if [ -z "${OPENAI_API_KEY:-}" ]; then
                error "Profile '$profile' requires OPENAI_API_KEY. Set it in .env or export it."
                exit 1
            fi
            info "Using profile '$profile' (OpenAI). Skipping Ollama setup."
            ;;
    esac

    if [ "$using_openai" = "false" ]; then
        # Detect or start Ollama
        if ! detect_ollama; then
            warn "Ollama not found. Starting via Docker..."
            if ! command -v docker &> /dev/null; then
                error "Neither Ollama nor Docker found. Install one of them first."
                exit 1
            fi
            start_ollama_docker
        fi

        ensure_model

        if [ "${1:-}" = "--setup" ]; then
            info "Setup complete. Ollama is running at $OLLAMA_HOST with model $OLLAMA_MODEL"
            exit 0
        fi
    elif [ "${1:-}" = "--setup" ]; then
        info "Setup complete. OpenAI profile '$profile' configured."
        exit 0
    fi

    build_jar

    local workflow="$1"
    shift
    local studio_flag="false"
    [ "$STUDIO" = "1" ] && studio_flag="true"

    info "Running: $workflow $*"
    echo ""

    local extra_args=""
    if [ "$workflow" = "customer-support-app" ]; then
        # Re-enable web mode (default is now `none` so CLI workflows don't crash on missing Netty)
        extra_args="--swarmai.customer-support.enabled=true --spring.main.web-application-type=reactive"
        studio_flag="true"
    fi
    if [ "$workflow" = "desktop-tidy" ]; then
        # CLI workflow — no embedded web server needed; suppress autodetected reactive startup.
        # Cross-platform via the swarmai.tools.os category — runs on Windows, macOS, Linux.
        extra_args="--swarmai.tools.os.enabled=true --spring.main.web-application-type=none"
    fi
    if [ "$workflow" = "rag-app" ]; then
        extra_args="--swarmai.rag-app.enabled=true --spring.main.web-application-type=reactive"
        extra_args="$extra_args --spring.ai.vectorstore.chroma.client.host=localhost"
        extra_args="$extra_args --spring.ai.vectorstore.chroma.client.port=8000"
        extra_args="$extra_args --spring.ai.vectorstore.chroma.collection-name=swarmai-rag"
        extra_args="$extra_args --spring.ai.ollama.embedding.model=nomic-embed-text"
        extra_args="$extra_args --spring.ai.ollama.embedding.enabled=true"
        studio_flag="true"
        if ! curl -s --connect-timeout 2 "http://localhost:8000/api/v1/heartbeat" > /dev/null 2>&1 && \
           ! curl -s --connect-timeout 2 "http://localhost:8000/api/v2/heartbeat" > /dev/null 2>&1; then
            warn "Chroma vector store not detected at localhost:8000"
            warn "To enable semantic search: docker run -d --name swarmai-chroma -p 8000:8000 ghcr.io/chroma-core/chroma:latest"
        fi
    fi

    if [ "$using_openai" = "true" ]; then
        java -jar "$JAR" "$workflow" "$@" \
            --spring.profiles.active="$profile" \
            --swarmai.studio-keep-alive="$studio_flag" \
            $extra_args
    else
        java -jar "$JAR" "$workflow" "$@" \
            --spring.profiles.active="$profile" \
            --spring.ai.ollama.base-url="$OLLAMA_HOST" \
            --spring.ai.ollama.chat.options.model="$OLLAMA_MODEL" \
            --swarmai.studio-keep-alive="$studio_flag" \
            $extra_args
    fi
}

main "$@"
