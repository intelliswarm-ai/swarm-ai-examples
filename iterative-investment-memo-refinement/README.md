# Iterative Investment Memo Workflow

Demonstrates the ITERATIVE process type by producing an institutional-quality investment memo through cycles of drafting, review, and refinement until a Managing Director reviewer approves it.

## Architecture

```
+------------------------------------------------------------------+
|                      ITERATION LOOP                              |
|                                                                  |
|  +-----------------+    +----------------+    +----------------+ |
|  | Research        |--->| Memo Writer    |--->| MD Reviewer    | |
|  | Analyst         |    | (CalculatorTool)|   | (no tools --   | |
|  | (Calculator,    |    |                |    |  reviews only) | |
|  |  WebSearch,     |    +----------------+    +-------+--------+ |
|  |  SECFilings)    |                                  |          |
|  +-----------------+                                  |          |
|                                             +---------+---------+|
|                                             |                   ||
|                                        NEEDS_REFINEMENT    APPROVED|
|                                        + specific feedback      ||
|                                             |                   ||
|                                             v                   v |
|                                        loop back              DONE|
+------------------------------------------------------------------+
                                                          |
                                                          v
                                    output/investment_memo_<ticker>.md
```

## What You'll Learn

- Iterative process type with configurable max iterations
- Reviewer-driven quality gates using an explicit 7-point rubric
- Feedback-driven improvement where each draft addresses prior review comments
- Pre-fetched tool evidence (web search + SEC filings + competitor data)
- Quality criteria configuration via Swarm config map
- Per-iteration token tracking to measure cost vs quality tradeoffs

## Prerequisites

- Ollama running locally (or OpenAI/Anthropic API key configured)
- Optional: API keys for richer web search results

## Run

```bash
./run.sh iterative-memo NVDA
./run.sh iterative-memo AAPL 5       # max 5 iterations
./run.sh iterative-memo MSFT 2       # max 2 iterations
```

## How It Works

The workflow begins with a Research Analyst gathering financial data from SEC filings and web sources into a structured brief. A Memo Writer then drafts an institutional-quality investment memo using that brief as its sole data source. A Managing Director reviews the draft against a 7-point rubric (thesis clarity, data grounding, peer comparison, risk analysis, catalyst identification, cross-referencing, completeness) scoring each criterion 1-5. If any criterion scores below 4, the MD provides specific feedback and the Memo Writer revises. This loop repeats until the MD approves or the maximum iteration count is reached.

## Key Code

```java
// Iterative process with MD as reviewer and explicit quality rubric
Swarm memoSwarm = Swarm.builder()
        .id("iterative-memo-" + ticker.toLowerCase())
        .agent(researchAnalyst)
        .agent(memoWriter)
        .agent(managingDirector)
        .task(researchTask)
        .task(memoTask)
        .process(ProcessType.ITERATIVE)
        .managerAgent(managingDirector)       // MD is the reviewer
        .config("maxIterations", maxIterations)
        .config("qualityCriteria", qualityCriteria)
        .build();
```

## Output

- `output/investment_memo_<ticker>.md` -- Final investment memo with:
  - Executive summary (BUY/HOLD/SELL with confidence level)
  - Business overview with moat analysis
  - Financial analysis with data tables and peer comparison
  - Catalyst analysis with dates and probability ratings
  - Risk matrix with likelihood, impact, and mitigation
  - Bull/base/bear case scenarios
  - Data quality disclaimer
- Console logs with per-iteration breakdown (research, memo, review token counts)
- Iteration count and reviewer verdict (APPROVED or MAX ITERATIONS REACHED)

## Customization

- Set max iterations via the second CLI argument (default: 3)
- Modify the `qualityCriteria` string to change the review rubric
- Add additional research tasks (e.g., a competitor analysis pass) before the memo writing stage
- Adjust the Memo Writer's temperature (0.3) for more/less creative prose
- Change the MD's scoring threshold from 4+ to a stricter or more lenient bar

## YAML DSL

This workflow can also be defined declaratively in YAML. See [`workflows/iterative-investment.yaml`](src/main/resources/workflows/iterative-investment.yaml):

```bash
# Load and run via YAML instead of Java
Swarm swarm = swarmLoader.load("workflows/iterative-investment.yaml",
    Map.of("ticker", "AAPL"));
SwarmOutput output = swarm.kickoff(Map.of());
```

The YAML definition includes iterative review loop with manager agent and quality criteria.
