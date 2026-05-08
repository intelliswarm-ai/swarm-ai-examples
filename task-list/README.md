# task-list — agent-driven todo list (swarmai 1.0.19+)

Showcases the `TaskList` primitive end-to-end with a real LLM. The agent receives
two tool callbacks — `task_create` and `task_update` — bound to a per-run
`TaskList` on top of an `InMemoryPlanChannelAccessor`. As the LLM decomposes a
multi-step problem and walks through it, the channel listener renders the live
todo list after every transition.

## What this proves

The `TaskList` isn't just a Java helper. It's a structured surface an LLM can
drive directly through tool calls — the same agent self-organisation pattern
exposed by todo-write tools in modern AI agent harnesses. Same channel, same
status lifecycle, same renderer.

| What | Where |
|---|---|
| Task model | `swarmai-core` → `state.plan.TaskItem` |
| Operator API | `swarmai-core` → `state.plan.TaskList` |
| Markdown renderer | `swarmai-core` → `state.plan.TaskListRenderer` |
| Channel | `swarmai-core` → `state.plan.InMemoryPlanChannelAccessor` |
| Tool callbacks | this example — bound via `FunctionToolCallback.builder` |

Zero new infrastructure: `TaskList` lives on the existing `EvolvingPlan` channel
as a different payload type, so the same listener / persistence / dashboard
machinery applies.

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./task-list/run.sh                              # uses the default Tokyo prompt
./task-list/run.sh "Plan a release for v2.0"    # custom prompt
```

The example uses `ChatClient` directly (not `Agent`), so any provider configured
via `application-*.yml` works — `openai-mini`, `openai`, `ollama`, etc.

## Output shape

```
======================================================================
  TaskList primitive — agent-driven todo list
======================================================================

  User goal:
    Plan a 3-day trip to Tokyo for a first-time visitor. ...

  Live channel transitions (n   status      description):
   1 [+]          Research flights and accommodations
   2 [+]          Plan day-by-day itinerary
   3 [+]          Prepare packing list
   ...
   8 [APPROVED]   Research flights and accommodations
   9 [EXECUTED]   Research flights and accommodations
  ...

======================================================================
  Final TaskList (rendered)
======================================================================
- [x] Research flights and accommodations
- [x] Plan day-by-day itinerary
...

======================================================================
  Quality check
======================================================================
  task_create calls: 5
  task_update calls: 10
  channel transitions: 15
  total tasks:       5
  pending / in-progress / done / cancelled: 0 / 0 / 5 / 0

  Checks:
    [PASS] at least 3 tasks created   (got 5)
    [PASS] at least 1 task completed   (got 5)
    [PASS] no orphaned in-progress tasks (got 0)

  QUALITY CHECK PASSED
```

## How it works

1. **Channel + TaskList per run.** `InMemoryPlanChannelAccessor` is the
   underlying append-only state channel; `TaskList(channel)` is the operator
   facade that translates `createTask` / `startTask` / `completeTask` / etc.
   into channel append + status-transition writes.

2. **Two tool callbacks bound to the TaskList.** `FunctionToolCallback.builder`
   wires a Java function and an input record into a Spring AI `ToolCallback` that
   the LLM can call by name. The callbacks close over the `TaskList` instance,
   so every LLM call mutates exactly the state being rendered.

3. **System prompt enforces the create-then-walk pattern.** The prompt directs
   the LLM to: (a) call `task_create` for each step up front, then (b) for each
   task in order call `task_update(start)` → produce content → `task_update(complete)`.
   This makes the live transitions readable and gives the renderer real data to show.

4. **Listener prints transitions in real time.** A `PlanListener` on the channel
   prints a one-liner per status change; the same listener mechanism is used by
   `BanditApprovalTrainer`, `PlanAttachingSubagentSpawner`, etc.

5. **Quality check at the end.** Verifies the LLM actually drove the lifecycle
   end-to-end (≥ 3 tasks, ≥ 1 completed, no orphaned in-progress).

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
