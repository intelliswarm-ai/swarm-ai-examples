# system-reminders — harness-injected runtime context (swarmai 1.0.19+)

Showcases the `SystemReminderChannel` primitive end-to-end with a real LLM. The
harness posts `<system-reminder>` notes between turns based on observed
`TaskList` state, and the prompt-assembly path drains them and prepends them
to the next user message — a common harness pattern for injecting
runtime context the agent should be aware of.

## What this proves

| | |
|---|---|
| Producers (the harness) post freely | Listener-driven from any state change — TaskList, drift budget, replanner activation |
| Consumer drains right before the next LLM call | `SystemReminderPromptDecorator.prepend(userMessage)` does drain + render + prepend in one step |
| Drain-on-read = each reminder fires exactly once | The same nudge doesn't reappear on every subsequent turn |
| `postOnce` dedup survives drain | "First time the count exceeds N" alerts don't repeat across the whole conversation |
| The agent visibly responds | Final-turn response references completion/summary because the harness's "all-done" reminder steered it |

## Composition with TaskList

This example layers the new primitive on top of the `task-list` example. The
`PlanChannelAccessor` carries TaskList state; the `SystemReminderChannel`
carries harness messages — two complementary channels, one workflow.

| Channel | Role | Lifecycle |
|---|---|---|
| `InMemoryPlanChannelAccessor` (TaskList) | State the agent reasons about | Append-only history, persistent |
| `SystemReminderChannel` | Messages from harness to agent | Drain-on-read, transient |

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./system-reminders/run.sh                                         # default Tokyo prompt
./system-reminders/run.sh "Plan a v2.0 release for an open-source library"
```

Same provider auto-detection as every other example — switch between
`openai-mini`, `openai`, `ollama` via `SPRING_PROFILES_ACTIVE`.

## Output shape

```
======================================================================
  SystemReminderChannel — harness-injected runtime context
======================================================================

--- Turn 1: decompose into tasks (do NOT execute yet) ---
    plan  1 [+]          Research popular attractions and activities in Tokyo...
    plan  2 [+]          Find and compare accommodation options...
    ...
    reminder posted: [task-list] You have 6 pending tasks and none in progress. Begin executing...

--- Turn 2: continue (the harness has injected a reminder) ---
    turn 2 user prompt contains <system-reminder>: true
    plan  7 [APPROVED]   Research popular attractions...
    plan  8 [EXECUTED]   Research popular attractions...
    ...
    reminder posted: [task-list] All 6 tasks are now complete. Summarise...

--- Turn 3: wrap up (another reminder prepended) ---
    turn 3 user prompt contains <system-reminder>: true
    agent reply (turn 3): The Tokyo trip plan is now complete. Here is a summary of...

======================================================================
  Quality check
======================================================================
  reminders posted:   2

  Checks:
    [PASS] >= 2 reminders posted
    [PASS] turn 2 + turn 3 prompts contained <system-reminder>
    [PASS] all tasks reached a terminal state
    [PASS] turn 3 response references completion/summary

  QUALITY CHECK PASSED
```

## Three-turn flow

```
Turn 1: "Decompose this trip into tasks. Do NOT execute yet."
        Agent calls task_create x N.
        harnessTick(): posts "you've drafted N tasks; now execute them".

Turn 2: "Continue with your plan."  ← preceded by the drained reminder
        Agent calls task_update(start) -> work -> task_update(complete) for each.
        harnessTick(): posts "all N tasks complete; summarise".

Turn 3: "Anything else?"            ← preceded by the drained reminder
        Agent gives a wrap-up summary that explicitly references the reminder.
```

## Why two channels not one

`EvolvingPlan` channels carry **state** the harness reasons about (plan
entries, status transitions, audit history). `SystemReminderChannel` carries
**messages** the harness sends to the agent (transient, fire-and-forget,
drain-on-read).

Conflating them would force one of two compromises: (a) state-channel entries
get mutated by drain semantics, breaking the audit trail; or (b) reminders
accumulate forever in the state channel, requiring TTL or pruning logic.
Two purpose-built primitives keep both stories clean.

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
