# Audited Research Pipeline

Multi-turn research workflow with full audit logging, PII sanitization, and observability tracing.

## Architecture

```
 +--------------------------------------------------------------+
 |  STAGE 1: RESEARCH (multi-turn, 5 turns max)                 |
 |                                                               |
 |   [Senior Research Analyst]                                   |
 |     |-- web_search  (audit + sanitize + rate-limit hooks)     |
 |     |-- http_request                                          |
 |     |-- web_scrape                                            |
 |     |-- calculator                                            |
 |     |                                                         |
 |     Turn 1: broad search --> <CONTINUE>                       |
 |     Turn 2: drill into findings --> <CONTINUE>                |
 |     Turn 3-5: cross-reference & refine --> <DONE>             |
 |                                                               |
 +-------------------------------+------------------------------+
                                 |
                                 v
 +--------------------------------------------------------------+
 |  STAGE 2: REPORT WRITING                                     |
 |                                                               |
 |   [Research Report Writer]                                    |
 |     |-- file_write  (audit hook)                              |
 |     |                                                         |
 |     Synthesizes findings into structured report               |
 |                                                               |
 +-------------------------------+------------------------------+
                                 |
                                 v
 +--------------------------------------------------------------+
 |  OBSERVABILITY LAYER                                         |
 |   Decision Trace  |  Event Recording  |  Metrics JSON        |
 +--------------------------------------------------------------+
```

## Features Combined

This example demonstrates 8 framework features working together:

| Feature | How It's Used |
|---|---|
| Multi-Turn Agents | Researcher runs up to 5 turns, signaling `<CONTINUE>` / `<DONE>` to control depth |
| Tool Hooks (Audit) | Every tool call is logged with timestamp, agent ID, parameters, and result status |
| Tool Hooks (Sanitization) | Email addresses and phone numbers are regex-redacted from tool output |
| Tool Hooks (Rate Limiting) | Warns when more than 10 tool calls occur within a 30-second sliding window |
| Tool Health Checking | `ToolHealthChecker.filterOperational()` removes unhealthy tools before execution |
| Tiered Permissions | Researcher is `READ_ONLY`; Writer is `WORKSPACE_WRITE` |
| Compaction | Researcher compacts context after turn 3 to stay within 4000-token window |
| Decision Tracing | `DecisionTracer` captures a full decision tree with per-agent explanations |
| Event Replay | `EventStore` creates a `WorkflowRecording` saved to JSON for post-mortem replay |
| Budget Tracking | `WorkflowMetricsCollector` tracks token usage and cost per task |

## Prerequisites

- Java 21+
- Running Ollama instance (or OpenAI-compatible API)
- Model configured via `OLLAMA_MODEL` (default: `mistral:latest`)

## Run

```bash
./scripts/run.sh audited-research "AI agent frameworks in enterprise 2026"
```

## How It Works

The pipeline begins with a Senior Research Analyst agent that iteratively builds understanding across up to five tool-calling turns. On each turn the agent issues web searches, fetches pages, and cross-references findings, signaling `<CONTINUE>` until it has comprehensive coverage. Three tool hooks wrap every call: an **audit hook** that logs timestamps and parameters, a **sanitization hook** that redacts PII (emails and phone numbers) from tool output via regex, and a **rate-limit hook** that emits warnings when the call frequency exceeds a sliding-window threshold. Only tools that pass `ToolHealthChecker.filterOperational()` are handed to agents. Once research is complete, a Report Writer agent synthesizes findings into a structured markdown report with citations, executive summary, and recommendations. Throughout execution, a `DecisionTracer` records the reasoning chain, and an `EventStore` captures every event for post-mortem replay. The entire run is wrapped by `WorkflowMetricsCollector` for token and cost accounting.

## Key Code

```java
// Sanitization hook: redacts PII from tool output before it reaches the agent
ToolHook sanitizationHook = new ToolHook() {
    @Override
    public ToolHookResult afterToolUse(ToolHookContext ctx) {
        if (ctx.output() == null) return ToolHookResult.allow();
        String sanitized = ctx.output();
        boolean changed = false;
        if (emailPattern.matcher(sanitized).find()) {
            sanitized = emailPattern.matcher(sanitized).replaceAll("[EMAIL REDACTED]");
            changed = true;
        }
        if (phonePattern.matcher(sanitized).find()) {
            sanitized = phonePattern.matcher(sanitized).replaceAll("[PHONE REDACTED]");
            changed = true;
        }
        if (changed) {
            sanitizedCount.incrementAndGet();
            return ToolHookResult.withModifiedOutput(sanitized);
        }
        return ToolHookResult.allow();
    }
};
```

## Output

- `output/audited_research_report.md` -- structured research report with citations
- `output/audited_research_recording.json` -- full event replay recording
- Console audit summary: total tool calls logged, outputs sanitized, rate-limit warnings
- Decision trace with per-agent workflow explanation

## Customization

- Change the number of research turns by adjusting `.maxTurns(5)` on the researcher agent
- Modify the PII patterns in `buildSanitizationHook()` to redact additional data types
- Adjust the rate-limit threshold (currently 10 calls per 30 seconds) in `buildRateLimitHook()`
- Swap `ProcessType.SEQUENTIAL` for `PARALLEL` if research and writing can overlap
- Set a cost ceiling via `BudgetPolicy` on the metrics collector
