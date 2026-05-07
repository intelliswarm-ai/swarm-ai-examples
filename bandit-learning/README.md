# Bandit Learning

Visible demonstration that SwarmAI's `BanditApprovalPolicy` actually learns from outcomes — and that the `BanditPromotionGate` correctly graduates buckets from observer to vetoer once they have enough evidence.

**No LLM required** — this example runs a deterministic simulation. Pure framework demo.

## What it shows

- A `BanditApprovalPolicy` is fed 600 simulated outcomes across two buckets:
  - `tool:deploy:LOW` ("good") with underlying P(success) = 0.9
  - `tool:deploy:HIGH` ("bad") with underlying P(success) = 0.1
- A learning-curve table prints every 50 iterations:
  - Bandit's per-bucket mean estimate
  - 95% Wald confidence-interval half-width
  - Total observations
  - Whether the `BanditPromotionGate` considers the bucket ready
  - Empirical AUTO_APPROVE rate over 100 probes
- At iteration 300, **`BanditState` is snapshotted, the policy is rebuilt fresh, and the snapshot is restored**. The simulation continues against the restored bandit, demonstrating the persistence round-trip.
- Final asserts (in the in-tree test): the good bucket converges to mean ≈ 0.9, the bad bucket to ≈ 0.1; both auto-approve with the expected asymmetry.

## Why it matters

The plan-loop showcase (`./run.sh plan-loop`) demonstrates the *machinery*. This example demonstrates the *learning*. Together they answer two questions:

1. *Plan-loop:* Do the components compose correctly into a workflow with policy-driven approvals, replanner, and subagents? — Yes.
2. *Bandit-learning:* Does the bandit actually converge toward truth, and does the gate fire at the right moment? — Yes, visibly, in 600 iterations.

Without #2, the framework's "stateful learning harness" claim is unproven. This example is the proof.

## Sample output (excerpt)

```
 iter | good: mean   ci½    obs  ready  auto%/100 | bad:  mean   ci½    obs  ready  auto%/100
------+-------------------------------------------+-------------------------------------------
   50 |  0.926  0.097     25  no       0          |  0.074  0.097     25  no       0
  100 |  0.904  0.079     50  yes    100          |  0.154  0.097     50  yes      0
  ...
  600 |  0.901  0.034    300  yes    100          |  0.096  0.033    300  yes      0

Final state:
  good bucket: mean=0.901  ci½=0.034  obs=300  ready=true  (truth=0.9)
  bad  bucket: mean=0.096  ci½=0.033  obs=300  ready=true  (truth=0.1)
```

## Run

```bash
./run.sh bandit-learning
# or, equivalently:
./bandit-learning/run.sh
```

The shared runner will start Ollama (or use OpenAI if `SPRING_PROFILES_ACTIVE` requests it), build the jar if needed, and dispatch this example. The LLM provider is technically loaded but never called — the example uses no `ChatClient`.

If you want to skip Ollama entirely:

```bash
SPRING_PROFILES_ACTIVE=openai-mini ./run.sh bandit-learning
# Even though we don't call OpenAI either; the env var just suppresses Ollama setup.
```

## Source layout

```
bandit-learning/
├── README.md
├── run.sh
└── src/main/java/ai/intelliswarm/swarmai/examples/banditlearning/
    └── BanditLearningExample.java   # @Component — drives the simulation
```

## Reading the bandit's output

| Column | Meaning |
|---|---|
| `mean` | Bayesian point estimate of P(auto-approval was correct) for this bucket — `α/(α+β)` of the auto arm |
| `ci½` | 95% Wald CI half-width — narrower means more confident |
| `obs` | Total observations recorded for this bucket |
| `ready` | Promotion gate verdict — true once `obs ≥ 30` AND `ci½ ≤ 0.10` |
| `auto%/100` | Empirical fraction of AUTO_APPROVE decisions over 100 Thompson-sampled probes |

Once a bucket is `ready`, the gate routes its decisions to the bandit; before that, it falls back to `alwaysHuman()`.

## How to apply this in a real workflow

The example prints an "application guide" at the end with the recommended composition pattern. Summary:

```java
BanditApprovalPolicy bandit = new BanditApprovalPolicy();
BanditApprovalTrainer.attach(channel, bandit);  // observer mode

BanditPromotionGate gate = new BanditPromotionGate(
    bandit,
    PlanApprovalPolicy.alwaysHuman(),  // fallback for unready buckets
    /*minObs*/ 30,
    /*maxCiHalfWidth*/ 0.10);

PlanApprovalPolicy policy = PlanApprovalPolicy.any(
    PlanApprovalPolicy.all(tier, budget),  // static rules first
    gate);                                  // gated bandit can bypass
```

## Future work

This example demonstrates in-process learning. Two follow-ups would make it production-grade:

1. **`BanditStateStore` SPI** with JSON-file and JDBC implementations so the bandit's learning survives restarts. The snapshot/restore round-trip in this example is the in-memory mechanism; an SPI completes the loop.
2. **Shaped rewards from `swarmai-eval`.** Today reward is binary (±1 from execution success/failure). With a real evaluator, the bandit could learn from continuous quality scores — converging faster and to nuanced means.

Both are tracked in `docs/internal/HARNESS_PARITY_BACKLOG.md` (in the SwarmAI repo) under Path A increments 2 and 3.
