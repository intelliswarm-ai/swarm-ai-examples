package ai.intelliswarm.swarmai.examples.planloop;

import ai.intelliswarm.swarmai.agent.subagent.InProcessSubagentSpawner;
import ai.intelliswarm.swarmai.agent.subagent.PlanAttachingSubagentSpawner;
import ai.intelliswarm.swarmai.agent.subagent.Subagent;
import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpec;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpawner;
import ai.intelliswarm.swarmai.state.plan.BanditApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.BanditApprovalTrainer;
import ai.intelliswarm.swarmai.state.plan.ChannelDerivedReplanContextSupplier;
import ai.intelliswarm.swarmai.state.plan.DriftBudgetApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.EvolvingPlan;
import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.LlmReplanner;
import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanContextRenderer;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.PlanOperator;
import ai.intelliswarm.swarmai.state.plan.ReplanTriggerPolicy;
import ai.intelliswarm.swarmai.state.plan.ReplannerLoop;
import ai.intelliswarm.swarmai.state.plan.Risk;
import ai.intelliswarm.swarmai.state.plan.RiskTieredApprovalPolicy;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.safety.PlanAwareMutationGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * End-to-end demonstration of SwarmAI's evolving plan loop.
 *
 * <p>What this shows in one workflow run:
 * <ul>
 *   <li><b>{@link EvolvingPlan} channel</b> as the single integration point — every component
 *       reads/writes the same channel via {@link PlanChannelAccessor}.</li>
 *   <li><b>Composed approval policies</b> — risk-tier + drift-budget (decision-makers) plus a
 *       bandit attached as observer that learns silently.</li>
 *   <li><b>{@link PlanAwareMutationGuard}</b> — wraps the safety chain so tool calls go through
 *       policy first; auto-approves what's safe, routes the rest to a human.</li>
 *   <li><b>{@link Subagent}</b> for delegated investigation — operator spawns an auditor
 *       subagent for a second opinion before deciding on a HIGH-risk action.</li>
 *   <li><b>Operator override</b> fires the {@link ReplannerLoop} via
 *       {@link ReplanTriggerPolicy#onRejection()}.</li>
 *   <li><b>{@link LlmReplanner}</b> — uses Spring AI's {@link ChatClient} to propose a
 *       structured amendment ({@link LlmReplanner.Amendments}) which the loop appends to the
 *       channel.</li>
 *   <li><b>{@link BanditApprovalTrainer}</b> — observes channel transitions and learns from
 *       outcomes, filtering to only the policy's own decisions for honest training data.</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   ./evolving-plan-loop/run.sh
 *   # or, equivalently:
 *   ./run.sh plan-loop
 * </pre>
 *
 * <p><b>Output is non-deterministic.</b> The auditor subagent and the replanner both make
 * real LLM calls, and their answers shape the run. Sometimes the auditor says PROCEED;
 * sometimes the replanner returns no amendments because the LLM's JSON didn't parse.
 * Both paths are handled and printed — the loop is failure-tolerant by design.
 *
 * <p>For a fully deterministic run, see the in-tree
 * {@code ai.intelliswarm.swarmai.showcase.PlanLoopShowcaseTest} in the SwarmAI repo, which
 * uses Mockito-stubbed canned responses.
 */
@Component
public class PlanLoopExample {

    private static final Logger logger = LoggerFactory.getLogger(PlanLoopExample.class);

    private final ChatClient.Builder chatClientBuilder;

    public PlanLoopExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        ExecutorService subagentExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "subagent-pool");
            t.setDaemon(true);
            return t;
        });
        try {
            runShowcase(subagentExecutor);
        } finally {
            subagentExecutor.shutdownNow();
        }
    }

    private void runShowcase(ExecutorService subagentExecutor) {
        banner("SwarmAI Plan-Loop Showcase — live LLM");
        line("A simulated deploy workflow that exercises every plan-loop primitive.");
        line("LLM-driven amendments come from a real ChatClient — output may vary run-to-run.");
        nl();

        // ---------- 1. Wire the loop ----------
        banner("Setup");

        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        PlanContextRenderer renderer = new PlanContextRenderer();
        PlanOperator operator = new PlanOperator(channel);

        // Static policies decide; bandit is an observer that learns silently.
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        PlanApprovalPolicy policy = PlanApprovalPolicy.all(
                new RiskTieredApprovalPolicy(),
                new DriftBudgetApprovalPolicy(5));

        BanditApprovalTrainer.attach(channel, bandit);

        // Stand-in human gate. In a real app this routes to a UI / CLI / Slack.
        MutationGuard humanDelegate = plan ->
                MutationGuard.Decision.approve("alice", "operator approved at gate");

        PlanAwareMutationGuard guard =
                new PlanAwareMutationGuard(humanDelegate, channel, policy);

        // LLM-driven replanner — uses the injected ChatClient.Builder so the same
        // provider config (Ollama / OpenAI / Anthropic) flows through.
        LlmReplanner replanner = new LlmReplanner(chatClientBuilder.build());

        // Auto-derive observations from recent terminal transitions on the channel,
        // so the replanner sees what *actually* happened rather than a hand-crafted note.
        // Goal is directive: the LLM has been seen reasoning "rejection is the correction,
        // no amendment needed" when the goal is too soft. We explicitly ask for a concrete
        // recovery proposal whenever a step was rejected.
        //
        // The fifth argument (amendmentPolicy) re-gates LLM-emitted amendments through the
        // same policy chain that gates tool calls. Without this, an LLM could effectively
        // introduce HIGH-risk actions that bypass the static tier rule. With it, a HIGH-risk
        // amendment lands as PROPOSED (waiting for alice) just like a HIGH-risk tool call.
        ReplannerLoop loop = ReplannerLoop.start(channel, replanner,
                ReplanTriggerPolicy.onRejection(),
                ChannelDerivedReplanContextSupplier.builder(channel)
                        .goal("Ship the change safely. " +
                                "If any step was rejected, propose a concrete recovery action " +
                                "(e.g. rollback, retry with safeguards) as a new PROPOSED entry.")
                        .maxObservations(8)
                        .build(),
                policy);  // ← amendments now go through the same gate as tool calls

        // Subagent for investigation — same ChatClient builder, separate session.
        // Wrap with PlanAttachingSubagentSpawner so the auditor's verdict (status,
        // output, metrics) lands on the target entry's metadata as part of the
        // audit trail. Specs that don't set parentMetadata("attachToEntryId", …)
        // pass through unchanged.
        SubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                new InProcessSubagentSpawner(chatClientBuilder.build(), subagentExecutor),
                channel);

        line("Channel: in-memory plan accessor");
        line("Policy:  RiskTier(LOW,MEDIUM auto once seeded) ∧ DriftBudget(5)");
        line("Bandit:  observer-only; promote to vetoer once buckets have data");
        line("Replanner: LlmReplanner driven by ChatClient (real LLM call)");
        line("Subagent: in-process, runs ChatClient.prompt() async with timeout/cancel");
        nl();

        // ---------- 2. Simulated tool calls ----------
        banner("Step 1 — seed action: stage_build (MEDIUM)");
        MutationGuard.Decision d1 = guard.check(deployPlan("stage_build",
                MutationPlan.RiskLevel.MEDIUM, "Stage the build artifact"));
        line("guard verdict: " + describe(d1));
        guard.observe(d1.correlationId(), true, "build staged");
        renderPlan(channel, renderer);

        banner("Step 2 — run_tests (MEDIUM) — auto-approve via policy");
        MutationGuard.Decision d2 = guard.check(deployPlan("run_tests",
                MutationPlan.RiskLevel.MEDIUM, "Run the test suite against staged build"));
        line("guard verdict: " + describe(d2));
        guard.observe(d2.correlationId(), true, "all tests pass");
        renderPlan(channel, renderer);

        banner("Step 3 — promote_canary (MEDIUM) — auto-approve");
        MutationGuard.Decision d3 = guard.check(deployPlan("promote_canary",
                MutationPlan.RiskLevel.MEDIUM, "Roll out to 5% of traffic"));
        line("guard verdict: " + describe(d3));
        guard.observe(d3.correlationId(), true, "canary stable for 10 minutes");
        renderPlan(channel, renderer);

        line("Bandit so far for tool:deploy:" + Risk.MEDIUM + ":");
        line("  observations=" + bandit.observationCount("tool:deploy:" + Risk.MEDIUM));
        line("  meanSuccessRate=" + fmt(bandit.meanSuccessRate("tool:deploy:" + Risk.MEDIUM)));
        nl();

        banner("Step 4 — promote_prod (HIGH) — RiskTier vetoes auto-approve");
        MutationGuard.Decision d4 = guard.check(deployPlan("promote_prod",
                MutationPlan.RiskLevel.HIGH, "Promote canary to 100% of prod"));
        line("guard verdict: " + describe(d4));
        line("HIGH risk forced human routing — alice approves at the gate.");
        line("(execution hasn't started yet — alice wants a second opinion first)");
        renderPlan(channel, renderer);

        // ---------- 3. Subagent investigation ----------
        banner("Step 5 — pre-execution audit; alice spawns an auditor SUBAGENT");
        line("alice: \"this is the prod cut. Let me get an audit before I let it execute.\"");
        nl();

        // Find the prod entry BEFORE the spawn so we can attach the auditor's
        // verdict to it. With PlanAttachingSubagentSpawner in the chain, the
        // SubagentResult will land on this entry's metadata under "subagent.auditor".
        PlanEntry prodEntry = findLatestForOp(channel, "promote_prod");

        SubagentSpec auditSpec = SubagentSpec.builder()
                .type("auditor")
                .systemPrompt("You are a release-safety auditor. Your job is to weigh whether a " +
                        "production change should proceed given the recent operational signals you " +
                        "are shown. Reply in one short paragraph and END with one of:  PROCEED  /  HOLD  /  ROLLBACK.")
                .userPrompt("Should promote_prod proceed? Recent signals: " +
                        "canary saw error rate jump 22x baseline; a recent dependency upgrade is in scope; " +
                        "tests passed before staging.")
                .timeout(Duration.ofSeconds(60))
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, prodEntry.id())
                .build();

        Subagent auditor = spawner.spawn(auditSpec);
        SubagentResult audit = auditor.awaitResult(Duration.ofSeconds(90));
        line("auditor subagent status:  " + audit.status());
        line("auditor durationMs:       " + audit.metrics().get("durationMs"));
        line("auditor verdict:");
        for (String aLine : audit.output().split("\n")) line("  > " + aLine);
        nl();

        // The PlanAttachingSubagentSpawner attached the SubagentResult to prodEntry
        // under "subagent.auditor". Re-fetch prodEntry to show the audit trail
        // now carries the auditor's verdict directly on the entry.
        PlanEntry prodAfterAudit = channel.get().byId(prodEntry.id()).orElseThrow();
        Object attachedAudit = prodAfterAudit.metadata().get("subagent.auditor");
        if (attachedAudit != null) {
            line("PlanAttachingSubagentSpawner attached the auditor's report to entry "
                    + shortId(prodEntry.id()) + ":");
            line("  metadata.subagent.auditor = " + attachedAudit);
            nl();
        }

        // ---------- 4. Operator decision based on audit ----------
        banner("Step 6 — alice decides based on auditor's recommendation");
        String upper = audit.output() == null ? "" : audit.output().toUpperCase();
        boolean rollback = upper.contains("ROLLBACK") || upper.contains("HOLD");

        if (rollback) {
            line("Auditor said ROLLBACK/HOLD — alice rejects entry " + shortId(prodEntry.id()));
            operator.reject(prodEntry.id(), "alice",
                    "auditor recommended rollback after risk assessment");
            line("ReplannerLoop fires on the REJECTED transition.");
            line("LlmReplanner is asked to amend the plan — calling the LLM…");
        } else {
            line("Auditor said PROCEED — alice lets the prod cut go through.");
            // The earlier approval was via the human delegate; transition through the
            // operator API rather than guard.observe() because in this narrative
            // there's no actual tool execution producing success/failure — alice is the
            // one signalling completion.
            operator.markExecuted(prodEntry.id(), "deploy", "prod cut completed");
        }
        nl();
        renderPlan(channel, renderer);

        if (rollback) {
            List<PlanEntry> proposedNow = channel.get().withStatus(PlanEntry.Status.PROPOSED);
            if (proposedNow.isEmpty()) {
                line("(LlmReplanner did not propose a new entry. Common causes:");
                line(" the LLM returned an empty amendments object, or its JSON didn't parse.");
                line(" The loop is failure-tolerant — see Amendments.rationale() for the reason.)");
            } else {
                PlanEntry rollbackEntry = proposedNow.stream()
                        .filter(e -> LlmReplanner.PROPOSED_BY.equals(e.proposedBy()))
                        .findFirst()
                        .orElse(proposedNow.get(0));
                line("Replanner amendment captured: " + shortId(rollbackEntry.id())
                        + " — " + rollbackEntry.metadata().get("summary"));
            }
            nl();
        }

        // ---------- 5. Final state + bandit learnings ----------
        banner("Final plan state");
        renderPlan(channel, renderer);

        banner("Bandit learnings");
        printBandit(bandit, "tool:deploy:" + Risk.MEDIUM);
        printBandit(bandit, "tool:deploy:" + Risk.HIGH);
        nl();
        line("The MEDIUM bucket learned from auto-approved-then-EXECUTED actions.");
        line("Promote the bandit from observer to vetoer once you trust its data:");
        line("  PlanApprovalPolicy.all(tier, budget, bandit)        // conservative");
        line("  PlanApprovalPolicy.any(all(tier, budget), bandit)   // bandit can bypass");

        loop.close();
    }

    // ============== Helpers ==============

    private static MutationPlan deployPlan(String op, MutationPlan.RiskLevel risk, String summary) {
        return MutationPlan.builder()
                .toolName("deploy")
                .summary(summary)
                .riskLevel(risk)
                .op(op, "release-" + java.time.LocalDate.now())
                .metadata("apply", true)
                .build();
    }

    private static PlanEntry findLatestForOp(PlanChannelAccessor channel, String opType) {
        EvolvingPlan plan = channel.get();
        for (int i = plan.entries().size() - 1; i >= 0; i--) {
            PlanEntry e = plan.entries().get(i);
            if (e.payload() instanceof MutationPlan mp
                    && mp.ops().stream().anyMatch(o -> o.type().equals(opType))) {
                return e;
            }
        }
        throw new IllegalStateException("no entry found for op " + opType);
    }

    private static String describe(MutationGuard.Decision d) {
        return (d.approved() ? "APPROVED" : "REJECTED")
                + " by " + d.decidedBy()
                + (d.reason().isEmpty() ? "" : " — " + d.reason())
                + (d.correlationId() != null ? " (id=" + shortId(d.correlationId()) + ")" : "");
    }

    private static String shortId(String id) {
        return id == null ? "(null)" : id.substring(0, Math.min(8, id.length()));
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }

    private static void renderPlan(PlanChannelAccessor channel, PlanContextRenderer renderer) {
        String rendered = renderer.render(channel.get());
        if (rendered.isBlank()) {
            line("(plan is empty)");
        } else {
            for (String l : rendered.split("\n")) line(l);
        }
        nl();
    }

    private static void printBandit(BanditApprovalPolicy bandit, String key) {
        long obs = bandit.observationCount(key);
        if (obs == 0) {
            line(key + ": no observations yet");
        } else {
            line(key + ": observations=" + obs
                    + ", meanSuccessRate=" + fmt(bandit.meanSuccessRate(key)));
        }
    }

    private static void banner(String title) {
        nl();
        line("======================================================================");
        line("  " + title);
        line("======================================================================");
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }
}
