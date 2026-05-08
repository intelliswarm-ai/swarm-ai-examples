# tool-search — deferred tool loading via `tool_search` (swarmai 1.0.19+)

Showcases the highest-impact harness primitive for production scaling: instead of
preloading every tool's schema into the system prompt, the harness exposes a
single meta-tool `tool_search`. The agent discovers further tools by query.

## What this proves

| | |
|---|---|
| **Context cost stays flat** as the catalog grows | The system prompt only ever carries `tool_search`'s schema, regardless of whether the catalog has 5, 50, or 500 tools |
| **Discovery quality** is good with simple keyword scoring | The MVP uses weighted substring matching against name (×3) / keywords (×2) / description (×1) plus an all-terms-hit bonus — no embeddings, no external services |
| **The agent uses search results faithfully** | When asked about CSV analysis, it picks `csv_analysis`; when asked about dates, it picks `date_diff`; when asked about math, it picks `calculator` |

## Architecture

```
ToolCatalog          (15 tools registered with description, keywords, schemaSummary)
   │
   └─▶ ToolSearchService.search("CSV data")
          │
          └─▶ ranked List<ToolMatch>          (subset of catalog by relevance)
                 │
                 └─▶ ToolSearchTool.callback(service)
                        │
                        └─▶ Spring AI ToolCallback ('tool_search')   ← the only tool the LLM sees
```

| Type | Role |
|---|---|
| `CatalogEntry` | Record: name, description, keywords[], schemaSummary |
| `ToolCatalog` | Insertion-ordered registry, name lookup |
| `ToolMatch` | Search result: entry + score |
| `ToolSearchService` | Keyword-relevance search; deterministic, no I/O |
| `ToolSearchTool` | Bridge: builds a Spring AI `ToolCallback` named `tool_search` |

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./tool-search/run.sh
./tool-search/run.sh "Help me parse an XML response from an API."  # custom one-off question
```

The default mode runs three questions across three categories (CSV, dates, math)
and validates that the catalog returns category-appropriate results for each.

## Output shape

```
======================================================================
  ToolSearch — deferred tool loading
======================================================================

  The harness has catalogued 15 tools but loaded only ONE
  into the agent's tool set: tool_search. The other 14
  tools' schemas never enter the system prompt — they're discoverable
  but not loaded.

--- question #1 (csv-analysis) ---
user> I have a CSV file at /data/sales.csv. What kind of analysis could I run on it?
    direct search returned 3 match(es): [csv_analysis(7.0), file_read(3.0), directory_list(3.0)]
agent> I would use the csv_analysis tool to profile your CSV file. It takes args: path (string), and would give you...

--- question #2 (date-arithmetic) ---
user> What's the elapsed time between 2025-01-01 and today?
    direct search returned 2 match(es): [date_diff(6.0), current_time(2.0)]
agent> I would use date_diff with from="2025-01-01" and to=today's date...

--- question #3 (math) ---
user> I need to compute compound interest on $10000 at 5% over 3 years.
    direct search returned 1 match(es): [calculator(4.0)]
agent> The calculator tool would handle this; it takes args: expression (string)...

======================================================================
  Quality check
======================================================================
  catalog size:           15
  questions sent to LLM:  3
  prompt calls completed: 3

  Checks:
    [PASS] every question reached the LLM (3/3)
    [PASS] direct search returned a category-relevant match for each question (3/3)
    [PASS] the LLM's reply referenced an expected tool for at least 2/3 questions (3/3)

  QUALITY CHECK PASSED
```

## Why "MVP"

The example demonstrates **discovery without preload** — the load-bearing half
of the deferred-loading story. The other half — the agent telling the harness
"please load tool X for the next turn" — requires a multi-turn conversation
loop on top of Spring AI's auto-tool-execution path. That's a follow-up.

For the demo: the agent reads the catalog through `tool_search`, plans which
tool it would use, and reports its plan. In production, the harness would
either:

- **Multi-turn loop**: read the agent's plan, attach the matched callback, re-prompt
- **Two-step tool**: add a `tool_load(name)` agent-callable that registers the tool for the next turn

Both are straightforward layers on top of this primitive.

## Composing with other primitives

`ToolSearchService` is a pure-Java service — no LLM dependency, no I/O. Wire it
into other harness components:

- **Slash commands**: `/tools <query>` skill that calls `service.search(query)`
- **System reminders**: when the agent looks up the same tool 3 times, post a
  reminder suggesting the harness load it
- **TaskList**: a sub-task whose description starts with "find a tool for X"
  triggers a `tool_search` automatically

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
