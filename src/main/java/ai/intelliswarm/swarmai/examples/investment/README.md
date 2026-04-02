# Investment Analysis Swarm

Multi-company investment analysis with parallel self-improving agents, real-time CODE skill generation, and cross-agent skill sharing via a shared SkillRegistry.

## Architecture

```
                        +---------------------+
                        |  DISCOVERY PHASE    |
                        |  [Financial Analyst]|
                        |  Verifies tickers   |
                        |  via Yahoo Finance, |
                        |  outputs TICKER:    |
                        |  lines              |
                        +----------+----------+
                                   |
                     SwarmCoordinator parses
                     TICKER: lines, fans out
                                   |
         +----------+--------------+--------------+----------+
         |          |              |              |          |
         v          v              v              v          v
   +----------+ +----------+ +----------+ +----------+ +----------+
   | AAPL     | | MSFT     | | GOOGL    | | NVDA     | | TSLA     |
   | Agent    | | Agent    | | Agent    | | Agent    | | Agent    |
   |          | |          | |          | |          | |          |
   | browse   | | browse   | | browse   | | browse   | | browse   |
   | calc     | | calc     | | calc     | | calc     | | calc     |
   | sec_file | | sec_file | | sec_file | | sec_file | | sec_file |
   |          | |          | |          | |          | |          |
   | Generates| | Reuses   | | Reuses   | | Reuses   | | Reuses   |
   | P/E calc | | P/E calc | | P/E calc | | + adds   | | all      |
   | skill    | | skill    | | skill    | | margin   | | skills   |
   +----+-----+ +----+-----+ +----+-----+ +----+-----+ +----+-----+
        |             |             |             |             |
        +------+------+------+------+------+------+------+-----+
               |                    |                    |
               v                    v                    v
   +---------------------+  +------------------------+
   | SHARED SKILL        |  | COMMAND LEDGER          |
   | REGISTRY            |  | Deduplicates API calls  |
   | P/E calculator      |  | across parallel agents  |
   | Revenue parser      |  +------------------------+
   | Margin analyzer     |
   +---------------------+
                                   |
              +--------------------+--------------------+
              |                                         |
              v                                         v
   +---------------------+              +---------------------------+
   | REVIEWER             |    drives   | SYNTHESIS                 |
   | [Investment Research |  ---------> | [Chief Investment Officer]|
   |  Director]           |   deeper    | Investment memo with      |
   | Pushes for CONFIRMED |   data      | Buy/Hold/Sell per company |
   | data over ESTIMATE   |  fetching   | Confidence levels         |
   +---------------------+              +---------------------------+
                                                   |
                                                   v
                                    output/investment_memo.md
```

## Parallel Agent Coordination

The SwarmCoordinator manages the full investment analysis lifecycle:

1. **Discovery** -- A financial analyst identifies tickers from the query, verifying each by browsing its Yahoo Finance page
2. **Fan-Out** -- The coordinator parses `TICKER:` prefixed lines and clones one analyst agent per company (up to 5 in parallel)
3. **Per-Company Analysis** -- Each agent follows a structured protocol: fetch overview from Yahoo Finance, pull SEC 10-K filings, compute financial metrics (P/E, revenue growth, margins, D/E ratio), assess competitive position, and produce a Buy/Hold/Sell recommendation
4. **Skill Generation and Sharing** -- The first agent to compute a P/E ratio generates a reusable CODE skill; subsequent agents reuse it instantly via the shared `SkillRegistry`
5. **Data Confidence Tagging** -- All data points are tagged `[CONFIRMED]` (from API) or `[ESTIMATE]` (from LLM knowledge)
6. **Reviewer Loop** -- An Investment Research Director reviews output and issues `NEXT_COMMANDS` with specific Yahoo Finance / SEC EDGAR URLs to replace `[ESTIMATE]` tags with `[CONFIRMED]` data
7. **Synthesis** -- A Chief Investment Officer produces the final investment memo with comparative metrics table and portfolio recommendations

## Prerequisites

- Java 21+
- Running Ollama instance (or OpenAI-compatible API)
- Model configured via `OLLAMA_MODEL` (default: `mistral:latest`)

## Run

```bash
./scripts/run.sh investment-swarm "Compare AAPL, MSFT, GOOGL, NVDA, TSLA for investment"
```

## How It Works

The Investment Analysis Swarm uses `ProcessType.SWARM` to coordinate parallel financial analysis across multiple companies. Execution begins with a discovery phase where a financial analyst agent identifies and verifies stock tickers from the user's query by browsing Yahoo Finance pages. The SwarmCoordinator parses `TICKER:` lines from the discovery output and spawns one cloned agent per company, running up to five in parallel. Each agent follows a five-step analysis protocol: company overview via Yahoo Finance, SEC 10-K filing retrieval, financial metric computation using the calculator tool, competitive positioning research, and investment thesis formulation. A key differentiator is runtime CODE skill generation -- when the first agent computes a P/E ratio, it generates a reusable skill that is immediately registered in the shared `SkillRegistry` and consumed by all other parallel agents. A command ledger prevents redundant API calls across agents. The Investment Research Director acts as a reviewer, pushing agents to replace `[ESTIMATE]` tags with `[CONFIRMED]` data by issuing `NEXT_COMMANDS` containing specific API URLs. The final investment memo, produced by a Chief Investment Officer agent, includes per-company financials tables, a comparative metrics matrix, risk analysis, and Buy/Hold/Sell recommendations with confidence levels.

## Key Code

```java
// Swarm with skill sharing, reviewer-driven deepening, and command ledger
Swarm swarm = Swarm.builder()
    .id("investment-analysis-swarm")
    .agent(financialAnalyst)
    .agent(memoWriter)
    .managerAgent(reviewer)
    .task(discoveryTask)
    .task(analysisTask)
    .task(reportTask)
    .process(ProcessType.SWARM)
    .config("maxIterations", 5)
    .config("maxParallelAgents", 5)
    .config("targetPrefix", "TICKER:")
    .config("qualityCriteria",
        "1. Each company has revenue, net income, and P/E ratio " +
        "   with [CONFIRMED] or [ESTIMATE] tags\n" +
        "2. Financial metrics are calculated using real data\n" +
        "3. Buy/Hold/Sell recommendations include confidence levels\n" +
        "4. Comparative metrics table includes ALL companies")
    .verbose(true)
    .build();
```

## Output

- `output/investment_memo.md` -- professional investment memo with per-company analysis, comparative table, risk assessment, and portfolio recommendations
- `output/analysis_TICKER.md` -- individual company analysis files
- Console summary: duration, tasks completed, skills generated, skills reused, SkillRegistry stats, token usage

## Customization

- Change the default ticker list by modifying the query argument
- Add additional data sources by including more tools (e.g., `csv_analysis` for historical data)
- Adjust `maxIterations` to control how many reviewer-driven deepening cycles occur
- Modify `qualityCriteria` to enforce sector-specific analysis requirements
- Tune agent temperature (currently 0.2 for analysts, 0.3 for memo writer) for creativity vs. precision
- Add a `BudgetPolicy` to cap token spend for cost-sensitive environments
