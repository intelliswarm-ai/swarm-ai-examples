# typed-message-history — sealed-record typed conversation log (swarmai 1.0.19+)

Showcases `TypedMessageHistory` end-to-end with a real LLM. Two tool callbacks
record their calls and results into a typed history as **structured records**
(`ToolCall(id, name, argsJson)`, `TypedToolResultMessage.isError()`) — not
free-text strings the harness has to parse later.

## What this proves

| | |
|---|---|
| Type-safe access | Sealed interface + 5 record variants. Exhaustive `switch` expressions, no `instanceof` chains. |
| Structured tool I/O | Tool calls have explicit id/name/args fields; results have an `isError` flag. Observability counts failures without string-matching `"Error:"` prefixes. |
| Per-message metadata | Free-form `Map<String, Object>` per message for harness state (trace ids, routing tags, span links). |
| Pretty rendering | `renderTranscript()` flattens to a debug-friendly format with role tags + nested tool calls. |
| Spring AI round-trip | `toSpringMessages()` for replay; `fromSpringMessages(...)` for ingestion. |

## Architecture

```
TypedMessage (sealed interface)
  ├── TypedSystemMessage     (record)
  ├── TypedUserMessage       (record)
  ├── TypedAssistantMessage  (record)  — has List<ToolCall>
  ├── TypedToolCallMessage   (record)  — wraps one ToolCall
  └── TypedToolResultMessage (record)  — has isError flag

TypedMessageHistory
  ├── add(TypedMessage)                   ← tool callbacks call this
  ├── all() / filterType(Class) / filter(Predicate)
  ├── userMessages() / assistantMessages() / toolCallMessages() / ...
  ├── firstUserMessage() / lastAssistantMessage() / lastMessage()
  ├── toolCallCount() / toolResultCount() / toolErrorCount()
  ├── renderTranscript()                  ← human-readable
  ├── toSpringMessages()                  ← for chatClient.prompt().messages(...)
  └── static fromSpringMessages(List<Message>)
```

| Type | Role |
|---|---|
| `TypedMessage` | Sealed interface — exhaustive switch over the 5 variants |
| `ToolCall` | Record: id + name + argsJson |
| `TypedAssistantMessage` | Holds inline `List<ToolCall>` for prompt-replay fidelity |
| `TypedToolCallMessage` | Standalone log entry per call — for query-friendly history |
| `TypedToolResultMessage` | Carries `toolCallId` (correlation) and `isError` (typed status) |

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./typed-message-history/run.sh
./typed-message-history/run.sh "Compute 25% of 600 and tell me what time it is in Tokyo."
```

The default question forces both tools to be exercised: `What's 7 * 23, and what time is it in UTC right now?`

## Output shape

```
======================================================================
  TypedMessageHistory — typed wrappers around the conversation
======================================================================

  User goal:
    What's 7 * 23, and what time is it in UTC right now?

======================================================================
  Rendered transcript (TypedMessageHistory.renderTranscript)
======================================================================
[system]    You are a helpful assistant with two tools available: ...
[user]      What's 7 * 23, and what time is it in UTC right now?
[tool_call] calculator #call_calc_8a1f4c2e args={"expression":"7*23"}
[tool_ok]   #call_calc_8a1f4c2e  161.0
[tool_call] current_time #call_time_3b9e1d20 args={"zone":"UTC"}
[tool_ok]   #call_time_3b9e1d20  2026-05-08T19:42:11Z
[assistant] 7 * 23 = 161. The current time in UTC is 2026-05-08T19:42:11Z.

======================================================================
  Typed-history queries
======================================================================
  size():                  7
  systemMessages():        1
  userMessages():          1
  assistantMessages():     1
  toolCallMessages():      2
  toolResultMessages():    2
  toolCallCount() (sum):   2
  toolResultCount():       2
  toolErrorCount():        0
  firstUserMessage():      What's 7 * 23, and what time is it in UTC right now?
  lastAssistantMessage():  7 * 23 = 161. The current time in UTC is...
  filter(name=calculator): 1
  filter(name=current_time): 1

======================================================================
  Quality check
======================================================================
  Checks:
    [PASS] core roles present
    [PASS] both tools exercised
    [PASS] tool calls + results captured
    [PASS] recorded counts match callback invocations
    [PASS] rendered transcript is well-formed

  QUALITY CHECK PASSED
```

## Why this beats `List<Message>`

| Question | Plain `List<Message>` | `TypedMessageHistory` |
|---|---|---|
| "How many tool errors?" | Walk list, instanceof, parse content for "Error:" | `history.toolErrorCount()` |
| "Show every call to calculator" | instanceof + cast + filter | `history.toolCallMessages().stream().filter(c -> "calculator".equals(c.toolCall().name()))` |
| "What's the latest assistant message?" | reverse-loop with instanceof | `history.lastAssistantMessage().orElseThrow()` |
| "Is this an exhaustive type analysis?" | `if/else if/else` chain — compile silently if a new type is added | `switch` is exhaustive — compile error if the sealed family grows |

The big one is the last: when SwarmAI eventually adds a sixth message variant, every consumer using `switch` on `TypedMessage` gets a compile error pointing them to handle it. With `instanceof` chains the bug surfaces at runtime as silently-skipped messages.

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
