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

## Running Examples

### From your IDE (recommended)

Every workflow class has its own `main()` method. Open any example in IntelliJ / VS Code and right-click → Run:

```
StockAnalysisWorkflow.main()          → runs stock-analysis AAPL
CompetitiveAnalysisWorkflow.main()    → runs competitive-analysis
AuditedResearchWorkflow.main()        → runs audited-research
GovernedPipelineWorkflow.main()       → runs governed-pipeline
SecureOpsWorkflow.main()              → runs secure-ops
```

Pass CLI arguments to override defaults (e.g., `TSLA` instead of `AAPL`).

### From the command line

```bash
# Build
mvn clean package -DskipTests

# Run any workflow
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar <workflow-type> [options]

# Examples
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar stock-analysis TSLA
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar competitive-analysis "AI trends 2026"
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar audited-research "AI agent frameworks in enterprise 2026"
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar governed-pipeline "AI infrastructure market 2026"
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar secure-ops "REST API security best practices"
```

**Or run with Docker** (Ollama + Redis included, no local setup needed):

```bash
mvn clean package -DskipTests
cd docker
docker compose up --build

# Run a different workflow:
WORKFLOW=audited-research WORKFLOW_ARGS="AI agent frameworks 2026" docker compose up --build
```

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
| **Enterprise Governed** | `enterprise-governed <QUERY>` | Self-Improving | Self-improving + multi-tenancy + budget tracking + human-in-the-loop governance gates |
| **Pentest Swarm** | `pentest-swarm <TARGET>` | Parallel | Distributed penetration testing with parallel agents and shared skill generation |
| **Competitive Swarm** | `competitive-swarm <QUERY>` | Parallel | Parallel company analysis with shared skills across agents |
| **Investment Swarm** | `investment-swarm <QUERY>` | Parallel | Multi-company investment analysis with cross-agent skill sharing |

### Composite Examples

These higher-level examples combine multiple framework features to achieve complex goals. Each demonstrates 5-8 features working together.

| Workflow | Command | Features Combined | Description |
|---|---|---|---|
| **Audited Research** | `audited-research <QUERY>` | Multi-turn reasoning, conversation compaction, tool hooks (audit + sanitize + rate-limit), permission levels, decision tracing, event replay, structured logging | Research pipeline with full observability and security controls. The researcher agent reasons across 5 turns with auto-compaction, every tool call is audited and sanitized, and the entire workflow is recorded for replay. |
| **Governed Pipeline** | `governed-pipeline <QUERY>` | Type-safe state channels, sealed lifecycle/validation, composite process (Parallel→Hierarchical→Iterative), checkpoints, Mermaid diagram, functional graph API, budget tracking, lifecycle hooks | Multi-stage analysis with compile-time validation, checkpoints between stages, and budget enforcement. Generates a Mermaid diagram before execution and uses a functional quality gate for review routing. |
| **Secure Ops** | `secure-ops <QUERY>` | Permission tiers (READ_ONLY→WORKSPACE_WRITE→DANGEROUS), compliance tool hooks, timing hooks, decision tracing, event replay, structured logging, budget tracking, reactive multi-turn | Security assessment with tiered agent permissions. Recon agents are READ_ONLY with multi-turn reasoning, analysis agents are WORKSPACE_WRITE, and all tool calls are checked against compliance rules. |

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
│       ├── enterprise/                      # Enterprise governed + self-improving
│       ├── competitive/                     # Competitive research swarm
│       ├── investment/                      # Investment analysis swarm
│       └── pentest/                         # Distributed pentest swarm
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

## License

MIT License — see [LICENSE](LICENSE) for details.
