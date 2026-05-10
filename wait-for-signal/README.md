# wait-for-signal — pause-resume primitive (swarmai 1.0.21+)

Showcases the `WaitForSignalTool` end-to-end with a real LLM. The agent receives
one tool — `wait_for_signal` — bound to a `SignalDispatcher`. A separate
thread simulates an external system (CI pipeline, webhook, batch job) that
fires a wake-up signal after a delay. The agent's tool-call thread blocks on
a `CompletableFuture`; when the external thread calls `dispatcher.deliver(...)`,
the future completes and the signal payload reaches the LLM as the tool's
return value.

## What this proves (Mode A — synchronous block-on-wait)

| | |
|---|---|
| The agent can pause synchronously inside its tool-call thread | `wait_for_signal(signalKey, reason, timeoutSeconds)` blocks until signal/timeout/cancel |
| External signal sources can wake the agent | `SignalDispatcher.deliver(key, payload)` from any thread completes the future |
| The signal payload reaches the LLM | Tool returns `"Signal received: <payload>"`, which the LLM uses to produce its response |
| TTL auto-fires reliably | Auto-expiry runs on a daemon `ScheduledExecutorService` — no caller polling required |
| The wait genuinely paused | Run takes ≥3s wall-clock even though the LLM round-trip is sub-second |

## Architecture

```
                    ┌──────────────────────┐
                    │  Foreground LLM call │
                    └──────────────────────┘
                              │
                              │ calls wait_for_signal(...)
                              ▼
                    ┌──────────────────────┐
                    │ WaitForSignalTool    │
                    │ — registerWait()     │
                    │ — awaitSignal()  ────┼──── BLOCKS on CompletableFuture
                    └──────────────────────┘
                              ▲
                              │ deliver(key, payload)
                              │ completes the future
                              │
            ┌─────────────────┴─────────────────┐
            │  External signal source           │
            │  (separate thread in this demo;   │
            │   in production: webhook handler, │
            │   wall-clock scheduler, CI hook)  │
            └───────────────────────────────────┘
```

| Type | Role |
|---|---|
| `Signal` | Record: `key`, `payload`, `deliveredAt` |
| `WaitStatus` | Enum: PENDING / COMPLETED / CANCELLED / EXPIRED |
| `WaitForSignalRequest` | Immutable snapshot of dispatcher state |
| `SignalDispatcher` | FIFO key-matching, auto-expiry via daemon scheduler, listener for unmatched signals |
| `WaitForSignalTool` | Spring AI `ToolCallback` named `wait_for_signal` |

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./wait-for-signal/run.sh
./wait-for-signal/run.sh "Pause for the integration-test signal and report results when it arrives."
```

The default scenario simulates a canary deployment: the LLM pauses on
`deploy.canary.healthy`, the simulator fires that key 3 seconds later with
metrics in the payload, the LLM uses the metrics to recommend "promote /
hold / roll back".

## Output shape

```
======================================================================
  WaitForSignal — pause-resume primitive (1.0.21+)
======================================================================

  Foreground tool set: 1 callback (wait_for_signal)
  External simulator: separate thread that fires
    dispatcher.deliver("deploy.canary.healthy", "...metrics...")
  after a 3-second delay.

  User goal:
    I'm rolling out a canary deployment of v2.4.0. ...

    [external simulator] firing signal 'deploy.canary.healthy' after 3s delay
    [external simulator] delivered=true, dispatcher.dropped=0

======================================================================
  Final response from the LLM
======================================================================
The canary deployment of v2.4.0 reports 100% healthy with 0 errors over 60
seconds. Latency p99 is 42ms (well within budget) and 5/5 cells are green.
RECOMMENDATION: promote to full rollout. The canary signal is unambiguous —
no degradation observed and all SLOs are within healthy bounds.

======================================================================
  Dispatcher state at end of run
======================================================================
  wait_a1b2c3d4  status=COMPLETED  age=3s
    signalKey: deploy.canary.healthy
    reason:    waiting for canary deploy of v2.4.0
    received:  100% healthy, 0 errors over 60s, latency p99=42ms, 5/5 cells green
  dropped signals: 0

======================================================================
  Quality check
======================================================================
  elapsed wall-clock:    4.21s
  total waits:           1
  completed:             1
  expired:               0
  external delay was:    3s

  Checks:
    [PASS] exactly 1 wait registered
    [PASS] wait reached COMPLETED status
    [PASS] run took at least 3s — agent genuinely paused
    [PASS] final reply references the signal payload's content

  QUALITY CHECK PASSED
  -> The agent paused synchronously, an external thread fired the signal,
  -> the payload reached the LLM, and the response uses it.
```

## What's NOT yet built (future phases)

This example validates **Mode A** — synchronous block-on-wait, single JVM session.

For genuine cross-session pause-resume (the use case the original backlog
described as `WaitForSignal` / pause-resume), the missing layers are:

| Phase | Layer | Status |
|---|---|---|
| 1 | Dispatcher abstractions + agent-callable tool | ✅ shipped |
| 2 | Wall-clock signal source (`pause-until-2026-06-01T09:00`) | not built |
| 3 | Webhook signal source (REST endpoint that calls `deliver`) | not built |
| 4 | `CheckpointingSignalDispatcher` — instead of blocking, persists state via `CheckpointSaver` and releases the agent thread | not built |
| 5 | Resume CLI / API that rehydrates `AgentState` and re-enters the agent loop | not built |

The records (`Signal`, `WaitForSignalRequest`, `WaitStatus`) are immutable on
purpose — they round-trip through `CheckpointSaver` unchanged when phase 4
lands.

## Composability

`SignalDispatcher` is independent of any LLM — it's a typed message queue
with key-FIFO matching. Wire signal sources to it from anywhere:

- **Wall-clock scheduler**: `Executors.newSingleThreadScheduledExecutor().schedule(() -> dispatcher.deliver(...), delay, MILLISECONDS)`
- **Webhook handler**: Spring web controller mapping `POST /signal/:key` → `dispatcher.deliver(key, body)`
- **Job-completion hook**: tail an external job's status file and call `deliver` when "done"
- **Manual operator command**: CLI subcommand `swarmai signal <key> <payload>`

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
