# Codebase Analysis Workflow

Parallel multi-agent workflow that analyzes a codebase's architecture, code quality metrics, and dependencies simultaneously, then synthesizes findings into a comprehensive technical report.

## Architecture

```
  Layer 0 (PARALLEL -- all 3 run concurrently):

+-------------------+  +-------------------+  +-------------------+
| Software          |  | Code Quality      |  | Dependency        |
| Architect         |  | Engineer          |  | Analyst           |
| (DirectoryRead,   |  | (ShellCommand,    |  | (FileRead,        |
|  FileRead,        |  |  FileRead,        |  |  XMLParse,        |
|  XMLParse)        |  |  DirectoryRead)   |  |  JSONTransform)   |
+--------+----------+  +--------+----------+  +--------+----------+
         |                       |                      |
         +-----------------------+----------------------+
                                 |
  Layer 1 (depends on all 3):
                                 |
                                 v
                      +--------------------+
                      | Technical Writer   |
                      | (no tools --       |
                      |  synthesis only)   |
                      +--------------------+
                                 |
                                 v
                      output/codebase_analysis_report.md
```

## What You'll Learn

- Parallel process with filesystem-focused tool agents
- Seven tools from the SwarmAI tool library working together: DirectoryReadTool, FileReadTool, FileWriteTool, ShellCommandTool, CodeExecutionTool, XMLParseTool, JSONTransformTool
- Agents that execute real shell commands (find, wc, git log) for metrics
- XML parsing of pom.xml for dependency extraction
- Tool-based code quality measurement (file counts, line counts, test coverage indicators)
- Synthesis of multiple analysis streams into a single report

## Prerequisites

- Ollama running locally (or OpenAI/Anthropic API key configured)
- A codebase to analyze (defaults to the current directory)
- No additional API keys required

## Run

```bash
./run.sh codebase-analysis .
./run.sh codebase-analysis /path/to/your/project
./run.sh codebase-analysis ../another-project
```

## How It Works

Three specialist agents analyze a codebase in parallel. The Software Architect scans the directory structure and configuration files (pom.xml, application.yml, Dockerfile) to identify packages, design patterns, and architectural layers. The Code Quality Engineer runs shell commands (find, wc -l, git log) to gather exact file counts, lines of code, and commit history. The Dependency Analyst reads pom.xml and extracts every dependency block with groupId, artifactId, and version, flagging risks like missing versions or snapshot dependencies. A Technical Writer then combines all findings into a structured report with an executive summary, architecture overview, metrics tables, dependency inventory, and prioritized recommendations.

## Key Code

```java
// 7 tools assigned across 3 parallel agents
Agent architect = Agent.builder()
        .role("Senior Software Architect")
        .tool(directoryReadTool)
        .tool(fileReadTool)
        .tool(xmlParseTool)
        .permissionMode(PermissionLevel.WORKSPACE_WRITE)
        .build();

// Parallel process: architecture + metrics + deps -> synthesis
Swarm swarm = Swarm.builder()
        .id("codebase-analysis")
        .task(architectureTask)   // parallel
        .task(metricsTask)        // parallel
        .task(dependencyTask)     // parallel
        .task(synthesisTask)      // depends on all 3
        .process(ProcessType.PARALLEL)
        .build();
```

## Output

- `output/codebase_analysis_report.md` -- Technical report containing:
  - Executive summary with 5 key findings and specific numbers
  - Architecture overview (project structure table, packages, design patterns)
  - Code quality metrics (file counts, line counts, test counts, git history)
  - Dependency analysis (inventory table with groupId/artifactId/version)
  - Top 3 prioritized improvement recommendations

## Customization

- Pass any filesystem path as the first argument to analyze a different codebase
- Add language-specific agents (e.g., a Python dependency analyst for requirements.txt)
- Extend the metrics agent's shell commands to include complexity analysis tools (e.g., PMD, Checkstyle)
- Add the CodeExecutionTool to the architect agent for running custom analysis scripts
- Increase `maxTurns` on agents (default 3) for deeper file exploration

## YAML DSL

This workflow can also be defined declaratively in YAML. See [`workflows/codebase-analysis.yaml`](src/main/resources/workflows/codebase-analysis.yaml):

```bash
# Load and run via YAML instead of Java
Swarm swarm = swarmLoader.load("workflows/codebase-analysis.yaml",
    Map.of());
SwarmOutput output = swarm.kickoff(Map.of());
```

The YAML definition includes parallel 4-agent analysis with tool hooks.
