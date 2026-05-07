# Self-Improving Plan Loop

The closed loop. **This is what proves SwarmAI's self-improvement claim.**

## What's missing without this example

Two prior examples each cover one half of the story:

| Example | Covers | Doesn't cover |
|---|---|---|
| `plan-loop` | Workflow machinery, real LLM, subagents, replanner | Bandit influencing decisions; learning being applied |
| `bandit-learning` | Thompson Sampling convergence, promotion gate, snapshot/restore | Real workflow; closed-loop application |

The framework's "stateful learning harness" claim is only credible if the bandit's learning actually changes the workflow's decisions over time. **This example makes that visible.**

## What you'll see

A scenario with two buckets of LOW-risk actions:

- **fastpath** (`rotate_logs`): underlying P(success) = 0.95 — reliable
- **slowpath** (`wipe_cache`): underlying P(success) = 0.10 — looks LOW-risk by static rules but actually fails most of the time

Static policy rules (RiskTier + DriftBudget) cannot tell these apart — both classify as LOW-risk. The bandit can: it observes outcomes per bucket and learns the asymmetry.

The policy chain: `all(RiskTier, DriftBudget, BanditPromotionGate)`. Pre-graduation, the gate's `alwaysAuto` fallback means the AND collapses to "static decides." Post-graduation, the bandit's verdict joins the AND — and a low-mean bucket can **veto** what static rules would have auto-approved.

State is persisted to `~/.swarmai/self-improving-plan-loop-bandit.json` so learning survives across runs.

## Cold-start run output

```
   1 | wipe_cache   | PLAN_POLICY  | FAIL       | mean=0.33 obs=1 warming
   5 | wipe_cache   | PLAN_POLICY  | FAIL       | mean=0.20 obs=3 warming
  10 | rotate_logs  | PLAN_POLICY  | success    | mean=0.86 obs=5 warming
  35 | wipe_cache   | PLAN_POLICY  | FAIL       | mean=0.10 obs=18 warming
  40 | rotate_logs  | PLAN_POLICY  | success    | mean=0.95 obs=20 READY
  45 | wipe_cache   | alice        | FAIL       | mean=0.09 obs=20 READY    <- moment of graduation
  50 | rotate_logs  | PLAN_POLICY  | success    | mean=0.96 obs=25 READY
  55 | wipe_cache   | alice        | FAIL       | mean=0.09 obs=20 READY
  ...
 120 | rotate_logs  | PLAN_POLICY  | FAIL       | mean=0.95 obs=60 READY
```

**Tally:**
- fastpath: 60 auto-approved, 0 routed to human
- slowpath: 20 auto-approved, **40 routed to human** — bandit started vetoing once it had evidence

The slowpath bucket graduated around run 40. Every `wipe_cache` after that is rejected by the bandit and routed to alice. Meanwhile `rotate_logs` keeps auto-approving — same policy chain, different verdict per bucket because the bandit's evidence per bucket differs.

Note `obs=20` stays frozen for slowpath after graduation: the trainer's `decidedBy` filter skips alice-decided transitions, so the bandit doesn't poison its own model with operator-routed runs.

## Second run — persistence in action

Re-run without clearing state:

```
bandit state on entry: 2 buckets known    <- loaded from disk

   1 | wipe_cache   | alice        | FAIL       | mean=0.09 obs=20 READY    <- from row 1
   5 | wipe_cache   | alice        | FAIL       | mean=0.09 obs=20 READY
  10 | rotate_logs  | PLAN_POLICY  | success    | mean=0.96 obs=65 READY
  ...
```

**Tally:**
- fastpath: 60 auto-approved, 0 routed to human
- slowpath: **0 auto-approved, 60 routed to human**

The bandit's learning from run 1 carries forward. The workflow makes **better decisions on day 2** because of what it observed on day 1. Same code, same input, different — and demonstrably correct — output.

**This is self-improvement as a system.**

## Run

```bash
./run.sh self-improving-plan-loop
# or
./self-improving-plan-loop/run.sh
```

To reset and watch the cold-start convergence again:

```bash
rm ~/.swarmai/self-improving-plan-loop-bandit.json
./run.sh self-improving-plan-loop
```

Default profile is Ollama; works fine with no LLM calls. To use the OpenAI profile (which skips Ollama setup but still doesn't call OpenAI for this example):

```bash
SPRING_PROFILES_ACTIVE=openai-mini ./run.sh self-improving-plan-loop
```

This example does not call any LLM. The simulation is deterministic with seed 42.

## Architecture

The `EvolvingPlan` channel is the integration bus — every component reads/writes it via `PlanChannelAccessor`. The bandit, gate, trainer, and store don't know about each other. They compose by sharing state through the channel.

```
   tool       ┌────────────────────────────┐
   builds ──> │  PlanAwareMutationGuard    │
   plan       └────────────┬───────────────┘
                           │ check()
                           v
              ┌──────────────────────────────────────┐
              │  policy = all(                       │
              │    RiskTieredApprovalPolicy,         │
              │    DriftBudgetApprovalPolicy,        │
              │    BanditPromotionGate(              │
              │      bandit,                         │
              │      alwaysAuto fallback,            │
              │      minObs=20, ci-half<=0.12)       │
              │  )                                   │
              └─────────────┬────────────────────────┘
                            │ verdict
                            v
                ┌──────────────────────────┐
                │  BanditApprovalTrainer   │  observes channel transitions,
                │  (filters PLAN_POLICY)   │  feeds bandit per-bucket reward
                └──────────────────────────┘

         FileBanditStateStore (~/.swarmai/...-bandit.json)
         load on startup ; save on shutdown
```

## Source layout

```
self-improving-plan-loop/
├── README.md
├── run.sh
└── src/main/java/ai/intelliswarm/swarmai/examples/selfimprovingplanloop/
    └── SelfImprovingPlanLoopExample.java   # @Component, drives 120 simulated runs
```

## Going further

This example uses simulated outcomes for determinism. To wire this into a real LLM-driven workflow:

1. Replace the deterministic outcome simulation with real tool calls (e.g., `WindowsFileSystemTool`, etc.) — the rest of the wiring stays identical.
2. Replace the in-memory `PlanChannelAccessor` with `AgentStateBackedPlanChannelAccessor` so the plan persists in the swarm's `AgentState`.
3. Add a `LlmReplanner` like in `plan-loop` — the bandit's learning will then influence which amendments get auto-applied vs which need operator review.
