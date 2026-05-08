# background-tasks — async LLM dispatch (swarmai 1.0.19+)

Showcases the background-task primitive (background-task harness primitive) end-to-end with
a real LLM. The foreground agent has four agent-callable tools:

| Tool | What it does |
|---|---|
| `background_spawn(prompt)` | Start a task asynchronously; return its id immediately |
| `background_list()` | List every task with its status and age |
| `background_output(id)` | Fetch status + output (or "still running") |
| `background_stop(id)` | Cancel a task mid-flight |

The agent dispatches a slow research prompt to the background, does other work
in the meantime (e.g. composes a haiku), then polls for the result and combines
both into its final answer — the same pattern modern AI agent harnesses use for
async sub-agents.

## What this proves

| | |
|---|---|
| The agent can dispatch slow work asynchronously | `background_spawn` returns immediately with an id; the LLM call doesn't block |
| The harness runs the task in its own thread | `LlmBackgroundTaskRunner` uses an `ExecutorService` for parallelism |
| The agent can poll status / fetch output / cancel | All three operations are agent-callable tool calls |
| Recursion is structurally prevented | Background agents have NO `background_*` tools — can't spawn sub-tasks |

## Architecture

```
Foreground agent       tool set: [background_spawn, background_list,
                                   background_output, background_stop]
                                       │
                                       ↓
                          ┌──────────────────────┐
                          │ BackgroundTaskRegistry│  ←── tracks every spawned task
                          └──────────────────────┘
                                       │
                                       ↓
                          ┌──────────────────────┐
                          │ LlmBackgroundTaskRunner│  ←── runs each task in its own LLM call
                          └──────────────────────┘
                                       │
                                       ↓
                              Background agent
                              (no background_* tools)
```

| Type | Role |
|---|---|
| `BackgroundTask` | Per-task state: id, prompt, status, output/error, completedAt |
| `BackgroundTaskStatus` | Enum: PENDING / RUNNING / COMPLETED / FAILED / CANCELLED |
| `BackgroundTaskRegistry` | Tracks tasks; exposes lookup, active/completed lists, completion listener |
| `BackgroundTaskRunner` (SPI) | Decides how a task actually runs |
| `LlmBackgroundTaskRunner` | Default: runs the task as an LLM call against a `ChatClient` |
| `BackgroundTaskTools` | Bridge: builds the four agent-callable Spring AI `ToolCallback`s |

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./background-tasks/run.sh
./background-tasks/run.sh "Spawn a background research task on X. Meanwhile, tell me Y. Then combine both."
```

The default question forces the dispatch → other-work → poll → combine pattern.

## Output shape

```
======================================================================
  Background Tasks — async LLM dispatch
======================================================================

  User goal:
    Spawn a background task to write a 4-sentence summary of how mitochondria
    produce ATP. While it's running, tell me a quick haiku about coffee. ...

    [completion event] task bg_a1b2c3d4 -> COMPLETED (took 4s)

======================================================================
  Foreground agent's final reply
======================================================================
Here is your haiku:
   Steaming dark elixir,
   Morning's quiet companion,
   Hope in every sip.

I dispatched the mitochondria summary as a background task and retrieved its result:
"Mitochondria produce ATP through oxidative phosphorylation in their inner membrane.
The electron transport chain pumps protons into the intermembrane space, creating a
gradient. ATP synthase uses this gradient to phosphorylate ADP into ATP. This process
generates the bulk of cellular energy used by aerobic organisms."

======================================================================
  Background tasks spawned during this run
======================================================================
  bg_a1b2c3d4  status=COMPLETED  age=4s
    prompt: write a 4-sentence summary of how mitochondria produce ATP
    output: Mitochondria produce ATP through oxidative phosphorylation...

======================================================================
  Quality check
======================================================================
  tasks spawned:    1
  completed:        1
  failed:           0
  substantial out:  1 (>=50 chars)

  Checks:
    [PASS] >= 1 background task was spawned
    [PASS] >= 1 task reached COMPLETED status
    [PASS] background output is substantial
    [PASS] foreground reply references the background work or its topic

  QUALITY CHECK PASSED
```

## Composing with other primitives

`BackgroundTaskRegistry` exposes a completion listener — wire it into other
harness components:

- **SystemReminderChannel** — listener posts `<system-reminder>` blocks when
  a background task completes, so the agent sees them on its next turn
- **TypedMessageHistory** — listener appends a `TypedToolResultMessage` for
  each background completion to keep the conversation log faithful
- **Subagent** — `BackgroundTaskRunner` is the SPI; swap in `InProcessSubagent`-backed
  runners that have their own isolated workspaces (filesystem, env vars)

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
