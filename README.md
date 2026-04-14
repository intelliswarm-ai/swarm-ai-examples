# SwarmAI Examples

Example workflows demonstrating the [SwarmAI](https://github.com/IntelliSwarm-ai) multi-agent framework — a Java/Spring Boot toolkit for orchestrating AI agents that collaborate on complex tasks.

Each example lives in its own descriptive directory at the root of this repository. Browse the folders to find what you need.

## Featured Examples

### [Customer Support REST API](customer-support-rest-api/)
Full production-ready REST API with AI-powered chat, conversation history, product catalog, order management, and intelligent agent routing. Runs on port 8080 with a web UI.

```bash
./customer-support-rest-api/run.sh
# Then: curl -X POST http://localhost:8080/api/support/chat -H "Content-Type: application/json" -d '{"message": "I need help with billing"}'
```

### [RAG Knowledge Base REST API](rag-knowledge-base-rest-api/)
Complete RAG application with document ingestion, vector store integration, semantic search, and multi-agent Q&A. Runs on port 8080 with a web UI.

```bash
./rag-knowledge-base-rest-api/run.sh
```

### [Customer Support Routing](customer-support-routing/)
SwarmGraph-based routing that classifies support tickets and hands off to the right specialist agent (billing, technical, account, or general).

```bash
./customer-support-routing/run.sh "I was charged twice for my subscription"
```

### [RAG Retrieval-Augmented Research](rag-retrieval-augmented-research/)
RAG workflow with vector store search and multi-agent evidence-grounded report writing.

```bash
./rag-retrieval-augmented-research/run.sh "AI governance frameworks"
```

## Quick Start

### Prerequisites
- **Java 21+**
- **Maven 3.9+**
- **SwarmAI framework `1.0.0`** — `swarmai-core`, `swarmai-tools`, `swarmai-dsl` are pulled automatically from Maven Central. `swarmai-studio`, `swarmai-rl`, `swarmai-enterprise` are not yet published — install them locally first via `mvn install` in the framework repo.
- [Ollama](https://ollama.com/) running locally (or OpenAI/Anthropic API key)

### Run any example

```bash
# From each example's directory
./hello-world-single-agent/run.sh

# Or from the root with a workflow name
./run.sh bare-minimum
./run.sh stock-analysis TSLA
./run.sh customer-support "I was charged twice"

# List all available workflows
./run.sh --list
```

### From your IDE

Every workflow class has a `main()` method. Open any example in IntelliJ / VS Code and right-click Run. After opening the project, reimport Maven to detect all source roots.

### Build manually

```bash
mvn clean package -DskipTests
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar bare-minimum
```

## Example Catalog

### Getting Started

| Example | Description |
|---------|-------------|
| [hello-world-single-agent](hello-world-single-agent/) | Simplest possible setup: 1 agent, 1 task, no tools |
| [agent-with-tool-calling](agent-with-tool-calling/) | Single agent using a CalculatorTool for math |
| [agent-to-agent-task-handoff](agent-to-agent-task-handoff/) | Two agents with `dependsOn` — researcher feeds into editor |
| [shared-context-between-agents](shared-context-between-agents/) | Three agents sharing structured context via inputs map |
| [multi-turn-deep-reasoning](multi-turn-deep-reasoning/) | Deep reasoning with `maxTurns=5` and context compaction |

### Features

| Example | Description |
|---------|-------------|
| [streaming-real-time-responses](streaming-real-time-responses/) | Reactive multi-turn execution with progress hooks |
| [**customer-support-routing**](customer-support-routing/) | SwarmGraph routing + agent handoff for support tickets |
| [**customer-support-rest-api**](customer-support-rest-api/) | **Full REST API** with chat, orders, tickets (port 8080) |
| [**rag-retrieval-augmented-research**](rag-retrieval-augmented-research/) | RAG retrieval + grounded report writing |
| [**rag-knowledge-base-rest-api**](rag-knowledge-base-rest-api/) | **Full REST API** with document ingestion + semantic search |
| [error-handling-and-recovery](error-handling-and-recovery/) | Failures, budget limits, timeout handling |
| [conversation-memory-persistence](conversation-memory-persistence/) | Shared memory across agents — save, search, recall |
| [human-approval-gate](human-approval-gate/) | Approval gates + revision loops |
| [multi-llm-provider-switching](multi-llm-provider-switching/) | Same task across different models/temperatures |
| [evaluator-optimizer-feedback-loop](evaluator-optimizer-feedback-loop/) | Generate, evaluate, optimize loop with quality gate |
| [unit-testing-agents-with-mocks](unit-testing-agents-with-mocks/) | Agent quality evaluation + JUnit 5 unit tests |
| [multi-agent-debate](multi-agent-debate/) | Two agents debate, a judge declares the winner |
| [multi-language-translation](multi-language-translation/) | Parallel analysis in English/Spanish/French |
| [scheduled-cron-monitoring](scheduled-cron-monitoring/) | Cron-scheduled monitoring with trend detection |
| [workflow-visualization-mermaid](workflow-visualization-mermaid/) | Mermaid diagram generation for workflows |
| [yaml-workflow-definition](yaml-workflow-definition/) | YAML DSL workflow definition — no Java required |

### Production Workflows

| Example | Description |
|---------|-------------|
| [stock-market-analysis](stock-market-analysis/) | Parallel financial analysis with 3 analysts + synthesizer |
| [competitive-market-analysis](competitive-market-analysis/) | Hierarchical manager coordinates 4 specialists |
| [investment-due-diligence](investment-due-diligence/) | Comprehensive company due diligence |
| [mcp-model-context-protocol](mcp-model-context-protocol/) | Research using MCP tools (web fetch/search) |
| [iterative-investment-memo-refinement](iterative-investment-memo-refinement/) | Draft → review → refine loop until approved |
| [codebase-analysis-workflow](codebase-analysis-workflow/) | Code architecture analysis |
| [web-search-research-pipeline](web-search-research-pipeline/) | Deep web research with fact-checking |
| [data-processing-pipeline](data-processing-pipeline/) | AI-powered data profiling and insights |

### Advanced

| Example | Description |
|---------|-------------|
| [self-improving-agent-learning](self-improving-agent-learning/) | Plans, identifies capability gaps, generates new tools at runtime |
| [enterprise-self-improving-with-governance](enterprise-self-improving-with-governance/) | Self-improving + multi-tenancy + budget + governance gates |
| [enterprise-governance-spi-hooks](enterprise-governance-spi-hooks/) | Enterprise governance with SPI extension points |
| [deep-reinforcement-learning-dqn](deep-reinforcement-learning-dqn/) | DQN-based reinforcement learning for agent optimization |

### Swarm Patterns

| Example | Description |
|---------|-------------|
| [security-penetration-testing-swarm](security-penetration-testing-swarm/) | Parallel pentest agents with shared skill generation |
| [competitive-research-parallel-swarm](competitive-research-parallel-swarm/) | Parallel company analysis with cross-agent skills |
| [investment-analysis-parallel-swarm](investment-analysis-parallel-swarm/) | Multi-company investment analysis in parallel |

### Composite

| Example | Description |
|---------|-------------|
| [audit-trail-research-pipeline](audit-trail-research-pipeline/) | Multi-turn + hooks + observability + audit trail |
| [governed-pipeline-with-checkpoints](governed-pipeline-with-checkpoints/) | Composite process + checkpoints + budget enforcement |
| [secure-operations-compliance](secure-operations-compliance/) | Tiered permissions + compliance hooks + tracing |

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
├── hello-world-single-agent/          # Each example is a top-level directory
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
