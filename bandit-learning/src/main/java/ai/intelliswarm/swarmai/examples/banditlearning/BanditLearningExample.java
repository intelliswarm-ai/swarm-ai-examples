package ai.intelliswarm.swarmai.examples.banditlearning;

import ai.intelliswarm.swarmai.state.plan.BanditApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.BanditPromotionGate;
import ai.intelliswarm.swarmai.state.plan.BanditState;
import ai.intelliswarm.swarmai.state.plan.BanditStateStore;
import ai.intelliswarm.swarmai.state.plan.EvolvingPlan;
import ai.intelliswarm.swarmai.state.plan.FileBanditStateStore;
import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.Risk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Visible demonstration that the bandit + promotion gate actually learn.
 *
 * <p>Drives a deterministic 600-iteration simulated workload across two
 * buckets with known underlying success rates (0.9 "good" and 0.1 "bad").
 * Prints a learning curve table — the bandit's mean estimate per bucket,
 * the 95% CI half-width, the gate's verdict, and the empirical rate at
 * which the gate auto-approves a probe entry from each bucket.
 *
 * <p>Mid-run, snapshots the bandit's state via {@link BanditState} and
 * restores it into a fresh policy, demonstrating that learned state can be
 * persisted and round-tripped — the foundation for cross-session memory.
 *
 * <p>Why deterministic simulation? Real LLM-driven runs would mix the
 * bandit's slow-learning signal with LLM noise. A controlled simulation
 * with known truth makes the learning behaviour visible — the same
 * convergence is what you'd see in production over hundreds of real
 * approvals, just stretched out in time.
 *
 * <p>Run:
 * <pre>
 *   ./bandit-learning/run.sh
 *   # or
 *   ./run.sh bandit-learning
 * </pre>
 *
 * <p>No LLM provider needed — this example does not call ChatClient.
 */
@Component
public class BanditLearningExample {

    private static final Logger logger = LoggerFactory.getLogger(BanditLearningExample.class);

    private static final double GOOD_RATE = 0.9;
    private static final double BAD_RATE = 0.1;
    private static final int TOTAL_ITERATIONS = 600;
    private static final int SNAPSHOT_AT = 300;
    private static final int PRINT_EVERY = 50;
    private static final long SEED = 42L;

    private static final String GOOD_KEY = "tool:deploy:" + Risk.LOW;
    private static final String BAD_KEY = "tool:deploy:" + Risk.HIGH;

    public void run(String[] args) {
        Random rng = new Random(SEED);

        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditPromotionGate gate = new BanditPromotionGate(
                bandit,
                PlanApprovalPolicy.alwaysHuman(),
                /*minObs*/ 30,
                /*maxCiHalfWidth*/ 0.10);

        printHeader();

        for (int i = 1; i <= TOTAL_ITERATIONS; i++) {
            // Alternate buckets so both grow at similar rates.
            boolean isGoodBucket = (i % 2 == 0);
            double underlying = isGoodBucket ? GOOD_RATE : BAD_RATE;
            Risk risk = isGoodBucket ? Risk.LOW : Risk.HIGH;

            double draw = rng.nextDouble();
            double reward = draw < underlying ? 1.0 : -1.0;

            PlanEntry e = PlanEntry.builder()
                    .id("iter-" + i)
                    .proposedBy("tool:deploy")
                    .payload("op-" + i)
                    .risk(risk)
                    .build();
            bandit.recordOutcome(e, reward);

            if (i % PRINT_EVERY == 0) {
                printRow(i, bandit, gate, rng);
            }

            // Demonstrate state persistence at the halfway point.
            if (i == SNAPSHOT_AT) {
                bandit = demonstrateSnapshotRestore(bandit, gate, rng);
                // Replace the gate's reference too — the gate holds a final
                // reference to the original bandit, so we rebuild it to point
                // at the restored policy.
                gate = new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman(),
                        /*minObs*/ 30, /*maxCiHalfWidth*/ 0.10);
            }
        }

        printFooter(bandit, gate);

        // Bonus: demonstrate cross-session persistence via FileBanditStateStore.
        // Saves to a temp file, builds a fresh policy, loads from disk.
        try {
            demonstrateFilePersistence(bandit);
        } catch (IOException e) {
            line("File persistence demo failed: " + e.getMessage());
        }

        printApplicationGuide();
    }

    /**
     * Save the bandit's state to a JSON file, build a fresh policy, load
     * the JSON back. The simulated equivalent of restarting the process —
     * proves cross-session persistence works end-to-end.
     */
    private void demonstrateFilePersistence(BanditApprovalPolicy bandit) throws IOException {
        nl();
        line("======================================================================");
        line("  Cross-session persistence demo (FileBanditStateStore)");
        line("======================================================================");

        Path tmp = Files.createTempDirectory("bandit-learning-demo");
        Path stateFile = tmp.resolve("bandit-state.json");
        BanditStateStore store = new FileBanditStateStore(stateFile);

        line("  Saving snapshot to: " + stateFile);
        store.save(bandit.snapshot());
        line("  File size: " + Files.size(stateFile) + " bytes");
        nl();

        line("  Simulating a process restart…");
        line("  Building a brand-new BanditApprovalPolicy (zero prior knowledge):");
        BanditApprovalPolicy reborn = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
        line("    fresh.bucketKeys() = " + reborn.bucketKeys());
        nl();

        line("  Loading state from disk and restoring…");
        BanditState loaded = store.load();
        reborn.restore(loaded);
        line("    reborn.bucketKeys() = " + reborn.bucketKeys());
        line(String.format("    good bucket — original mean=%.3f, reborn mean=%.3f",
                bandit.meanSuccessRate(GOOD_KEY),
                reborn.meanSuccessRate(GOOD_KEY)));
        line(String.format("    bad  bucket — original mean=%.3f, reborn mean=%.3f",
                bandit.meanSuccessRate(BAD_KEY),
                reborn.meanSuccessRate(BAD_KEY)));
        nl();

        line("  This is what makes the bandit's learning durable: snapshot at shutdown,");
        line("  restore at startup. Every observation across the application's lifetime");
        line("  contributes to the same model.");

        // Best-effort cleanup
        try {
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // OS will clean up the temp dir eventually
        }
    }

    /**
     * Snapshot the bandit's per-bucket state, build a fresh policy, restore
     * the snapshot into it. Demonstrates that the entire learned state is
     * portable as a serializable {@link BanditState}.
     */
    private BanditApprovalPolicy demonstrateSnapshotRestore(
            BanditApprovalPolicy original,
            BanditPromotionGate gate,
            Random rng) {
        nl();
        line("------------------------------------------------------------------------");
        line("  Snapshot/Restore demo at iteration " + SNAPSHOT_AT);
        line("------------------------------------------------------------------------");
        BanditState snapshot = original.snapshot();
        line("  buckets in snapshot:    " + snapshot.bucketCount());
        for (var entry : snapshot.buckets().entrySet()) {
            BanditState.BucketState s = entry.getValue();
            line(String.format("    %-30s  α_auto=%.2f β_auto=%.2f obs=%d  mean=%.3f",
                    entry.getKey(),
                    s.alphaAuto(), s.betaAuto(),
                    s.observationCount(), s.meanAuto()));
        }
        nl();
        line("  Discarding original bandit; building a fresh BanditApprovalPolicy…");
        BanditApprovalPolicy restored = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
        line("  Fresh bandit knows " + restored.bucketKeys().size() + " buckets (expected 0).");

        restored.restore(snapshot);
        line("  After restore: " + restored.bucketKeys().size() + " buckets recovered.");
        line(String.format("  good bucket — original mean=%.3f, restored mean=%.3f",
                original.meanSuccessRate(GOOD_KEY),
                restored.meanSuccessRate(GOOD_KEY)));
        line(String.format("  bad  bucket — original mean=%.3f, restored mean=%.3f",
                original.meanSuccessRate(BAD_KEY),
                restored.meanSuccessRate(BAD_KEY)));
        nl();
        line("  Continuing simulation against the RESTORED bandit — the curve should be");
        line("  indistinguishable from continuing on the original.");
        line("------------------------------------------------------------------------");
        nl();
        return restored;
    }

    // ============== Output ==============

    private static void printHeader() {
        nl();
        line("======================================================================");
        line("  Bandit Learning Curve — 600 iterations, two buckets");
        line("======================================================================");
        line("  good bucket = " + GOOD_KEY + " (underlying P(success) = " + GOOD_RATE + ")");
        line("  bad  bucket = " + BAD_KEY + "  (underlying P(success) = " + BAD_RATE + ")");
        line("  Gate config: minObs=30, maxCiHalfWidth=0.10 (95% Wald)");
        line("  Snapshot/restore demo at iteration " + SNAPSHOT_AT);
        nl();
        line(String.format("%5s | %s | %s",
                "iter",
                "good: mean   ci½    obs  ready  auto%/100",
                "bad:  mean   ci½    obs  ready  auto%/100"));
        line("------+-------------------------------------------+-------------------------------------------");
    }

    private static void printRow(int iter,
                                  BanditApprovalPolicy bandit,
                                  BanditPromotionGate gate,
                                  Random rng) {
        var goodState = bandit.bucketState(GOOD_KEY).orElse(null);
        var badState = bandit.bucketState(BAD_KEY).orElse(null);
        line(String.format("%5d | %s | %s",
                iter,
                formatBucket(goodState, gate, GOOD_KEY, Risk.LOW, rng),
                formatBucket(badState, gate, BAD_KEY, Risk.HIGH, rng)));
    }

    private static String formatBucket(BanditState.BucketState state,
                                        BanditPromotionGate gate,
                                        String key,
                                        Risk risk,
                                        Random rng) {
        if (state == null) return "  -";
        double halfWidth = BanditPromotionGate.halfWidth(state.alphaAuto(), state.betaAuto());
        boolean ready = gate.isReady(key);
        int autos = countAutoApproves(gate, risk, 100, rng);
        return String.format(" %.3f  %.3f  %5d  %-5s  %3d",
                state.meanAuto(), halfWidth, state.observationCount(),
                ready ? "yes" : "no", autos);
    }

    private static int countAutoApproves(BanditPromotionGate gate, Risk risk, int trials, Random rng) {
        int autos = 0;
        for (int i = 0; i < trials; i++) {
            PlanEntry probe = PlanEntry.builder()
                    .id("probe-" + rng.nextLong())
                    .proposedBy("tool:deploy")
                    .payload("probe")
                    .risk(risk)
                    .build();
            if (gate.decide(EvolvingPlan.empty(), probe) == PlanApprovalPolicy.Decision.AUTO_APPROVE) {
                autos++;
            }
        }
        return autos;
    }

    private static void printFooter(BanditApprovalPolicy bandit, BanditPromotionGate gate) {
        nl();
        line("Final state:");
        var goodState = bandit.bucketState(GOOD_KEY).orElseThrow();
        var badState = bandit.bucketState(BAD_KEY).orElseThrow();
        line(String.format("  good bucket: mean=%.3f  ci½=%.3f  obs=%d  ready=%s  (truth=%.1f)",
                goodState.meanAuto(),
                BanditPromotionGate.halfWidth(goodState.alphaAuto(), goodState.betaAuto()),
                goodState.observationCount(),
                gate.isReady(GOOD_KEY),
                GOOD_RATE));
        line(String.format("  bad  bucket: mean=%.3f  ci½=%.3f  obs=%d  ready=%s  (truth=%.1f)",
                badState.meanAuto(),
                BanditPromotionGate.halfWidth(badState.alphaAuto(), badState.betaAuto()),
                badState.observationCount(),
                gate.isReady(BAD_KEY),
                BAD_RATE));
        nl();
        line("Promoted buckets: " + gate.readyBuckets());
    }

    private static void printApplicationGuide() {
        nl();
        line("======================================================================");
        line("  How to apply this in a real workflow");
        line("======================================================================");
        line("");
        line("  1. Compose the gate into your PlanApprovalPolicy chain:");
        line("");
        line("       BanditApprovalPolicy bandit = new BanditApprovalPolicy();");
        line("       BanditApprovalTrainer.attach(channel, bandit);  // observer mode");
        line("");
        line("       BanditPromotionGate gate = new BanditPromotionGate(");
        line("           bandit,");
        line("           PlanApprovalPolicy.alwaysHuman(),  // fallback for unready buckets");
        line("           /*minObs*/ 30,");
        line("           /*maxCiHalfWidth*/ 0.10);");
        line("");
        line("       PlanApprovalPolicy policy = PlanApprovalPolicy.any(");
        line("           PlanApprovalPolicy.all(tier, budget),  // static rules first");
        line("           gate);                                  // gated bandit can bypass");
        line("");
        line("  2. Persist learning across restarts via BanditStateStore:");
        line("");
        line("       BanditStateStore store = new FileBanditStateStore(Path.of(\"data/bandit.json\"));");
        line("       bandit.restore(store.load());        // on startup");
        line("       // … run the workflow …");
        line("       store.save(bandit.snapshot());       // on shutdown / interval");
        line("");
        line("  3. Promote a single bucket without flipping the global policy:");
        line("       gate.readyBuckets() returns the set currently confident enough.");
        line("");
        nl();
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }
}
