# SwarmAI Examples

Example workflows demonstrating the [SwarmAI](https://github.com/IntelliSwarm-ai) multi-agent framework — a Java/Spring Boot toolkit for orchestrating AI agents that collaborate on complex tasks.

Each example lives in its own descriptive directory at the root of this repository. Browse the folders to find what you need.

## Start here if you're new: [`quickstart-template/`](quickstart-template/)

A self-contained Maven project with one `pom.xml` + one `Main.java` + one `application.yml`.
Clone it, `mvn spring-boot:run`, get a cited Wikipedia biography in your terminal in ~60 seconds
(no API keys required — runs against local Ollama by default, swap to OpenAI in one line).
Then the table in its README tells you the one-line change to swap in any of the other tools.

The rest of this repo is a catalog of richer recipes — production patterns for parallel swarms,
human-in-the-loop, RAG, governance hooks, and so on. Come back after the quickstart.

## Featured Examples

### [Self-Evolving Swarm](self-evolving-swarm/) 
The framework rewrites its own topology between runs. Run it once, it executes sequentially. Observations get persisted to H2. Run it again, and it has restructured itself into a parallel swarm — no code changes. The clearest demo of the framework's self-improvement loop.

```bash
./self-evolving-swarm/run.sh
# Then run it again and watch the topology evolve.
```

### [Desktop Tidy (Windows)](desktop-tidy-windows/)
Point the agent at your actual `Desktop`. It proposes per-file moves, you approve each one with `y/N`, and your real desktop gets reorganized. Concrete, reversible action on real files.

```bash
./desktop-tidy-windows/run.sh
```

### [Customer Support REST API](customer-support-rest-api/)
Full production-ready REST API with AI-powered chat, conversation history, product catalog, order management, and intelligent agent routing. Runs on port 8080 with a web UI. **Includes side-by-side comparison vs LangChain4j.**

```bash
./customer-support-rest-api/run.sh
# Then: curl -X POST http://localhost:8080/api/support/chat -H "Content-Type: application/json" -d '{"message": "I need help with billing"}'
```

### [RAG Knowledge Base REST API](rag-knowledge-base-rest-api/)
Complete RAG application with document ingestion, vector store integration, semantic search, and multi-agent Q&A. Runs on port 8080 with a web UI. Eval data: ~12% refusal rate vs LangChain's 36%, ~25s vs 31s p50 latency.

```bash
./rag-knowledge-base-rest-api/run.sh
```

### [Spring Data Repository Agent](spring-data-repository-agent/)
Add the dependency to a Spring Boot app and your agents can now query *every* `JpaRepository` in your codebase via natural language. JVM-only moat — no Python framework can do this.

```bash
./spring-data-repository-agent/run.sh "how many customers are on the PRO tier?"
```

### [Image Generation (DALL·E)](image-generation-dalle/)
Type a prompt, get a PNG on disk. The most shareable artifact in the repo.

```bash
./image-generation-dalle/run.sh "a minimalist vector logo for an AI startup"
```

## Why Java?

Most AI agent frameworks are Python-first. SwarmAI runs on the JVM — this is a feature, not a translation. Three examples that lean into the moat:

| Example | The Java thing |
|---------|----------------|
| **[spring-data-repository-agent](spring-data-repository-agent/)** | Drop-in dependency: agents now query every `@Repository` in your Spring Boot app via natural language. Reuses your entity model, validations, and security. |
| **[customer-support-rest-api](customer-support-rest-api/)** | Full Spring Boot REST microservice with WebFlux streaming, conversation history, JPA persistence — and a side-by-side LangChain4j comparison table. |
| **[kafka-event-publishing](kafka-event-publishing/)** | Idempotent Kafka publish with correlation IDs, Spring Kafka transactions, schema-registry-aware payloads. The kind of integration enterprise teams already need. |

If your team owns a Spring/Java stack, SwarmAI lets you add agents *inside* the existing service — same monorepo, same CI, same observability, same auth model — instead of standing up a Python sidecar.

## Quick Start

### Prerequisites
- **Java 21+**
- **Maven 3.9+**
- **SwarmAI framework `1.0.7`** — `swarmai-core`, `swarmai-tools`, `swarmai-dsl` are pulled automatically from Maven Central. `swarmai-studio`, `swarmai-rl`, `swarmai-enterprise` are not yet published — install them locally first via `mvn install` in the framework repo.
- [Ollama](https://ollama.com/) running locally (or OpenAI/Anthropic API key)

### Run any example

```bash
# From each example's directory
./quickstart-template/run.sh

# Or from the root with a workflow name
./run.sh bare-minimum
./run.sh stock-analysis TSLA
./run.sh customer-support "I was charged twice"

# List all available workflows
./run.sh --list
```

### Pick your LLM provider

| Profile | Model | When to use | Activate |
|--------|-------|-------------|----------|
| `ollama` (default) | `mistral:7b` | Local, no API keys, offline dev | nothing — just run |
| **`openai-mini`** | **`gpt-4o-mini`** | **Recommended for most workflows** — ~95% cheaper than gpt-4o, near-equivalent quality | `--spring.profiles.active=openai-mini` |
| `openai` | `gpt-4o` | Best general-purpose quality | `--spring.profiles.active=openai` |
| `openai-o3` | `o3-mini` | Reasoning-heavy tasks (research, planning) | `--spring.profiles.active=openai-o3` |

Set `OPENAI_API_KEY` in `.env`. Override the model on any profile with `OPENAI_WORKFLOW_MODEL=gpt-4.1-mini` (or any OpenAI model id).

### From your IDE

Every workflow class has a `main()` method. Open any example in IntelliJ / VS Code and right-click Run. After opening the project, reimport Maven to detect all source roots.

### Build manually

```bash
mvn clean package -DskipTests
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar bare-minimum
```

## Iterating on examples

Two scripts cover the inner and outer loops:

```bash
# Inner loop — change something, then verify in ~3 minutes on gpt-4o-mini
./.infra/scripts/cheap-model-sweep.sh --quick

# Outer loop — full 16-example sweep on gpt-4o-mini
./.infra/scripts/cheap-model-sweep.sh

# Both append a row to output/sweep/sweep-history.tsv so you can track
# how pass-rate changes as you edit examples.
```

Each run captures per-example logs in `output/sweep/<workflow>.log`, so a failure points you straight to the culprit. Override the cheap model with `OPENAI_WORKFLOW_MODEL=gpt-4.1-mini ./.infra/scripts/cheap-model-sweep.sh` if you want to compare cheap variants head-to-head.

## Running the regression suite

A curated set of offline-safe examples that exercise the framework end-to-end on local Mistral 7B.

```bash
# Run the curated regression suite locally.
./.infra/scripts/regression.sh

# Preview the curated workflow list.
./.infra/scripts/regression.sh --list
```

What you'll see per example: the workflow's own output (what the agents actually produced) and a compact summary of tokens used.

### Curated workflow list

Selected to run entirely on local Mistral 7B with no external API keys, no vector store, no REST server. The full list is in [`.infra/scripts/regression.sh`](.infra/scripts/regression.sh); headline entries:

`bare-minimum`, `tool-calling`, `agent-handoff`, `context-variables`, `multi-turn`, `error-handling`, `memory`, `multi-provider`, `evaluator-optimizer`, `codebase-analysis`, `data-pipeline`, `self-improving`, `enterprise-governed`, `audited-research`, `governed-pipeline`, `secure-ops`.

Skipped (require external services): `stock-analysis`, `competitive-analysis`, `due-diligence`, `rag-research`, `rag-app`, `customer-support-app`, `competitive-swarm`, `investment-swarm`, `pentest-swarm`.

## Example Catalog

### Onboarding (no API keys required)

| Example | Description |
|---------|-------------|
| [quickstart-template](quickstart-template/) | Self-contained Maven project — clone, run, see a cited Wikipedia bio in 60s |
| [agent-with-tool-calling](agent-with-tool-calling/) | Single agent using a tool to produce a tangible artifact |
| [agent-to-agent-task-handoff](agent-to-agent-task-handoff/) | Two agents with `dependsOn` — researcher feeds into editor |
| [shared-context-between-agents](shared-context-between-agents/) | Three agents sharing structured context via inputs map |
| [multi-turn-deep-reasoning](multi-turn-deep-reasoning/) | Deep reasoning with `maxTurns=5` and context compaction |
| [wikipedia-research](wikipedia-research/) | Source-cited mini-bio via Wikipedia REST — zero config |
| [arxiv-paper-search](arxiv-paper-search/) | Cite recent arXiv papers in a synthesis paragraph — zero config |

### Real-world action (tangible artifacts)

| Example | Description |
|---------|-------------|
| [desktop-tidy-windows](desktop-tidy-windows/) | Reorganize your actual Desktop with per-move y/N approval |
| [image-generation-dalle](image-generation-dalle/) | Prompt → PNG on disk |
| [jira-ticket-management](jira-ticket-management/) | Run JQL, create/update tickets via natural language |
| [stock-market-analysis](stock-market-analysis/) | BUY/HOLD/SELL memo grounded by SEC + EODHD + RSI |
| [eodhd-global-markets](eodhd-global-markets/) | Citation-tagged brief on any global ticker (BMW.XETRA, 7203.TSE) |

### Advanced patterns (the framework's superpowers)

| Example | Description |
|---------|-------------|
| [self-evolving-swarm](self-evolving-swarm/) | Framework rewrites its own topology between runs based on observations |
| [self-improving-agent-learning](self-improving-agent-learning/) | LLM plans the workflow, generates Groovy skills mid-run to fill gaps |
| [governed-pipeline-with-checkpoints](governed-pipeline-with-checkpoints/) | Ten framework features composed: checkpoints, budgets, mermaid emission |
| [audit-trail-research-pipeline](audit-trail-research-pipeline/) | Eight features: PII redaction, rate limit, tracing, replay |
| [security-penetration-testing-swarm](security-penetration-testing-swarm/) | Distributed pentest agents with shared exploit skills (CTF use only) |
| [competitive-research-parallel-swarm](competitive-research-parallel-swarm/) | Per-competitor agents with reviewer-driven gap-filling |
| [investment-analysis-parallel-swarm](investment-analysis-parallel-swarm/) | Five parallel ticker analysts share a runtime-generated P/E parser |
| [secure-operations-compliance](secure-operations-compliance/) | Tiered permissions + compliance hooks + tracing |
| [iterative-investment-memo-refinement](iterative-investment-memo-refinement/) | Draft → review → refine loop until approved |
| [evaluator-optimizer-feedback-loop](evaluator-optimizer-feedback-loop/) | Generate, evaluate, optimize loop with quality gate |
| [multi-agent-debate](multi-agent-debate/) | Two agents debate, a judge declares the winner |
| [deep-reinforcement-learning-dqn](deep-reinforcement-learning-dqn/) | DQN replaces skill-decision logic in the agent loop |

### Enterprise / JVM moat

| Example | Description |
|---------|-------------|
| [spring-data-repository-agent](spring-data-repository-agent/) | Drop-in dependency: agents query every `JpaRepository` in your app |
| [customer-support-rest-api](customer-support-rest-api/) | Production REST microservice with side-by-side LangChain4j comparison |
| [rag-knowledge-base-rest-api](rag-knowledge-base-rest-api/) | Production REST RAG with eval-tuned defaults (citations included) |
| [kafka-event-publishing](kafka-event-publishing/) | Idempotent Kafka publish with correlation IDs + transactions |
| [customer-support-routing](customer-support-routing/) | SwarmGraph routing + agent handoff for support tickets |

### Tool integrations

| Example | Description |
|---------|-------------|
| [openapi-universal-client](openapi-universal-client/) | Drop a spec URL — agent invokes any OpenAPI 3.x endpoint |
| [mcp-model-context-protocol](mcp-model-context-protocol/) | Stdio MCP servers wired in as tools |
| [pinecone-vector-rag](pinecone-vector-rag/) | Pinecone upsert/query/delete round-trip |
| [s3-cloud-storage](s3-cloud-storage/) | S3 / LocalStack put/head/get/list/delete round-trip |
| [wolfram-alpha-math](wolfram-alpha-math/) | Quantitative analyst quotes Wolfram for every numeric claim |
| [openweathermap-forecast](openweathermap-forecast/) | Forecast → packing advice, single-tool demo |
| [rag-retrieval-augmented-research](rag-retrieval-augmented-research/) | Vector-store RAG with multi-agent grounded report writing |
| [web-search-research-pipeline](web-search-research-pipeline/) | Deep web research with fact-checking |
| [data-processing-pipeline](data-processing-pipeline/) | AI-powered CSV/JSON profiling + insights |

### Framework feature demos

| Example | Description |
|---------|-------------|
| [streaming-real-time-responses](streaming-real-time-responses/) | Reactive multi-turn execution with progress hooks |
| [error-handling-and-recovery](error-handling-and-recovery/) | Failures, budget limits, timeout handling |
| [conversation-memory-persistence](conversation-memory-persistence/) | Shared memory across agents — save, search, recall |
| [human-approval-gate](human-approval-gate/) | Approval gates + revision loops |
| [multi-llm-provider-switching](multi-llm-provider-switching/) | Same task across different models/temperatures |
| [unit-testing-agents-with-mocks](unit-testing-agents-with-mocks/) | Agent quality evaluation + JUnit 5 unit tests |
| [multi-language-translation](multi-language-translation/) | Parallel analysis in English/Spanish/French |
| [scheduled-cron-monitoring](scheduled-cron-monitoring/) | Cron-scheduled monitoring with trend detection |
| [workflow-visualization-mermaid](workflow-visualization-mermaid/) | Mermaid diagram generation for workflows |
| [yaml-workflow-definition](yaml-workflow-definition/) | YAML DSL workflow definition — no Java required |
| [investment-due-diligence](investment-due-diligence/) | GO/CAUTION/NO-GO verdict with risk matrix |
| [competitive-market-analysis](competitive-market-analysis/) | Hierarchical manager coordinates 4 specialists |
| [codebase-analysis-workflow](codebase-analysis-workflow/) | Architecture report from real `find`/`wc`/`git log` calls |
| [demo-recorder](demo-recorder/) | Records framework events to JSON for the website's split-pane comparison |

> **Enterprise examples** (multi-tenancy, governance gates, SPI hooks) are in a separate repo: [swarm-ai-examples-enterprise](https://github.com/IntelliSwarm-ai/swarm-ai-examples-enterprise) (BSL 1.1 license).

## Orchestration Patterns

```
Sequential:    [A] ──→ [B] ──→ [C]
Parallel:      [A] ──┐
               [B] ──┼──→ [Synthesizer]
               [C] ──┘
Hierarchical:       [Manager]
                   /    |    \
              [R]    [A]    [W]
Iterative:     [Writer] ──→ [Reviewer] ──→ APPROVED / loop back
Self-Improving: [Plan] → [Execute] → [Gap Analysis] → [Skill Gen] → re-execute
Composite:     [Parallel] ──→ [Hierarchical] ──→ [Iterative Review]
```

## Key Concepts

### Agents
```java
Agent analyst = Agent.builder()
    .role("Senior Financial Analyst")
    .goal("Produce accurate, evidence-based analysis")
    .chatClient(chatClient)
    .tools(List.of(calculatorTool, webSearchTool))
    .maxTurns(3)
    .permissionMode(PermissionLevel.READ_ONLY)
    .toolHook(metrics.metricsHook())
    .temperature(0.1)
    .build();
```

### Tasks
```java
Task task = Task.builder()
    .description("Analyze AAPL's financial health...")
    .expectedOutput("Markdown report with metrics and risk assessment")
    .agent(analyst)
    .dependsOn(researchTask)
    .outputFormat(OutputFormat.MARKDOWN)
    .build();
```

### Swarm
```java
Swarm swarm = Swarm.builder()
    .agent(analyst).agent(advisor)
    .task(analysisTask).task(synthesisTask)
    .process(ProcessType.PARALLEL)
    .verbose(true).build();
SwarmOutput result = swarm.kickoff(inputs);
```

### YAML DSL
Every example has a YAML equivalent under `.infra/src/main/resources/workflows/`. Define workflows declaratively — no Java required:

```yaml
swarm:
  process: SEQUENTIAL
  agents:
    worker:
      role: "Worker"
      goal: "Complete tasks"
  tasks:
    task1:
      description: "Do the work"
      agent: worker
```

## Project Structure

```
swarm-ai-examples/
├── quickstart-template/                # Onboarding entry point (start here)
│   ├── README.md                      # Description, architecture, how to run
│   ├── run.sh                         # One-click runner
│   └── src/main/java/.../             # Source code
├── customer-support-rest-api/         # Featured: full REST API application
├── rag-knowledge-base-rest-api/       # Featured: RAG with semantic search
├── ... (39 example directories)
├── .infra/                            # Build infrastructure (hidden)
│   ├── src/main/java/                 # Shared code (runner, metrics)
│   ├── src/main/resources/            # Config, YAML workflows, static assets
│   ├── scripts/                       # Legacy run script
│   └── docker/                        # Docker configs
├── run.sh                             # Run any example by name
├── pom.xml                            # Maven build
└── README.md                          # This file
```

## Configuration

Configure via Spring Boot properties (`.infra/src/main/resources/application.yml`) or environment variables:

```bash
export SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434
export SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=mistral:7b
export SWARMAI_STUDIO_ENABLED=false  # Set true to keep Studio UI alive
```

## License

MIT License — see [LICENSE](LICENSE) for details.
