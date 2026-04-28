# Reinforcement-Learning-Guided Agent Decisions

Your workflow gets smarter every time you run it — but without the cost. Two lightweight bandit policies (a contextual `LinUCBBandit` + a Bayesian `ThompsonSampling`) replace the hardcoded `if/else` heuristics that decide *should I generate a skill here?* and *should I keep iterating?* They learn online in dozens (not thousands) of observations, run in pure Java, and need **zero tensor libraries**.

> **Why this is a hook:** every other agent framework hard-codes "should I generate a skill here?" as a threshold. Bandits replace that threshold with a policy that adapts per domain — and unlike DQN they don't drag in PyTorch, GPUs, or 100s of training runs to converge.

> **Heads up — directory name is misleading.** This example started as a DQN demo
> using DJL/PyTorch. We replaced it with bandits because they learn faster, run
> in plain Java, and are interpretable. The directory still says `dqn` for git
> history; the code is bandit-based.

## Architecture

```mermaid
graph TD
    A[Analyst Agent] --> R[Reviewer Agent]
    subgraph Tier 2 — RL policies
        LinUCB[LinUCBBandit<br/>contextual<br/>4 actions × 8-dim state]
        Thompson[ThompsonSampling<br/>Bayesian<br/>CONTINUE / STOP]
    end
    R -.gap detected.-> LinUCB
    LinUCB -->|GENERATE / SKIP / REUSE / ESCALATE| A
    R -.iteration done.-> Thompson
    Thompson -->|CONTINUE / STOP| Loop[SELF_IMPROVING loop]
    Loop --> A
    Loop -->|reward| LinUCB
    Loop -->|reward| Thompson
```

## What the policies decide

| Decision | Policy | Action space |
|----------|--------|--------------|
| What to do when a capability gap is detected | `LinUCBBandit` (contextual) | `GENERATE`, `SKIP`, `REUSE_SKILL`, `ESCALATE` |
| Whether to keep iterating | `ThompsonSampling` (Bayesian) | `CONTINUE`, `STOP` |

The 8-dim state vector fed to `LinUCB` includes run progress, stale-iteration count, gap severity, tokens used, and a few synthetic features. Per iteration the bandits each pick an action; once the swarm finishes, both get a reward signal computed from `output.isSuccessful()`, `skillsGenerated`, `skillsReused`, and `totalIterations`.

## Run

```bash
./deep-reinforcement-learning-dqn/run.sh
# or
./run.sh deep-rl "AI orchestration frameworks"
```

The example does multiple sequential runs so you can watch the policies stabilize.

## Sample output

```text
=== Bandit RL Run 1/5 ===
Policy [LinUCB]:    recommended action=SKIP        (count so far=0)
Policy [Thompson]:  convergence recommendation=CONTINUE (mean=0.500)
Run 1 completed: successful=true, skillsGenerated=0, skillsReused=0,
                  iterations=2, reward=0.400

=== Bandit RL Run 5/5 ===
Policy [LinUCB]:    recommended action=GENERATE    (count so far=2)
Policy [Thompson]:  convergence recommendation=STOP    (mean=0.812)
Run 5 completed: successful=true, skillsGenerated=2, skillsReused=1,
                  iterations=2, reward=0.733

=== Bandit RL Training Summary ===
Runs successful:  5/5
Total SELF_IMPROVING iterations: 9
LinUCB total updates: 5
  action GENERATE  → count 2
  action SKIP      → count 1
  action REUSE     → count 1
  action ESCALATE  → count 1
  Thompson CONTINUE → mean 0.667 (n=3)
  Thompson STOP     → mean 0.875 (n=2)
```

## Three-tier policy architecture

The framework's `PolicyEngine` interface accepts any of three implementations — pick based on the data you have:

| Tier | Policy | Where it ships | Training data needed | Best for |
|------|--------|----------------|----------------------|----------|
| 1 | `HeuristicPolicy` | `swarmai-core` | None | Smoke tests, demos, no-data starts |
| 2 | `LinUCBBandit` + `ThompsonSampling` | `swarmai-core` | 10–50 runs | **Most production agents** — this example |
| 3 | DJL/PyTorch RL (custom) | external | 100s of runs | Multi-domain, heavy state spaces |

Bandits are the right answer 95% of the time: cheap, interpretable, and they learn online.

## What you'll learn

- **`LinUCBBandit.selectAction(state)` and `update(state, action, reward)`** — the entire contextual-bandit API.
- **`ThompsonSampling.selectAction()` / `update(action, success)`** — Beta-Bernoulli posterior in 5 lines of usage.
- **State-vector design** — 8 dims here is plenty; the bandit's confidence bound widens for unfamiliar states automatically.
- **Reward shaping** — `computeReward(success, generated, reused, iters)` punishes long iteration counts and rewards skill reuse.
- **Failure-driven learning** — the exception path calls `bandit.update(state, action, -1.0)` so failed actions get pushed down.
- **Why bandits beat DQN here** — fewer hyperparameters, no batch training, no GPU, decisions in <1µs, interpretable per-action statistics.

## Prerequisites

No special deps. The bandit classes live in `swarmai-core` (already on the classpath for every example). You'll need an LLM provider (`ollama` or `openai-mini` profile) — same as every other example.

## Source

- [`DeepRLWorkflow.java`](src/main/java/ai/intelliswarm/swarmai/examples/deeprl/DeepRLWorkflow.java)

## See also

- [`self-improving-agent-learning`](../self-improving-agent-learning/) — same SELF_IMPROVING loop with the framework's default `HeuristicPolicy` (Tier 1).
- [`self-evolving-swarm`](../self-evolving-swarm/) — the meta level: the framework's *topology* evolves between runs.
- [`evaluator-optimizer-feedback-loop`](../evaluator-optimizer-feedback-loop/) — simpler quality-gate loop without RL.
