# SwarmAI Examples

Example workflows demonstrating the [SwarmAI](https://github.com/IntelliSwarm-ai) multi-agent framework — a Java/Spring Boot toolkit for orchestrating AI agents that collaborate on complex tasks.

Each example showcases a different orchestration pattern (sequential, parallel, hierarchical, iterative, self-improving) applied to a real-world use case.

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- **SwarmAI framework libraries** installed to your local Maven repository:
  - `swarmai-core`
  - `swarmai-tools`
  - `swarmai-studio`
- An LLM backend — either:
  - [Ollama](https://ollama.com/) running locally, or
  - An OpenAI-compatible API key configured via Spring AI properties

## Configuration

Copy `.env.example` to `.env` and fill in your API keys:

```bash
cp .env.example .env
# Edit .env — set at least ALPHA_VANTAGE_API_KEY for data-dependent workflows
```

Workflows that need external data (stock-analysis, competitive-analysis, etc.) will **refuse to start** if no search API key is configured. Workflows like `codebase-analysis`, `data-pipeline`, and `pentest-swarm` work without any keys.

## Running Examples

### From your IDE (recommended)

Every workflow class has its own `main()` method. Open any example in IntelliJ / VS Code and right-click → Run:

```
StockAnalysisWorkflow.main()          → runs stock-analysis AAPL
CompetitiveAnalysisWorkflow.main()    → runs competitive-analysis
IterativeInvestmentMemoWorkflow.main() → runs iterative-memo NVDA
SelfImprovingWorkflow.main()          → runs self-improving
```

Pass CLI arguments to override defaults (e.g., `TSLA` instead of `AAPL`).

Feature demos under `examples/features/` run standalone with no LLM or Spring context needed.

### From the command line

```bash
# Build
mvn clean package

# Run any workflow
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar <workflow-type> [options]

# Examples
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar stock-analysis TSLA
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar competitive-analysis "AI trends 2026"
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar iterative-memo NVDA 3
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar self-improving "Analyze AAPL with YoY growth"
```

**Or run with Docker** (Ollama + Redis included, no local setup needed):

```bash
mvn clean package -DskipTests
cd docker
docker compose up --build

# Run a different workflow / ticker:
WORKFLOW=iterative-memo WORKFLOW_ARGS="NVDA 3" docker compose up --build
```

## Workflow Catalog

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

## Framework Feature Demos

These standalone examples demonstrate the new enterprise features added to the SwarmAI core framework. They run without LLM keys or Spring context.

```bash
# Run any feature example directly
mvn exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.TypeSafeStateExample"
mvn exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.FunctionalGraphExample"
mvn exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.CheckpointExample"
```

| Example | Feature | What it shows |
|---------|---------|--------------|
| `TypeSafeStateExample` | Type-Safe State | `AgentState` with `Optional<T>` access, `Channels` (appender, counter, lastWriteWins), immutable updates, concurrent merge semantics |
| `SealedLifecycleExample` | Sealed Lifecycle | `SwarmGraph` → `compile()` → `CompiledSwarm`, exhaustive pattern matching on errors, `CompilationError` sealed hierarchy |
| `CheckpointExample` | Checkpoints | `Checkpoint.create()`, `InMemoryCheckpointSaver`, save/load/resume workflow state |
| `HookSystemExample` | Hook System | `SwarmHook` lambdas at `BEFORE_WORKFLOW`, `AFTER_TASK`, `ON_ERROR` etc., unified cross-cutting concerns |
| `DiagramExample` | Diagram Generation | `MermaidDiagramGenerator` produces GitHub-renderable flowcharts from compiled workflows |
| `FunctionalGraphExample` | Functional Graph API | `addNode()` / `addEdge()` / `addConditionalEdge()` with lambdas, conditional routing loops |

## Project Structure

```
swarm-ai-examples/
├── pom.xml
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml              # Ollama + Redis + app (any workflow)
├── src/main/java/ai/intelliswarm/swarmai/
│   ├── SwarmAIWorkflowRunner.java          # CLI entry point / dispatcher
│   ├── SimpleSwarmExample.java             # Minimal 2-agent example
│   └── examples/
│       ├── features/                        # Framework feature demos (no LLM needed)
│       │   ├── TypeSafeStateExample.java
│       │   ├── SealedLifecycleExample.java
│       │   ├── CheckpointExample.java
│       │   ├── HookSystemExample.java
│       │   ├── DiagramExample.java
│       │   └── FunctionalGraphExample.java
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
Agents have a **role**, **goal**, and **backstory** that shape their behavior. Temperature, model, tools, and rate limits are configurable per agent.

```java
Agent analyst = Agent.builder()
    .role("Senior Financial Analyst")
    .goal("Produce accurate, evidence-based financial analysis")
    .backstory("You are a CFA-certified equity research analyst...")
    .chatClient(chatClient)
    .tools(List.of(calculatorTool, webSearchTool))
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
    .verbose(true)
    .build();

SwarmOutput result = swarm.kickoff(inputs);
```

### SwarmGraph (New Compiled API)
The type-safe sealed lifecycle with compile-time validation:

```java
CompiledSwarm swarm = SwarmGraph.create()
    .addAgent(analyst).addAgent(advisor)
    .addTask(researchTask).addTask(writeTask)
    .process(ProcessType.SEQUENTIAL)
    .checkpointSaver(new InMemoryCheckpointSaver())
    .addHook(HookPoint.BEFORE_TASK, ctx -> {
        System.out.println("Starting: " + ctx.taskId());
        return ctx.state();
    })
    .compileOrThrow();  // validates ALL errors at once

SwarmOutput result = swarm.kickoff(AgentState.of(Map.of("topic", "AI")));
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
Built-in tools include `Calculator`, `WebSearch`, `SECFilings`, `WebScrape`, `HttpRequest`, `JSONTransform`, `FileRead`, `FileWrite`, and `ShellCommand`. Tools undergo health checks before assignment, and include routing metadata (category, trigger/avoid conditions, tags) for intelligent tool selection.

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
  workflow:
    model: gpt-4o-mini
    max-iterations: 3
    verbose: true
    max-rpm: 15
```

## License

MIT License — see [LICENSE](LICENSE) for details.
