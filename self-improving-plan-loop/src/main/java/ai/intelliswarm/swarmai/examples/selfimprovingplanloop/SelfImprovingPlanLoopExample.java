package ai.intelliswarm.swarmai.examples.selfimprovingplanloop;

import ai.intelliswarm.swarmai.state.plan.BanditApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.BanditApprovalTrainer;
import ai.intelliswarm.swarmai.state.plan.BanditPromotionGate;
import ai.intelliswarm.swarmai.state.plan.BanditStateStore;
import ai.intelliswarm.swarmai.state.plan.DriftBudgetApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.FileBanditStateStore;
import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.Risk;
import ai.intelliswarm.swarmai.state.plan.RiskTieredApprovalPolicy;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.safety.PlanAwareMutationGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Closes the loop. Demonstrates that the framework's stated "self-improving"
 * behaviour actually changes workflow decisions over time, end-to-end.
 *
 * <p>Two prior examples each cover one half:
 * <ul>
 *   <li>{@code plan-loop} — workflow machinery (real LLM, but bandit is observer-only)</li>
 *   <li>{@code bandit-learning} — learning component (synthetic simulation, no workflow)</li>
 * </ul>
 *
 * <p>This example puts both halves together. The bandit is composed into the
 * policy via {@link BanditPromotionGate}; learning is persisted via
 * {@link FileBanditStateStore} so it survives JVM restarts; and 80 simulated
 * workflow runs visibly transition from "every action goes through the bandit"
 * (uniform behaviour) to "the bandit gates one bucket but lets the other through"
 * (changed behaviour driven by accumulated evidence).
 *
 * <h2>Scenario</h2>
 * Two buckets of LOW-risk actions:
 * <ul>
 *   <li>{@code fastpath} — underlying P(success) = 0.95 (reliable)</li>
 *   <li>{@code slowpath} — underlying P(success) = 0.20 (looks safe by static rules,
 *       but actually fails most of the time)</li>
 * </ul>
 *
 * <p>Static rules cannot tell these apart — both are LOW-risk by classification.
 * The bandit can: it observes outcomes per bucket and learns the asymmetry.
 *
 * <h2>Policy composition</h2>
 * <pre>
 *   policy = all(
 *       RiskTier(LOW,MEDIUM auto once seeded),
 *       DriftBudget(unlimited),
 *       BanditPromotionGate(bandit, fallback=alwaysAuto, minObs=20, ci½≤0.12)
 *   )
 * </pre>
 * <p>Pre-graduation, the gate's {@code alwaysAuto} fallback means {@code all}
 * collapses to "static decides". Post-graduation, the bandit's verdict joins
 * the AND — and a low-mean bucket can <em>veto</em> what static rules would
 * have auto-approved. That veto is the visible self-improvement.
 *
 * <h2>Persistence</h2>
 * State is persisted to {@code ~/.swarmai/self-improving-plan-loop-bandit.json}.
 * Run the example twice; the second run loads where the first left off and
 * the bandit's accumulated evidence carries forward.
 */
@Component
public class SelfImprovingPlanLoopExample {

    private static final Logger logger = LoggerFactory.getLogger(SelfImprovingPlanLoopExample.class);

    private static final int RUNS_PER_BUCKET = 60;            // 120 runs total
    private static final long SEED = 42L;
    private static final double FASTPATH_RATE = 0.95;
    private static final double SLOWPATH_RATE = 0.10;

    private static final String FASTPATH_OP = "rotate_logs";
    private static final String SLOWPATH_OP = "wipe_cache";
    private static final String TOOL = "deploy";
    private static final String FASTPATH_KEY = "tool:" + TOOL + ":" + Risk.LOW + ":" + FASTPATH_OP;
    private static final String SLOWPATH_KEY = "tool:" + TOOL + ":" + Risk.LOW + ":" + SLOWPATH_OP;

    public void run(String[] args) {
        Random rng = new Random(SEED);
        Path stateFile = stateFilePath();
        boolean reset = args != null && java.util.Arrays.asList(args).contains("--reset");

        // ---------- 1. Wire ----------
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();

        // Bucket key includes the operation type so fastpath / slowpath are distinct.
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                e -> "tool:" + TOOL + ":" + e.risk() + ":" + opOf(e),
                /*warmup*/ 0);

        // Optionally clear persisted state so this run demonstrates the full
        // graduation arc from scratch (no prior learning carries over).
        if (reset) {
            try {
                java.nio.file.Files.deleteIfExists(stateFile);
            } catch (IOException e) {
                logger.warn("could not delete state file {}: {}", stateFile, e.getMessage());
            }
        }

        // Load prior state if it exists — this is the cross-session bit.
        BanditStateStore store = new FileBanditStateStore(stateFile);
        try {
            bandit.restore(store.load());
        } catch (IOException e) {
            logger.warn("could not load bandit state from {}: {}", stateFile, e.getMessage());
        }

        BanditApprovalTrainer.attach(channel, bandit);

        // The promotion gate's fallback is alwaysAuto — pre-graduation, the bandit
        // doesn't block, but the trainer keeps feeding it data. Post-graduation,
        // the bandit's verdict becomes part of the AND.
        BanditPromotionGate banditGate = new BanditPromotionGate(
                bandit,
                PlanApprovalPolicy.alwaysAuto(),
                /*minObs*/ 20,
                /*maxCiHalfWidth*/ 0.12);

        PlanApprovalPolicy policy = PlanApprovalPolicy.all(
                new RiskTieredApprovalPolicy(),
                new DriftBudgetApprovalPolicy(Integer.MAX_VALUE),
                banditGate);

        MutationGuard humanDelegate = plan ->
                MutationGuard.Decision.approve("alice", "operator approved at gate");
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(humanDelegate, channel, policy);

        // Seed approval so the static RiskTier policy is satisfied; we want the
        // bandit's veto to be the interesting signal, not the seed gate.
        seedRiskTier(guard);

        // ---------- 2. Header ----------
        printHeader(stateFile, bandit);

        // ---------- 3. Drive the simulation ----------
        int fastAutoCount = 0, fastHumanCount = 0;
        int slowAutoCount = 0, slowHumanCount = 0;

        printRunHeader();
        for (int run = 1; run <= RUNS_PER_BUCKET * 2; run++) {
            boolean isFastpath = (run % 2 == 0);
            String op = isFastpath ? FASTPATH_OP : SLOWPATH_OP;
            double underlying = isFastpath ? FASTPATH_RATE : SLOWPATH_RATE;
            String bucketKey = isFastpath ? FASTPATH_KEY : SLOWPATH_KEY;

            MutationPlan plan = MutationPlan.builder()
                    .toolName(TOOL)
                    .summary("Run " + run + " — " + op)
                    .riskLevel(MutationPlan.RiskLevel.LOW)
                    .op(op, "release-" + run)
                    .metadata("apply", true)
                    .build();

            MutationGuard.Decision decision = guard.check(plan);
            String decidedBy = decision.decidedBy();
            boolean autoApproved = PlanAwareMutationGuard.DECIDED_BY_POLICY.equals(decidedBy);

            if (autoApproved) {
                if (isFastpath) fastAutoCount++; else slowAutoCount++;
            } else {
                if (isFastpath) fastHumanCount++; else slowHumanCount++;
            }

            // Simulate execution outcome with the underlying truth
            boolean success = rng.nextDouble() < underlying;
            guard.observe(decision.correlationId(), success, success ? "ok" : "io error");

            // Print a row every 5 runs + at the moment a bucket graduates
            boolean shouldPrint = run % 5 == 0
                    || run == 1
                    || (banditGate.isReady(bucketKey) &&
                        bandit.observationCount(bucketKey) ==
                                BanditPromotionGate.DEFAULT_MIN_OBSERVATIONS);
            if (shouldPrint) {
                printRunRow(run, op, decidedBy, success, banditGate, bandit);
            }
        }

        // ---------- 4. Save state for next run ----------
        try {
            store.save(bandit.snapshot());
            logger.info("saved bandit state to {}", stateFile);
        } catch (IOException e) {
            logger.warn("could not save bandit state to {}: {}", stateFile, e.getMessage());
        }

        // ---------- 5. Summary ----------
        printSummary(bandit, banditGate, stateFile,
                fastAutoCount, fastHumanCount,
                slowAutoCount, slowHumanCount);
    }

    /**
     * Drive a single MEDIUM-risk action through the human gate so the
     * RiskTieredApprovalPolicy considers itself "seeded" — that way the LOW-risk
     * actions that follow can auto-approve via the static chain. Without this
     * the bandit's veto behaviour would be hidden behind the tier rule.
     */
    private void seedRiskTier(PlanAwareMutationGuard guard) {
        MutationPlan seed = MutationPlan.builder()
                .toolName(TOOL)
                .summary("Seed action — operator approves once")
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("seed", "boot")
                .metadata("apply", true)
                .build();
        MutationGuard.Decision d = guard.check(seed);
        guard.observe(d.correlationId(), true, "seeded");
    }

    private static String opOf(PlanEntry entry) {
        Object payload = entry.payload();
        if (payload instanceof MutationPlan mp && !mp.ops().isEmpty()) {
            return mp.ops().get(0).type();
        }
        return "unknown";
    }

    private static Path stateFilePath() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".swarmai", "self-improving-plan-loop-bandit.json");
    }

    // ============== Output ==============

    private void printHeader(Path stateFile, BanditApprovalPolicy bandit) {
        int knownBuckets = bandit.bucketKeys().size();
        boolean preGraduated = knownBuckets >= 2;
        nl();
        line("======================================================================");
        line("  Self-Improving Plan Loop — closed-loop self-improvement demo");
        line("======================================================================");
        line("");
        line("  Two LOW-risk action types with very different underlying success rates:");
        line(String.format("    fastpath (%s): underlying P(success) = %.2f", FASTPATH_OP, FASTPATH_RATE));
        line(String.format("    slowpath (%s):   underlying P(success) = %.2f", SLOWPATH_OP, SLOWPATH_RATE));
        line("");
        line("  Static policy chain (RiskTier + DriftBudget) cannot tell the buckets");
        line("  apart — both are LOW-risk by classification. The bandit, attached as");
        line("  a third veto via BanditPromotionGate, learns the asymmetry from");
        line("  observed outcomes and gets to veto auto-approvals once confident.");
        line("");
        line("  Persistence: " + stateFile);
        line("    bandit state on entry: " + knownBuckets + " buckets known");
        line("");
        if (preGraduated) {
            line("  RUN MODE: continuation (prior learning carries over)");
            line("    Both buckets are already graduated from a prior run, so slowpath");
            line("    will be vetoed from row 1 — that's the durable self-improvement.");
            line("    To see the graduation ARC from scratch, re-run with --reset.");
        } else {
            line("  RUN MODE: fresh-state (showing the graduation arc from scratch)");
            line("    The bandit starts with no observations. Watch for the moment of");
            line("    self-improvement: once the slowpath bucket has >= 20 obs and CI");
            line("    half-width <= 0.12, it graduates and slowpath rows flip from");
            line("    PLAN_POLICY (auto) to alice (routed to human).");
        }
    }

    private void printRunHeader() {
        nl();
        line(String.format("%4s | %-12s | %-12s | %-10s | %-15s",
                "run", "op", "decidedBy", "outcome", "bucket state"));
        line("-----+--------------+--------------+------------+-------------------------");
    }

    private void printRunRow(int run, String op, String decidedBy, boolean success,
                              BanditPromotionGate gate, BanditApprovalPolicy bandit) {
        String bucketKey = "tool:" + TOOL + ":" + Risk.LOW + ":" + op;
        var state = bandit.bucketState(bucketKey).orElse(null);
        String bucketState = state == null
                ? "(no obs yet)"
                : String.format("mean=%.2f obs=%d %s",
                        state.meanAuto(), state.observationCount(),
                        gate.isReady(bucketKey) ? "READY" : "warming");
        line(String.format("%4d | %-12s | %-12s | %-10s | %s",
                run, op, decidedBy, success ? "success" : "FAIL",
                bucketState));
    }

    private void printSummary(BanditApprovalPolicy bandit, BanditPromotionGate gate, Path stateFile,
                               int fastAuto, int fastHuman,
                               int slowAuto, int slowHuman) {
        nl();
        line("======================================================================");
        line("  Summary");
        line("======================================================================");
        line("");
        line("  Routing tally over " + (RUNS_PER_BUCKET * 2) + " runs (" + RUNS_PER_BUCKET + " per bucket):");
        line(String.format("    fastpath:  %d auto-approved, %d routed to human",
                fastAuto, fastHuman));
        line(String.format("    slowpath:  %d auto-approved, %d routed to human",
                slowAuto, slowHuman));
        line("");
        line("  Bandit final state per bucket:");
        printBandit(bandit, gate, FASTPATH_KEY);
        printBandit(bandit, gate, SLOWPATH_KEY);
        line("");
        line("  Saved to " + stateFile);
        line("  Re-run the example: persisted state continues — the slowpath veto");
        line("  will be in effect from row 1.");
        line("");
        line("======================================================================");
        line("  What just happened — the closed loop");
        line("======================================================================");
        line("");
        line("  1. The static policy chain (RiskTier + DriftBudget) auto-approves both");
        line("     fastpath and slowpath because both classify as LOW-risk.");
        line("");
        line("  2. The bandit is attached via BanditApprovalTrainer in observer mode");
        line("     initially — it accumulates per-bucket success/failure data.");
        line("");
        line("  3. Once a bucket has ≥ 20 observations and its 95% Wald CI half-width");
        line("     is ≤ 0.12, BanditPromotionGate considers it READY and starts using");
        line("     the bandit's verdict instead of the alwaysAuto fallback.");
        line("");
        line(String.format("  4. The slowpath bucket's mean stays low (~%.2f, the underlying", SLOWPATH_RATE));
        line("     truth). When it graduates, the bandit's verdict in the all() chain");
        line("     flips slowpath rows from PLAN_POLICY → alice. The fastpath bucket");
        line(String.format("     (~%.2f) keeps auto-approving.", FASTPATH_RATE));
        line("");
        line("  5. State persists across runs. The next run starts with the buckets");
        line("     already graduated — slowpath is vetoed from row 1.");
        line("");
        line("  This is self-improvement as a system: the workflow's decisions visibly");
        line("  changed in response to observed outcomes, with no code change between");
        line("  runs, and the change is durable.");
    }

    private static void printBandit(BanditApprovalPolicy bandit, BanditPromotionGate gate, String key) {
        var state = bandit.bucketState(key).orElse(null);
        if (state == null) {
            line("    " + key + ": (no observations)");
            return;
        }
        double halfWidth = BanditPromotionGate.halfWidth(state.alphaAuto(), state.betaAuto());
        line(String.format("    %s: mean=%.3f ci½=%.3f obs=%d ready=%s",
                key, state.meanAuto(), halfWidth, state.observationCount(),
                gate.isReady(key)));
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }
}
