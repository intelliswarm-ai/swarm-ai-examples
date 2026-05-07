# Evolving Plan Loop

End-to-end demonstration of SwarmAI's plan-loop primitives: a workflow whose plan is **state**, not topology, and which **evolves** as outcomes accumulate.

Integrated into the shared examples runner — runs like every other example via `./run.sh plan-loop`.

## What this shows

A simulated deploy workflow with four mutating actions:

1. `stage_build` (MEDIUM) — first action; no policy seed yet, routes to a human gate.
2. `run_tests` (MEDIUM) — seed approval exists, **policy auto-approves**.
3. `promote_canary` (MEDIUM) — auto-approves again.
4. `promote_prod` (HIGH) — risk tier vetoes auto, routes to human.

Before letting the prod cut execute, the operator **spawns an `InProcessSubagent`** as an auditor. The auditor runs an LLM call asynchronously and returns a structured verdict (`PROCEED` / `HOLD` / `ROLLBACK`).

If the auditor recommends rolling back, the operator **rejects** their own earlier approval. The `ReplannerLoop` fires on that rejection, the `LlmReplanner` is asked to amend the plan, and the resulting amendment lands on the channel as a fresh PROPOSED entry.

Throughout the run, the `BanditApprovalTrainer` watches the channel silently and learns from every `PLAN_POLICY` transition — so a future run can promote the bandit from observer to vetoer once it has accumulated enough confidence.

The full state of the plan is rendered after each step via `PlanContextRenderer` so you can watch it evolve.

## Primitives exercised (one place per run to look)

| Primitive | Where to look in `PlanLoopExample.java` |
|---|---|
| `EvolvingPlan` channel | `InMemoryPlanChannelAccessor channel` |
| Composed approval policy | `PlanApprovalPolicy.all(new RiskTieredApprovalPolicy(), new DriftBudgetApprovalPolicy(5))` |
| Bandit observer + trainer | `BanditApprovalTrainer.attach(channel, bandit)` |
| Plan-aware mutation guard | `new PlanAwareMutationGuard(humanDelegate, channel, policy)` |
| Replanner loop on rejection | `ReplannerLoop.start(channel, replanner, ReplanTriggerPolicy.onRejection(), …)` |
| LLM-driven amendments | `new LlmReplanner(chatClientBuilder.build())` |
| Subagent for delegated investigation | `new InProcessSubagentSpawner(chatClient, executor).spawn(SubagentSpec)` |
| Operator override | `new PlanOperator(channel).reject(entryId, "alice", reason)` |
| Plan context for prompts | `new PlanContextRenderer().render(channel.get())` |

## Prerequisites

This example pins **SwarmAI 1.0.19-SNAPSHOT** at the parent-pom level. Until 1.0.19 ships to Maven Central, build SwarmAI locally first so the dependency resolves from your local Maven cache:

```bash
cd path/to/swarm-ai
./mvnw -DskipTests install
```

Once 1.0.19 is released, the examples repo's parent `pom.xml` can change `<swarmai.version>1.0.19-SNAPSHOT</swarmai.version>` to the release version.

## Run

From the examples root:

```bash
./run.sh plan-loop
```

…or from this example directory:

```bash
./evolving-plan-loop/run.sh
```

The shared `run.sh` will:
1. Detect or start Ollama, pull the configured model (default `mistral:latest`).
2. Build the swarmai-examples jar (~40s first time).
3. Invoke `java -jar target/swarmai-examples-1.0.0-SNAPSHOT.jar plan-loop`.

### Using OpenAI instead

```bash
SPRING_PROFILES_ACTIVE=openai-mini OPENAI_API_KEY=sk-... ./run.sh plan-loop
```

## Output is non-deterministic

This example calls a real LLM. The auditor's verdict and the replanner's amendment shape depend on the model's response. Two consequences:

1. **Sometimes the auditor says PROCEED.** That's a valid path through the demo — the example handles both branches and prints what happened.
2. **Sometimes the replanner returns `Amendments.none(...)`** because the LLM's output didn't parse cleanly into the expected JSON. The loop is failure-tolerant by design — `Amendments.rationale()` records why. The example calls this out in its output.

For a fully deterministic run, see the in-tree `PlanLoopShowcaseTest` in the SwarmAI repo at `swarmai-tools/src/test/java/ai/intelliswarm/swarmai/showcase/`, which uses Mockito-stubbed canned responses.

## Architectural notes

**The channel is the integration.** Each component (guard, trainer, replanner loop, operator) reads/writes the same `EvolvingPlan` channel via its `PlanChannelAccessor`. There is no custom integration class. This is the design holding up: components compose by sharing state through one channel, not by knowing about each other.

**Bandit promotion is intentional, not automatic.** The trainer feeds the bandit silently. Promoting the bandit to a decision-maker is a code change (`policy = PlanApprovalPolicy.all(tier, budget, bandit)`) — by design, so a human signs off after the bandit has accumulated trustworthy data per bucket.

**Reentrancy is guarded.** When the loop's own amendments cause channel transitions, the loop's listener skips them — without this, even one amendment would loop forever. Verified by the in-tree `ReplannerLoopTest`.

## Source layout

```
evolving-plan-loop/
├── README.md
├── run.sh
└── src/main/java/ai/intelliswarm/swarmai/examples/planloop/
    └── PlanLoopExample.java   # @Component injected with ChatClient.Builder
```

The example is a `@Component` registered in the shared `SwarmAIWorkflowRunner`. Source is picked up by the parent pom's `build-helper-maven-plugin` so it joins the same jar as every other example.

## Going further

To extend this example into a real workflow:

1. **Bridge the subagent's output to the plan.** Right now the auditor's verdict is a String the test code parses. A production setup would attach the verdict to the prod entry's `metadata` so it's part of the audit trail.
2. **Re-gate replanner amendments.** Today the replanner appends fresh PROPOSED entries directly. A production setup would route them back through `PlanAwareMutationGuard.check(...)` so amendments obey the same policy as tool output.
3. **Auto-derive `ReplanContext.observations`.** A `ChannelDerivedReplanContextSupplier` could read recent `REJECTED`/`EXECUTED` reasons from the channel and feed them into every replan.
4. **Add a `BanditPromotionGate`** that takes a min-observation count and confidence threshold, and reports when each bucket is ready for promotion.
