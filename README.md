# SwarmAI Examples

Example workflows demonstrating the [SwarmAI](https://github.com/IntelliSwarm-ai) multi-agent framework — a Java/Spring Boot toolkit for orchestrating AI agents that collaborate on complex tasks.

Each example showcases a different orchestration pattern (sequential, parallel, hierarchical, iterative, self-improving) applied to a real-world use case. All examples leverage the latest framework features including reactive multi-turn reasoning, conversation compaction, tool permission levels, tool hooks, and budget tracking.

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- **SwarmAI framework libraries** installed to your local Maven repository:
  - `swarmai-core`
  - `swarmai-tools`
  - `swarmai-studio`
  - `swarmai-dsl`
  - `swarmai-rl`
  - `swarmai-enterprise` (for enterprise and Deep RL examples)
- An LLM backend — either:
  - [Ollama](https://ollama.com/) running locally (GPU recommended), or
  - An OpenAI / Anthropic API key configured via Spring AI properties

## Configuration

Copy `.env.example` to `.env` and fill in your API keys:

```bash
cp .env.example .env
# Edit .env — set at least ALPHA_VANTAGE_API_KEY for data-dependent workflows
```

Workflows that need external data (stock-analysis, competitive-analysis, etc.) will **refuse to start** if no search API key is configured. Workflows like `codebase-analysis`, `data-pipeline`, and `secure-ops` work without any keys.

## Framework Editions

SwarmAI is available in multiple editions. The framework uses an SPI (Service Provider Interface) architecture — enterprise features are gated by the `LicenseProvider` SPI so the same codebase runs across all tiers.

| Edition | Max Agents | Key Features | Target |
|---|---|---|---|
| **Community** | 5 | All orchestration patterns, tools, DSL, budget tracking, observability, SwarmGraph | Individual developers, prototyping |
| **Team** | 25 | Community + shared memory, knowledge bases, team collaboration | Small teams, startups |
| **Business** | 100 | Team + governance gates, multi-tenancy, approval workflows | Mid-size organizations |
| **Enterprise** | Unlimited | Business + Deep RL, SPI extensions (audit, metering, licensing), custom integrations | Large enterprises, regulated industries |

### SPI Extension Points

Enterprise deployments can plug in custom implementations for three core SPIs:

| SPI Interface | Community Default | Enterprise Example |
|---|---|---|
| **`AuditSink`** | No-op (silent) | JDBC, Elasticsearch — persistent audit trail for compliance |
| **`LicenseProvider`** | Always-valid community license | JWT/RSA license validation with feature flags |
| **`MeteringSink`** | No-op (silent) | Stripe, custom billing — per-workflow/token usage recording |

All examples in this repository work with the Community edition. Enterprise-specific examples (`enterprise-governed`, `deep-rl`, `deep-rl-benchmark`) demonstrate the enterprise SPI integration points with logging-based implementations that can be swapped for production backends.

## Running Examples

### From your IDE (recommended)

Every workflow class has its own `main()` method. Open any example in IntelliJ / VS Code and right-click → Run:

```
BareMinimumExample.main()             → runs bare-minimum (start here!)
ToolCallingExample.main()             → runs tool-calling
CustomerSupportWorkflow.main()        → runs customer-support
StockAnalysisWorkflow.main()          → runs stock-analysis AAPL
GovernedPipelineWorkflow.main()       → runs governed-pipeline
```

Pass CLI arguments to override defaults (e.g., `TSLA` instead of `AAPL`).

### From the command line

```bash
# Build
mvn clean package -DskipTests

# Run any workflow
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar <workflow-type> [options]

# Start with the basics
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar bare-minimum
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar tool-calling "What is 15% of 2340?"
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar agent-handoff "quantum computing"
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar customer-support "I was charged twice for my subscription"

# Production workflows
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar stock-analysis TSLA
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar competitive-analysis "AI trends 2026"
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar governed-pipeline "AI infrastructure market 2026"
```

**Or run with Docker** (Ollama + Redis included, no local setup needed):

```bash
mvn clean package -DskipTests
cd docker
docker compose up --build

# Run a different workflow:
WORKFLOW=audited-research WORKFLOW_ARGS="AI agent frameworks 2026" docker compose up --build

# Enable Studio UI in Docker (container stays alive for inspection):
SWARMAI_STUDIO_ENABLED=true docker compose up --build
```

> **Exit behavior:** By default, Docker runs disable SwarmAI Studio so the container exits cleanly after the workflow completes. Without this, the embedded web server keeps the JVM alive indefinitely. Set `SWARMAI_STUDIO_ENABLED=true` if you want to inspect results in the Studio UI after the run.

### Benchmarking

All workflows produce metrics files in `output/<workflow>_metrics.json`. Use the benchmark script to run multiple workflows and generate a summary:

```bash
# Run default workflows (codebase-analysis, data-pipeline)
./scripts/benchmark.sh

# Run specific workflows
./scripts/benchmark.sh data-pipeline secure-ops audited-research

# Run all workflows
./scripts/benchmark.sh --all
```

The summary is written to `output/benchmark_summary.json` with per-workflow token counts, cost estimates, tool call stats, and execution times.

## Workflow Catalog

### Basic Examples (Start Here)

Single-concept examples that each teach ONE framework feature in isolation. Perfect for learning.

| Example | Command | What It Teaches | Lines |
|---|---|---|---|
| **Bare Minimum** | `bare-minimum [TOPIC]` | Simplest possible setup: 1 agent, 1 task, sequential process, no tools | ~80 |
| **Tool Calling** | `tool-calling [PROBLEM]` | Single agent using a CalculatorTool to solve math problems | ~90 |
| **Agent Handoff** | `agent-handoff [TOPIC]` | Two agents with `dependsOn` — researcher output feeds into editor | ~110 |
| **Context Variables** | `context-variables [TOPIC]` | Three agents sharing structured context (topic, audience, tone, wordCount) via inputs map | ~150 |
| **Multi-Turn** | `multi-turn [TOPIC]` | Single agent with `maxTurns(5)` and `CompactionConfig` for deep multi-step reasoning | ~90 |

### Feature Examples

Each demonstrates a specific framework capability with a focused use case.

| Example | Command | Pattern | What It Demonstrates |
|---|---|---|---|
| **Streaming Output** | `streaming [TOPIC]` | Sequential | Reactive multi-turn execution with progress hooks showing incremental output |
| **RAG Research** | `rag-research <QUERY>` | Sequential | Knowledge base retrieval + evidence-grounded report writing using `InMemoryKnowledge` and `SemanticSearchTool` |
| **Customer Support** | `customer-support [QUERY]` | SwarmGraph | Routing + handoff: classifier routes to billing/technical/account/general specialist via conditional edges |
| **Error Handling** | `error-handling` | Sequential | 3 resilience scenarios: tool failure recovery, budget enforcement (HARD_STOP), timeout handling |
| **Memory Persistence** | `memory [TOPIC]` | Sequential | Shared `InMemoryMemory` across agents — save, search, recall, cross-agent knowledge sharing |
| **Human-in-the-Loop** | `human-loop [TOPIC]` | SwarmGraph | Approval gates, checkpoints, revision loops — simulated human review with quality scoring |
| **Multi-Provider** | `multi-provider [TOPIC]` | Sequential | Same task at different temperatures and model variants, with side-by-side comparison |
| **Evaluator-Optimizer** | `evaluator-optimizer [TOPIC]` | SwarmGraph | Generate → evaluate → optimize loop with quality gate (score ≥ 80 to pass) |
| **Agent Testing** | `agent-testing [TOPIC]` | Sequential | Agent output quality evaluation with 5-criterion scoring + JUnit 5 unit tests |
| **Agent Debate** | `agent-debate [PROPOSITION]` | SwarmGraph | Two agents debate (3 rounds), a judge declares the winner — peer interaction pattern |
| **Multi-Language** | `multi-language [TOPIC]` | Parallel | 3 agents analyze in English/Spanish/French, synthesizer produces cross-cultural report |
| **Scheduled Monitoring** | `scheduled [TOPIC]` | Sequential | 3-iteration monitoring with file-based state, trend detection across runs |
| **Visualization** | `visualization` | SwarmGraph | Build 4 graph topologies, generate Mermaid diagrams, no LLM needed for diagram generation |

### Applications (Persistent Services)

Unlike the examples above which are single-run CLI workflows, these are complete web applications that start a REST API server and stay running.

| Application | Command | Description |
|---|---|---|
| **Customer Support** | `customer-support-app` | Full REST API with AI-powered chat, conversation history, product catalog, order management, ticket system, and intelligent agent routing. Runs on port 8080. |

```bash
# Start the service
./scripts/run.sh customer-support-app

# Chat with AI support
curl -X POST http://localhost:8080/api/support/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "I need help with my subscription billing"}'

# Browse products
curl http://localhost:8080/api/support/products

# Place an order
curl -X POST http://localhost:8080/api/support/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "cust-001", "productId": "prod-001", "quantity": 1}'
```

### Production Workflows

| Workflow | Command | Pattern | Description |
|---|---|---|---|
| **Stock Analysis** | `stock-analysis <TICKER>` | Parallel | 3 agents (financial, research, filings) analyze in parallel, then a 4th synthesizes a recommendation |
| **Competitive Analysis** | `competitive-analysis <QUERY>` | Hierarchical | Manager agent coordinates 4 specialists (researcher, data analyst, strategist, writer) |
| **Due Diligence** | `due-diligence <TICKER>` | Parallel | Comprehensive company due diligence across financial, legal, and market dimensions |
| **MCP Research** | `mcp-research <QUERY>` | Sequential | Research using MCP tools (web fetch/search) |
| **Iterative Memo** | `iterative-memo <TICKER> [N]` | Iterative | Research analyst drafts, MD reviews against a 7-point rubric, loop until approved (default: 3 iterations) |
| **Codebase Analysis** | `codebase-analysis [PATH]` | Sequential | Analyze codebase architecture, metrics, and dependencies |
| **Web Research** | `web-research <QUERY>` | Sequential | Deep web research with scraping, fact-checking, and report generation |
| **Data Pipeline** | `data-pipeline [FILE]` | Sequential | AI-powered data profiling, analysis, and insights |
| **Self-Improving** | `self-improving <QUERY>` | Self-Improving | Plans, executes, identifies capability gaps, generates new tools at runtime, re-executes |
| **Enterprise Governed** | `enterprise-governed <QUERY>` | Sequential | Multi-tenancy + budget tracking + governance gates + SPI extensions (audit, licensing, metering) |
| **Pentest Swarm** | `pentest-swarm <TARGET>` | Parallel | Distributed penetration testing with parallel agents and shared skill generation |
| **Competitive Swarm** | `competitive-swarm <QUERY>` | Parallel | Parallel company analysis with shared skills across agents |
| **Investment Swarm** | `investment-swarm <QUERY>` | Parallel | Multi-company investment analysis with cross-agent skill sharing |

### Deep RL Examples (Enterprise)

These examples require the `swarmai-enterprise` module and demonstrate neural network-based policy learning for self-improving workflows.

| Workflow | Command | Description |
|---|---|---|
| **Deep RL** | `deep-rl [TOPIC] [RUNS]` | DQN-powered self-improving workflow that learns optimal agent strategies across runs (default: 5 runs) |
| **Deep RL Benchmark** | `deep-rl-benchmark [N] [ITER]` | Production benchmark: N diverse topics (default 10), ITER iterations each. Tracks learning curves, skill reuse ratios, and convergence speed. Experience buffer persists across sessions. |

### Composite Examples

These higher-level examples combine multiple framework features to achieve complex goals. Each demonstrates 5-8 features working together.

| Workflow | Command | Features Combined | Description |
|---|---|---|---|
| **Audited Research** | `audited-research <QUERY>` | Multi-turn reasoning, conversation compaction, tool hooks (audit + sanitize + rate-limit), permission levels, decision tracing, event replay, structured logging | Research pipeline with full observability and security controls. The researcher agent reasons across 5 turns with auto-compaction, every tool call is audited and sanitized, and the entire workflow is recorded for replay. |
| **Governed Pipeline** | `governed-pipeline <QUERY>` | Type-safe state channels, sealed lifecycle/validation, composite process (Parallel→Hierarchical→Iterative), checkpoints, Mermaid diagram, functional graph API, budget tracking, lifecycle hooks | Multi-stage analysis with compile-time validation, checkpoints between stages, and budget enforcement. Generates a Mermaid diagram before execution and uses a functional quality gate for review routing. |
| **Secure Ops** | `secure-ops <QUERY>` | Permission tiers (READ_ONLY→WORKSPACE_WRITE→DANGEROUS), compliance tool hooks, timing hooks, decision tracing, event replay, structured logging, budget tracking, reactive multi-turn | Security assessment with tiered agent permissions. Recon agents are READ_ONLY with multi-turn reasoning, analysis agents are WORKSPACE_WRITE, and all tool calls are checked against compliance rules. |

### YAML Workflow Definitions

Every example above has a corresponding YAML workflow definition under `src/main/resources/workflows/`. The YAML DSL lets you define workflows declaratively — no Java required.

| YAML File | Process | Features Used |
|---|---|---|
| `basics.yaml` | Sequential | Minimal 1-agent workflow |
| `agent-testing.yaml` | Sequential | Quality evaluation with scoring rubric |
| `audited-research.yaml` | Sequential | ToolHooks (audit, sanitize, rate-limit), multi-turn |
| `data-pipeline.yaml` | Sequential | ToolHooks, task conditions, 3-agent chain |
| `enterprise.yaml` | Sequential | Budget, governance, workflow hooks, tenant isolation |
| `governed-pipeline.yaml` | Sequential | Approval gates, workflow hooks, task conditions, budget |
| `rag-research.yaml` | Sequential | Knowledge sources, evidence-grounded reports |
| `streaming.yaml` | Sequential | Multi-turn with workflow hooks |
| `memory-persistence.yaml` | Sequential | Agent-level memory |
| `multilanguage.yaml` | Sequential | Workflow-level language setting |
| `multiprovider.yaml` | Sequential | Per-agent model selection |
| `stock-analysis.yaml` | Sequential | Tools, compaction config |
| `metrics.yaml` | Sequential | Budget constraints |
| `visualization.yaml` | Sequential | Verbose mode for Studio |
| `secureops.yaml` | Sequential | ToolHooks (audit, sanitize), workflow hooks, budget |
| `scheduled-monitoring.yaml` | Sequential | File-based tools for historical comparison |
| `due-diligence.yaml` | Parallel | Multi-stream parallel analysis, workflow hooks |
| `codebase-analysis.yaml` | Parallel | ToolHooks, 4-agent parallel analysis |
| `web-research.yaml` | Hierarchical | Manager-coordinated 5-agent research |
| `iterative-investment.yaml` | Iterative | Manager review loop, quality criteria |
| `self-improving.yaml` | Self-Improving | Dynamic capability expansion |
| `competitive-swarm.yaml` | Swarm | Distributed fan-out with target discovery |
| `investment-swarm.yaml` | Swarm | Multi-company parallel analysis |
| `pentest.yaml` | Swarm | ToolHooks, DANGEROUS permission, distributed scanning |
| `research-pipeline.yaml` | Sequential | Template variables, budget tracking |
| `composite-analysis.yaml` | Composite | 3-stage pipeline with approval gates |
| `graph-debate.yaml` | Graph | Counter-based loop, conditional edges |
| `graph-customer-support.yaml` | Graph | Category-based routing (BILLING/TECHNICAL/ACCOUNT/GENERAL) |
| `graph-human-loop.yaml` | Graph | Quality gate, revision loop, checkpoints |
| `graph-evaluator.yaml` | Graph | Score threshold + iteration cap feedback loop |

**Loading a YAML workflow:**

```java
@Autowired SwarmLoader swarmLoader;

// Standard workflow
Swarm swarm = swarmLoader.load("workflows/research-pipeline.yaml",
    Map.of("topic", "AI Safety", "outputDir", "output"));
SwarmOutput output = swarm.kickoff(Map.of());

// Graph workflow (with conditional routing)
CompiledWorkflow workflow = swarmLoader.loadWorkflow(
    "workflows/graph-evaluator.yaml");
SwarmOutput output = workflow.kickoff(Map.of("topic", "AI Safety"));
```

**Example YAML with all features:**

```yaml
swarm:
  name: "Full-Featured Example"
  process: SEQUENTIAL
  verbose: true
  tenantId: "acme"

  budget:
    maxTokens: 100000
    maxCostUsd: 5.0
    onExceeded: WARN

  agents:
    researcher:
      role: "Research Analyst"
      goal: "Research {{topic}} thoroughly"
      backstory: "Expert researcher"
      maxTurns: 3
      temperature: 0.2
      tools: [web-search]
      toolHooks:
        - type: audit
        - type: rate-limit
          maxCalls: 10
          windowSeconds: 30

  tasks:
    research:
      description: "Research {{topic}}"
      agent: researcher
    report:
      description: "Write report"
      agent: researcher
      dependsOn: [research]
      condition: "contains('finding')"
      outputFile: "output/report.md"

  governance:
    approvalGates:
      - name: "Review Gate"
        trigger: AFTER_TASK
        policy:
          requiredApprovals: 1
          autoApproveOnTimeout: true

  hooks:
    - point: BEFORE_WORKFLOW
      type: log
      message: "Starting"
    - point: AFTER_TASK
      type: checkpoint
```

## Framework Features Used Across All Examples

Every workflow in this repository uses the latest SwarmAI framework capabilities:

| Feature | What It Does | How Examples Use It |
|---|---|---|
| **Reactive Multi-Turn** | Agents reason across N turns with `<CONTINUE>`/`<DONE>` markers | Research agents use `maxTurns(3-5)` for iterative tool-calling and deeper analysis |
| **Conversation Compaction** | Auto-summarizes old turns when context grows large | Agents with `maxTurns > 1` use `CompactionConfig.of(3, 4000)` to stay within context limits |
| **Tool Permission Levels** | Restricts which tools an agent can access | Research agents are `READ_ONLY`, writers are `WORKSPACE_WRITE`, exploit agents are `DANGEROUS` |
| **Tool Hooks** | Pre/post interceptors on every tool call | Metrics hook on all agents; composite examples add audit, sanitization, rate-limit, and compliance hooks |
| **Budget Tracking** | Monitors token usage and estimated cost | All workflows track via `WorkflowMetricsCollector` with configurable token/cost limits |
| **Decision Tracing** | Records why agents made decisions | Enabled via observability config; composite examples save decision traces to files |
| **Event Replay** | Stores all workflow events for debugging | Composite examples save `WorkflowRecording` to JSON for post-mortem analysis |
| **Type-Safe State** | Channels with merge semantics (appender, counter, lastWriteWins) | `governed-pipeline` uses typed channels for findings accumulation and quality scoring |
| **Checkpoints** | Save/resume workflow state | `governed-pipeline` saves checkpoints between composite stages |
| **Mermaid Diagrams** | Visual workflow representation | `governed-pipeline` generates a diagram before execution |
| **Functional Graph** | Lambda-based routing without agents | `governed-pipeline` uses `addNode`/`addConditionalEdge` for quality gate routing |
| **YAML DSL** | Define workflows in YAML instead of Java | 30 YAML files under `src/main/resources/workflows/` — every example has a YAML equivalent |
| **Enterprise SPI** | Pluggable audit, licensing, and metering extensions | `enterprise-governed` demonstrates AuditSink, LicenseProvider, and MeteringSink with logging implementations |
| **Deep RL** | DQN-based policy learning for agent strategy optimization | `deep-rl` and `deep-rl-benchmark` train neural network policies that improve across runs |

## Orchestration Patterns

### Sequential
Tasks execute one after another — each task's output feeds into the next.

```
[Researcher] ──→ [Writer]
```

### Parallel
Independent tasks run concurrently, then a synthesis task combines all results.

```
[Financial Analyst] ──┐
[Research Analyst]  ──┼──→ [Investment Advisor]
[Filings Analyst]  ──┘
```

### Hierarchical
A manager agent delegates to and coordinates specialist agents.

```
         [Manager]
        /    |    \
[Researcher] [Analyst] [Writer]
```

### Iterative
Tasks execute in a loop with a reviewer agent providing feedback until quality criteria are met.

```
[Analyst] ──→ [Writer] ──→ [Reviewer]
                 ↑              │
                 └── NEEDS_REFINEMENT
                          APPROVED → done
```

### Self-Improving
The workflow analyzes its own capability gaps and generates new tools at runtime.

```
[Planner] ──→ [Analyst] ──→ [Writer] ──→ [Reviewer]
                  ↑                           │
                  └── gap analysis → skill generation → re-execute
```

### Composite (New)
Chains multiple process types sequentially, passing output between stages.

```
[Parallel Research] ──→ [Hierarchical Synthesis] ──→ [Iterative Review]
       Stage 1                  Stage 2                   Stage 3
```

## Metrics and Observability

All workflows produce structured metrics via the `WorkflowMetricsCollector`:

```json
{
  "workflowName": "stock-analysis",
  "executionTimeSec": 45.2,
  "totalTokens": 12450,
  "promptTokens": 8200,
  "completionTokens": 4250,
  "estimatedCostUsd": 0.0186,
  "totalToolCalls": 8,
  "toolCallsDenied": 0,
  "toolCallsWarned": 1,
  "totalTurnsUsed": 6,
  "compactionEvents": 0,
  "budgetPolicy": {
    "maxTotalTokens": 500000,
    "maxCostUsd": 5.0,
    "onExceeded": "WARN"
  }
}
```

Metrics files are written to `output/<workflow>_metrics.json` after each run.

## Project Structure

```
swarm-ai-examples/
├── pom.xml
├── scripts/
│   └── benchmark.sh                        # Benchmark runner for multiple workflows
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml                  # Ollama + Redis + app (any workflow)
├── src/main/java/ai/intelliswarm/swarmai/
│   ├── SwarmAIWorkflowRunner.java          # CLI entry point / dispatcher
│   ├── SimpleSwarmExample.java             # Minimal 2-agent example
│   └── examples/
│       ├── metrics/                         # Shared metrics collector
│       │   └── WorkflowMetricsCollector.java
│       ├── basics/                          # Single-concept basic examples
│       │   ├── BareMinimumExample.java      #   Simplest: 1 agent, 1 task, no tools
│       │   ├── ToolCallingExample.java      #   Agent + CalculatorTool
│       │   ├── AgentHandoffExample.java     #   Two agents with dependsOn
│       │   ├── ContextVariablesExample.java #   Shared context via inputs map
│       │   └── MultiTurnExample.java        #   maxTurns + CompactionConfig
│       ├── streaming/                       # Reactive multi-turn output
│       │   └── StreamingWorkflow.java
│       ├── rag/                             # RAG: knowledge retrieval + grounded report
│       │   └── RAGResearchWorkflow.java
│       ├── customersupport/                 # Routing + handoff with SwarmGraph
│       │   └── CustomerSupportWorkflow.java
│       ├── errorhandling/                   # Resilience: failures, budget, timeouts
│       │   └── ErrorHandlingWorkflow.java
│       ├── memorypersistence/               # Shared memory across agents
│       │   └── ConversationMemoryWorkflow.java
│       ├── humanloop/                       # Approval gates + revision loops
│       │   └── HumanInTheLoopWorkflow.java
│       ├── multiprovider/                   # Cross-temperature/model comparison
│       │   └── MultiProviderWorkflow.java
│       ├── evaluator/                       # Generate → evaluate → optimize loop
│       │   └── EvaluatorOptimizerWorkflow.java
│       ├── agenttesting/                    # Agent quality evaluation + unit tests
│       │   └── AgentTestingWorkflow.java
│       ├── agentchat/                       # Agent-to-agent debate (peer pattern)
│       │   └── AgentDebateWorkflow.java
│       ├── multilanguage/                   # Parallel multilingual analysis
│       │   └── MultiLanguageWorkflow.java
│       ├── scheduled/                       # Scheduled monitoring with trend detection
│       │   └── ScheduledMonitoringWorkflow.java
│       ├── visualization/                   # Mermaid diagram generation for workflows
│       │   └── WorkflowVisualizationExample.java
│       ├── customersupportapp/              # Full REST API application (persistent service)
│       │   ├── CustomerSupportApp.java      #   Core: SwarmGraph, tools, data stores, sessions
│       │   └── CustomerSupportController.java #   REST controller: 5 API endpoints
│       ├── auditedresearch/                 # Composite: multi-turn + hooks + observability
│       │   └── AuditedResearchWorkflow.java
│       ├── governedpipeline/                # Composite: SwarmGraph + checkpoints + budget
│       │   └── GovernedPipelineWorkflow.java
│       ├── secureops/                       # Composite: permissions + compliance + tracing
│       │   └── SecureOpsWorkflow.java
│       ├── stock/                           # Parallel stock analysis
│       ├── research/                        # Hierarchical competitive analysis
│       ├── iterative/                       # Iterative investment memo
│       ├── duediligence/                    # Due diligence workflow
│       ├── mcpresearch/                     # MCP-based research
│       ├── webresearch/                     # Web scraping research
│       ├── codebase/                        # Codebase analysis
│       ├── datapipeline/                    # Data pipeline
│       ├── selfimproving/                   # Self-improving workflow
│       ├── enterprise/                      # Enterprise: governance, tenancy, SPI extensions
│       ├── deeprl/                          # Deep RL: DQN-powered self-improving workflows
│       ├── competitive/                     # Competitive research swarm
│       ├── investment/                      # Investment analysis swarm
│       └── pentest/                         # Distributed pentest swarm
├── src/main/resources/
│   └── workflows/                          # 30 YAML workflow definitions (DSL)
```

## Key Concepts

### Agents

Agents have a **role**, **goal**, and **backstory** that shape their behavior. Temperature, model, tools, permissions, and rate limits are configurable per agent.

```java
Agent analyst = Agent.builder()
    .role("Senior Financial Analyst")
    .goal("Produce accurate, evidence-based financial analysis")
    .backstory("You are a CFA-certified equity research analyst...")
    .chatClient(chatClient)
    .tools(List.of(calculatorTool, webSearchTool))
    .maxTurns(3)                                        // multi-turn reasoning
    .compactionConfig(CompactionConfig.of(3, 4000))     // auto-compact after 4K tokens
    .permissionMode(PermissionLevel.READ_ONLY)          // can only use read tools
    .toolHook(metrics.metricsHook())                    // instrument every tool call
    .temperature(0.1)
    .build();
```

### Tasks
Tasks define **what** an agent should do, with explicit expected outputs and optional dependencies.

```java
Task analysisTask = Task.builder()
    .description("Analyze AAPL's financial health...")
    .expectedOutput("Markdown report with metrics table and risk assessment")
    .agent(analyst)
    .outputFormat(OutputFormat.MARKDOWN)
    .build();

Task synthesisTask = Task.builder()
    .description("Synthesize all prior analyses into a recommendation")
    .agent(advisor)
    .dependsOn(analysisTask)    // waits for analysisTask to complete
    .build();
```

### Swarm (Classic API)
A Swarm orchestrates agents and tasks using a chosen process type.

```java
Swarm swarm = Swarm.builder()
    .agent(analyst).agent(advisor)
    .task(analysisTask).task(synthesisTask)
    .process(ProcessType.PARALLEL)
    .budgetTracker(metrics.getBudgetTracker())
    .budgetPolicy(metrics.getBudgetPolicy())
    .verbose(true)
    .build();

SwarmOutput result = swarm.kickoff(inputs);
```

### SwarmGraph (Compiled API)
The type-safe sealed lifecycle with compile-time validation:

```java
CompiledSwarm swarm = SwarmGraph.create()
    .addAgent(analyst).addAgent(advisor)
    .addTask(researchTask).addTask(writeTask)
    .process(ProcessType.SEQUENTIAL)
    .stateSchema(schema)
    .checkpointSaver(new InMemoryCheckpointSaver())
    .addHook(HookPoint.BEFORE_TASK, ctx -> {
        System.out.println("Starting: " + ctx.taskId());
        return ctx.state();
    })
    .compileOrThrow();  // validates ALL errors at once

SwarmOutput result = swarm.kickoff(AgentState.of(schema, Map.of("topic", "AI")));
```

### Tool Hooks
Intercept every tool call for audit, compliance, rate-limiting, or output sanitization:

```java
ToolHook complianceHook = new ToolHook() {
    @Override
    public ToolHookResult beforeToolUse(ToolHookContext ctx) {
        if (ctx.inputParams().toString().contains(".gov")) {
            return ToolHookResult.deny("Compliance: restricted domain");
        }
        return ToolHookResult.allow();
    }

    @Override
    public ToolHookResult afterToolUse(ToolHookContext ctx) {
        if (ctx.executionTimeMs() > 10_000) {
            return ToolHookResult.warn("SLA breach: " + ctx.executionTimeMs() + "ms");
        }
        return ToolHookResult.allow();
    }
};

Agent agent = Agent.builder()
    .toolHook(complianceHook)
    .toolHook(metrics.metricsHook())
    .build();
```

### Functional Graph API (Lambda-based)
For simple workflows without full Agent/Task definitions:

```java
SwarmGraph.create()
    .addNode("research", state -> Map.of("findings", doResearch(state)))
    .addNode("write", state -> Map.of("report", writeReport(state)))
    .addEdge(SwarmGraph.START, "research")
    .addEdge("research", "write")
    .addConditionalEdge("write", state ->
        state.valueOrDefault("quality_ok", false) ? SwarmGraph.END : "research")
    .compile();
```

### Tools
Built-in tools include `Calculator`, `WebSearch`, `SECFilings`, `WebScrape`, `HttpRequest`, `JSONTransform`, `FileRead`, `FileWrite`, and `ShellCommand`. Tools undergo health checks before assignment, include routing metadata (category, trigger/avoid conditions, tags) for intelligent selection, and declare a `PermissionLevel` that agents must match.

### SwarmAI Studio
Enable `swarmai.studio.enabled=true` to get a web UI at `http://localhost:8080/studio` for inspecting workflow results, agent interactions, and decision traces.

> **Note:** When Studio is enabled (the default for local runs), the application stays alive after workflow completion so you can browse results. When Studio is disabled (the default for Docker), the application exits cleanly with code 0. For CLI one-shot runs, set `SWARMAI_STUDIO_ENABLED=false` to get immediate exit.

### YAML DSL

The `swarmai-dsl` module provides a declarative YAML syntax for defining workflows. Instead of writing Java builder chains, you define agents, tasks, and configuration in a YAML file and load it with `SwarmLoader`:

```yaml
swarm:
  process: SEQUENTIAL
  agents:
    worker:
      role: "Worker"
      goal: "Complete tasks"
      backstory: "Reliable worker"
  tasks:
    task1:
      description: "Do the work"
      agent: worker
```

```java
Swarm swarm = swarmLoader.load("workflow.yaml");
```

Features: all 7 process types, budget tracking, governance gates, tool hooks (audit/sanitize/rate-limit/deny), workflow hooks, task conditions, graph-based routing with conditional edges, composite stages, template variables, knowledge sources, and agent-level memory.

## Configuration

Workflows are configured via Spring Boot properties (`application.yml` or environment variables):

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: mistral:7b

swarmai:
  studio:
    enabled: true
  default:
    verbose: true
    max-rpm: 15
    max-execution-time: 600000
  observability:
    enabled: true
    structured-logging-enabled: true
    decision-tracing-enabled: true
    replay-enabled: true
    metrics-enabled: false          # requires Spring Actuator
    tool-tracing-enabled: true
```

## Competitive Analysis: How SwarmAI Examples Compare

This section compares our example coverage against leading AI agent frameworks and identifies gaps.

### Framework Comparison

| Capability | OpenAI Swarm | CrewAI | AutoGen | Spring AI | LangChain4j | **SwarmAI** |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| Bare minimum / Hello World | Yes (5 basic) | Yes (template) | — | Yes | Yes | Yes (`SimpleSwarmExample`) |
| Sequential pattern | Yes | Yes | Yes | Yes (Chain) | Yes | **Yes** (4 workflows) |
| Parallel pattern | — | — | — | Yes | — | **Yes** (4 workflows) |
| Hierarchical pattern | — | Yes | Yes | Yes (Orchestrator) | — | **Yes** (2 workflows) |
| Iterative / review loop | — | Yes (flows) | — | Yes (Evaluator-Optimizer) | — | **Yes** (2 workflows) |
| Self-improving / meta | — | — | — | — | — | **Yes** (unique) |
| Composite multi-stage | — | — | — | — | — | **Yes** (unique) |
| Swarm coordination | — | — | Yes (distributed) | — | — | **Yes** (3 workflows) |
| Tool / function calling | Yes | Yes | Yes | Yes (MCP) | Yes | **Yes** |
| Tool permissions & hooks | — | — | — | — | — | **Yes** (unique) |
| Budget tracking | — | — | — | — | — | **Yes** (unique) |
| Decision tracing & replay | — | — | — | — | — | **Yes** (unique) |
| MCP integration | — | — | — | Yes | Yes | **Yes** |
| RAG / vector retrieval | — | — | Yes (GraphRAG) | — | Yes (extensive) | **Yes** (`rag-research`) |
| Streaming output | Yes (dedicated) | — | Yes (2 examples) | — | — | **Yes** (`streaming`) |
| Error handling / resilience | — | Yes (guardrails) | — | — | — | **Yes** (`error-handling`) |
| Human-in-the-loop | — | Yes | Yes | — | — | **Yes** (`human-loop`) |
| Memory / persistence | — | Yes (flows) | Yes | — | Yes (4 types) | **Yes** (`memory`) |
| UI / web integration | — | — | Yes (3 UIs) | — | Yes (JavaFX) | Partial (Studio) |
| Evaluation / testing | Yes (evals) | — | — | Yes (AI-powered) | — | **Yes** (`agent-testing` + unit tests) |
| Multi-provider example | — | — | — | — | Yes (15+ providers) | **Yes** (`multi-provider`) |
| Enterprise SPI (audit, licensing, metering) | — | — | — | — | — | **Yes** (unique) |
| Deep RL policy learning | — | — | — | — | — | **Yes** (unique) |
| Multi-tenant isolation | — | — | — | — | — | **Yes** (unique) |
| Per-example READMEs | Yes | Yes | Yes | Yes | Yes | **Yes** (all 30+ examples) |

### Where SwarmAI Leads

- **Orchestration pattern breadth**: 7 patterns (sequential, parallel, hierarchical, iterative, self-improving, composite, swarm) — no other framework covers all of these
- **Observability built-in**: Budget tracking, decision tracing, event replay, and structured logging across every example — unique in the ecosystem
- **Security & governance**: Tool permission tiers, compliance hooks, audit trails, and enterprise governance gates — no competitor has dedicated examples for this
- **Enterprise SPI architecture**: Pluggable audit (AuditSink), licensing (LicenseProvider), and metering (MeteringSink) — clean separation between community and enterprise tiers
- **Self-improving agents**: Dynamic tool generation at runtime is a differentiator — only SwarmAI demonstrates this
- **Deep RL policy learning**: DQN-powered self-improving workflows with persistent experience buffers — enterprise-grade optimization across runs
- **Composite workflows**: Chaining multiple process types (Parallel → Hierarchical → Iterative) in a single pipeline with checkpoints

### Learning Path (Recommended Order)

If you are new to SwarmAI, follow this progression:

1. **Start here** → `SimpleSwarmExample` — minimal 2-agent sequential workflow
2. **Patterns** → `stock-analysis` (parallel) → `competitive-analysis` (hierarchical) → `iterative-memo` (iterative)
3. **Data & code** → `data-pipeline` (sequential ETL) → `codebase-analysis` (multi-agent code review)
4. **Advanced** → `self-improving` (dynamic tools) → `enterprise-governed` (governance + SPI)
5. **Composite** → `audited-research` → `governed-pipeline` → `secure-ops`
6. **Swarm** → `competitive-swarm` → `investment-swarm` → `pentest-swarm`
7. **Enterprise** → `deep-rl` (DQN policy learning) → `deep-rl-benchmark` (production benchmarking)

## Recommended Future Examples

All high, medium, and lower-priority examples from the competitive analysis have been implemented. Potential future additions:

| Example | Pattern | Why It Matters |
|---|---|---|
| **Full Studio Integration** | Composite | Interactive workflow building, real-time agent inspection, and drag-and-drop graph construction via SwarmAI Studio web UI |
| **Database Agent** | Sequential | SQL-querying agent using DatabaseQueryTool for data warehouse analysis |
| **Slack/Email Integration** | Sequential | Agent that reads messages, processes them, and responds via SlackWebhookTool or EmailTool |

### Recommended Structural Improvements

Based on best practices observed across all analyzed frameworks:

1. ~~**Per-example READMEs**~~ — Done. Every example directory now has its own README with architecture diagram, prerequisites, run command, and key concepts
2. **Tiered organization** — Consider grouping into `basics/` → `patterns/` → `features/` → `applications/` → `enterprise/` to create a natural learning progression (similar to Thomas Vitale's structure)
3. **Starter template** — A copy-and-customize skeleton (like CrewAI's Starter Template) so users can bootstrap their own workflows quickly

## License

MIT License — see [LICENSE](LICENSE) for details.
