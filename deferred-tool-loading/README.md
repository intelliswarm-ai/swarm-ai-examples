# deferred-tool-loading — true multi-turn orchestration (swarmai 1.0.19+)

Showcases the **complete** deferred-loading story end-to-end with a real LLM:
the harness orchestrates a multi-turn conversation where the agent starts
with only `tool_search` + `tool_load`, requests specific tools as it
discovers them, and receives those tools' callbacks on the next turn.

## What this proves

| Stage | Behaviour |
|---|---|
| Turn 1 | Agent has `[tool_search, tool_load]`. Searches catalog, calls `tool_load(name)` to request specific tools. The tools themselves are NOT callable yet. |
| Harness between turns | `loader.applyPendingLoads()` materialises every queued callback. Returns the count of newly-loaded tools — `0` signals the orchestration loop to exit. |
| Turn 2+ | Agent now has `[tool_search, tool_load, calculator, current_time, ...]`. Calls the requested tools normally and produces the final answer. |

## Architecture

```
ToolCatalog (8 tools)            ToolLoader
    │                                │
    ├─ name → CatalogEntry            ├─ name → ToolCallback supplier (registered)
    │                                ├─ pending: Set<String>          (queued by tool_load)
    │                                └─ loaded:  Map<String,Callback> (materialised callbacks)
    │
    └─▶ ToolSearchService                 ToolLoadTool ─────▶ loader.requestLoad(name)
            │                                                       │
            └─▶ ToolSearchTool.callback   ◀── tool_load(name)        ↓
                       │                                       LoadRequestStatus
                       └── tool_search(query) returns matches      │
                                                              between turns:
                                                              loader.applyPendingLoads()
```

| Type | Role |
|---|---|
| `ToolCatalog`, `CatalogEntry` | Registry of metadata (no callbacks attached) |
| `ToolSearchService`, `ToolSearchTool` | Discovery — finds tool names by query |
| `ToolLoader` | Tracks loaded vs pending; materialises callbacks |
| `ToolLoadTool` | Agent-callable bridge to `loader.requestLoad(name)` |
| `LoadRequestStatus` | Enum: `PENDING`, `ALREADY_LOADED`, `NOT_IN_CATALOG`, `NO_CALLBACK_REGISTERED` |

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./deferred-tool-loading/run.sh
./deferred-tool-loading/run.sh "Convert 100 USD to EUR and tell me the time in Tokyo"
```

The default question forces both `calculator` and `current_time` to be discovered, loaded, and called.

## Output shape

```
======================================================================
  Deferred Tool Loading — true multi-turn orchestration
======================================================================

  The catalog has 8 tools. The agent starts with ONLY
  tool_search + tool_load loaded. It must:
    1. Search the catalog (tool_search)
    2. Request loads (tool_load)
    3. End the turn — harness materialises the loaded callbacks
    4. Next turn: the agent calls the loaded tools normally

--- turn 1 (tools available: [tool_search, tool_load]) ---
agent reply: I've searched and requested loads for calculator and current_time. They will be available on my next turn.
    loader.applyPendingLoads() = 2 (loaded so far: [calculator, current_time])

--- turn 2 (tools available: [tool_search, tool_load, calculator, current_time]) ---
agent reply: The square root of 144 plus 12 is 24.0, and the current time in Europe/Paris is 2026-05-08T20:42:11+02:00.
    loader.applyPendingLoads() = 0 (loaded so far: [calculator, current_time])
    no new loads pending → exit loop

======================================================================
  Quality check
======================================================================
  turns completed:        2
  total loads applied:    2
  loaded names final:     [calculator, current_time]
  calculator invocations: 1
  current_time invocations: 1

  Checks:
    [PASS] orchestration ran multiple turns
    [PASS] >= 2 tool loads applied through the loop
    [PASS] both calculator and current_time were invoked
    [PASS] final reply references both a number and a time-like value

  QUALITY CHECK PASSED
  -> The agent discovered, requested, and CALLED tools across multiple turns.
  -> True deferred loading: only tool_search + tool_load existed at turn 1;
  -> the actual tools were materialised and used in subsequent turns.
```

## How the orchestration loop works

```java
List<ToolCallback> currentTools = new ArrayList<>(List.of(searchCb, loadCb));

for (int turn = 1; turn <= MAX_TURNS; turn++) {
    String reply = chatClient.prompt()
        .messages(history)
        .toolCallbacks(currentTools.toArray(new ToolCallback[0]))
        .call().content();
    history.add(new AssistantMessage(reply));

    int newlyLoaded = loader.applyPendingLoads();
    if (newlyLoaded == 0) break;          // <-- exit condition: no new requests

    currentTools.clear();
    currentTools.add(searchCb);            // bootstrap tools always carry forward
    currentTools.add(loadCb);
    currentTools.addAll(loader.loadedCallbacks());

    history.add(new UserMessage(
        "<system-reminder>\nLoaded since last turn: " + loader.loadedNames()
        + "\n</system-reminder>\n\nContinue with your plan."));
}
```

The exit condition is the load-bearing piece: when `applyPendingLoads()` returns 0, the agent didn't request any new tools — either because it's done or because it can finish with what it already has.

## Why this is "true" deferred loading

| Aspect | Basic ToolSearch | Deferred Tool Loading (this) |
|---|---|---|
| Catalogue size in prompt | 1 tool schema (tool_search) | 2 tool schemas (tool_search + tool_load) |
| Can the agent CALL discovered tools? | No — only describe them | **Yes — after explicit load** |
| Conversation pattern | Single-turn discovery | Multi-turn discovery → load → call |
| Fits production? | For tool catalogs the user enables manually | For runtime-driven tool selection at scale |

## Composability

`ToolLoader` is independent of any LLM — it's a plain registry with `requestLoad` and `applyPendingLoads` operations. Wire it into any orchestration pattern:

- **Auto-loaders**: a producer that watches `loader.pendingNames()` and triggers external installs (npm, plugins, MCP server hot-attach)
- **Budget caps**: cap `loader.loadedCount()` to N; when the agent exceeds it, `unload(name)` rotates the least-recently-used
- **Persistence**: serialize `loader.loadedNames()` between sessions and re-load on resume

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
