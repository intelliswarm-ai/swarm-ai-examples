## What you're looking at

Same prompt — institutional-quality AAPL investment analysis. **Left: SwarmAI** (7 agents, Finnhub + SEC XBRL tools, cited output). **Right: raw LLM** (one call, no tools, no memory).

## On GPT-4o

| SwarmAI | Baseline |
|---|---|
| Pulls real FY-2025 filings: mcap **$3.97T**, revenue **$416B**, P/E **33.68**, current ratio **0.89** | Refuses to invent numbers |
| Every figure cites Finnhub or the 10-K | Output is a template: `[Insert Current Price]`, `[Insert P/E Ratio]` |
| **HOLD (HIGH)** — justified by P/E 33.68 and current ratio 0.89 | Honest. Unusable at an investment committee. |

**Gain:** an LLM that would otherwise stonewall becomes an analyst-grade tool with auditable output.

## On GPT-5.4 mini

| SwarmAI | Baseline |
|---|---|
| Same pipeline, same filings, same data | Zero tools called, zero filings consulted |
| **BUY (HIGH)** — 2.3× longer report, grounded in $416B revenue cited to the 10-K | Commits to a specific **$235 12-month price target** out of thin air |
| Defensible in a meeting | Textbook confident hallucination |

**Gain:** proof that citation discipline isn't optional — without it, the model fabricates plausible-looking specifics.

## Why the two models disagree (HOLD vs BUY)

Identical numbers, different conclusion. Frontier models read facts differently — the framework's job is making *both* auditable, not picking the winner.

## Metrics

| | Swarm 4o | Baseline 4o | Swarm 5.4-mini | Baseline 5.4-mini |
|---|---:|---:|---:|---:|
| Wall time | 46.6 s | 6.8 s | 45.5 s | 8.4 s |
| Tokens | 143,732 | 762 | 162,468 | 1,055 |
| Output | 3.3k chars, cited | placeholders | 7.8k chars, cited | $235, no source |

Switch the model chips above to replay either side.
