# SwarmAI Framework Regression Testing

## Overview

The LLM-as-Judge regression testing system evaluates the **SwarmAI framework's** orchestration quality across all example workflows. It uses an external LLM (GPT-4o or Claude) to judge framework behavior — not the underlying model's text quality.

Results are date-stamped so successive runs can be compared to track framework improvement or degradation across releases.

## What It Evaluates

Each workflow is scored on 6 **framework-centric** dimensions (0-100):

| Dimension | What It Measures |
|-----------|-----------------|
| **Orchestration** | Agent coordination, routing, handoffs, task dependency resolution |
| **Task Decomposition** | How well the framework breaks goals into agent tasks |
| **Tool Integration** | Tool management, hooks, permission enforcement |
| **Error Handling** | Retries, budget enforcement, timeouts, fallback strategies |
| **Observability** | Metrics, logging, tracing, budget tracking |
| **Architecture Pattern** | How well the pattern (sequential/parallel/graph/etc.) fits the use case |

The judge explicitly ignores LLM text quality — a framework can score 90/100 even when the underlying Mistral 7B produces mediocre text.

## Prerequisites

### Required
- **Java 21+**
- **Maven 3.8+**
- **Ollama** running with `mistral:latest` model
- **OpenAI API key** (for GPT-4o judge) OR **Anthropic API key** (for Claude judge)

### Optional (for data-dependent workflows)
- Alpha Vantage API key (stock data)
- Finnhub API key (financial data)
- NewsAPI key (news search)
- Google Custom Search API key + Search Engine ID

### Setup

1. Copy `.env.example` to `.env` and add your API keys:

```bash
cp .env.example .env
# Edit .env and add at minimum:
# OPENAI_API_KEY=sk-...
```

2. Ensure Ollama is running with `mistral:latest`:

```bash
ollama pull mistral:latest
ollama serve  # if not already running
```

3. Build the framework (if you have local changes):

```bash
# From the swarm-ai framework directory
mvn -f /path/to/swarm-ai/pom.xml clean install -DskipTests

# Then rebuild examples
mvn clean package -DskipTests
```

## Running the Regression Suite

### Full Run (all workflows)

```bash
# Source API keys
set -a; source .env; set +a

# Detect Ollama host (WSL auto-detects Windows host)
OLLAMA_HOST="http://$(ip route show default | awk '{print $3}'):11434"

# Run all 27 workflows with judge evaluation
java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar judge-all \
  --spring.ai.ollama.base-url="$OLLAMA_HOST" \
  --spring.ai.ollama.chat.options.model="mistral:latest" \
  --swarmai.studio-keep-alive=false
```

Or use the run script:
```bash
./run.sh judge-all
```

### Single Workflow

```bash
# Run a specific workflow (judge is automatically invoked if OPENAI_API_KEY is set)
./run.sh bare-minimum
./run.sh stock-analysis AAPL
./run.sh evaluator-optimizer "sustainable energy"
```

### Configuration

Judge behavior is configured in `application.yml` under `swarmai.judge`:

```yaml
swarmai:
  judge:
    enabled: true                    # Set to false to disable judging
    provider: openai                 # "openai" or "anthropic"
    model: gpt-4o                    # Judge model
    openai-api-key: ${OPENAI_API_KEY:}
    anthropic-api-key: ${ANTHROPIC_API_KEY:}
    output-dir: judge-results        # Directory name within each example
```

## Output Structure

Each workflow produces two date-stamped files in its `judge-results/` directory:

```
hello-world-single-agent/
  judge-results/
    bare-minimum_judge_result_2026-04-11.json    # Judge evaluation
    bare-minimum_output_2026-04-11.txt           # Raw workflow output (benchmark)
```

### Judge Result Format

```json
{
  "workflowName": "bare-minimum",
  "judgeModel": "openai/gpt-4o",
  "timestamp": "2026-04-11T14:36:11.859Z",
  "runDate": "2026-04-11",
  "successful": true,
  "scores": {
    "overall": 75,
    "orchestration": 80,
    "taskDecomposition": 70,
    "toolIntegration": 50,
    "errorHandling": 65,
    "observability": 85,
    "architecturePattern": 80
  },
  "verdict": "Framework demonstrates clean sequential orchestration...",
  "strengths": ["..."],
  "weaknesses": ["..."],
  "frameworkIssues": ["FRAMEWORK: ..."],
  "exampleIssues": ["EXAMPLE: ..."],
  "improvements": ["FRAMEWORK|EXAMPLE: ..."]
}
```

## Comparing Runs Over Time

After multiple runs on different dates, each example directory will contain:

```
hello-world-single-agent/judge-results/
    bare-minimum_judge_result_2026-04-11.json   # v1.0.0-SNAPSHOT
    bare-minimum_judge_result_2026-05-15.json   # v1.1.0-SNAPSHOT
    bare-minimum_output_2026-04-11.txt
    bare-minimum_output_2026-05-15.txt
```

To compare scores across runs:

```bash
# Quick score comparison across all examples and dates
find . -name "*_judge_result_*.json" -exec python3 -c "
import json, sys
d = json.load(open(sys.argv[1]))
print(f\"{d['runDate']} | {d['workflowName']:30s} | {d['scores']['overall']:3d}/100\")
" {} \; | sort
```

## Interpreting Results

### Score Ranges

| Range | Meaning |
|-------|---------|
| 85-100 | Framework excels at this pattern |
| 70-84 | Solid, minor improvements possible |
| 50-69 | Functional but significant gaps |
| 30-49 | Major framework issues exposed |
| 0-29 | Fundamentally broken |

### Common Patterns

- **SEQUENTIAL workflows** typically score 70-85 (framework handles these well)
- **SWARM_GRAPH workflows** score 80-85 (advanced routing is mature)
- **PARALLEL workflows** may score lower if partial-result recovery is needed
- **SELF_IMPROVING workflows** are the most demanding on the framework

### Tracking Regressions

A score drop of 10+ points between runs on the same workflow indicates a regression. Check:
1. Was the framework changed? (compare `frameworkIssues` across dates)
2. Was the example changed? (compare `exampleIssues`)
3. Was the LLM model changed? (scores should be model-agnostic, but extreme model changes may affect tool usage patterns)

## Workflow Coverage

The `judge-all` command runs 27 workflows covering:

- **Basic** (5): bare-minimum, tool-calling, agent-handoff, context-variables, multi-turn
- **Features** (8): streaming, customer-support, error-handling, memory, evaluator-optimizer, agent-testing, agent-debate, multi-language
- **Visualization** (1): visualization
- **Production** (5): rag-research, codebase-analysis, data-pipeline, stock-analysis, competitive-analysis
- **Advanced** (4): due-diligence, audited-research, governed-pipeline, secure-ops
- **Swarm** (4): self-improving, pentest-swarm, competitive-swarm, investment-swarm

### Excluded from judge-all

- `human-loop` (requires interactive input)
- `customer-support-app`, `rag-app` (persistent REST services)
- `scheduled` (cron-based)
- `multi-provider` (requires multiple LLM providers)
- `deep-rl` (DeepRLPolicy removed from framework)
- `yaml-dsl` (configuration-only, no direct execution)
- `mcp-research` (requires MCP server)

## Filing Issues from Results

Judge results include two issue categories:

- `frameworkIssues`: Bugs/limitations in `swarm-ai` core -> file at https://github.com/intelliswarm-ai/swarm-ai/issues
- `exampleIssues`: Problems in this specific example -> file at https://github.com/intelliswarm-ai/swarm-ai-examples/issues

Each `improvements` entry is prefixed with `FRAMEWORK:` or `EXAMPLE:` to indicate ownership.
