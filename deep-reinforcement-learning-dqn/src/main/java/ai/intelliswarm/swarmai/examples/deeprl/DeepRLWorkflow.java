/*
 * SwarmAI Framework
 * Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 *
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai.examples.deeprl;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.rl.bandit.LinUCBBandit;
import ai.intelliswarm.swarmai.rl.bandit.ThompsonSampling;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Reinforcement Learning Workflow Example (Bandit-based)
 *
 * <p>Demonstrates contextual and non-contextual bandits guiding a SELF_IMPROVING workflow's
 * decisions across multiple runs. This example replaces the previous DQN-based version —
 * the DeepRLPolicy / DQNNetwork classes were removed from the core framework, so the example
 * was rewritten using the lightweight, dependency-free bandit algorithms that ship with
 * {@code swarmai-core}.
 *
 * <h2>Two policies collaborate per iteration</h2>
 * <ol>
 *   <li><b>{@link LinUCBBandit} — contextual bandit</b> over skill-generation actions.
 *       Given an 8-dim state vector describing the current iteration (stale-count,
 *       gap severity, tokens used, etc.), it picks one of:
 *       {@code GENERATE}, {@code SKIP}, {@code REUSE_SKILL}, {@code ESCALATE}.</li>
 *   <li><b>{@link ThompsonSampling} — Bayesian bandit</b> for convergence detection.
 *       Learns whether to CONTINUE or STOP the self-improving loop, adapting to the
 *       domain after a handful of runs without hardcoded thresholds.</li>
 * </ol>
 *
 * <h2>How it gets smarter across runs</h2>
 * <pre>
 *   Run 1:   Cold start (uniform priors)        → baseline behavior
 *   Run 5:   Bandits have observations          → starts exploiting patterns
 *   Run 20+: Stable policies per context         → consistent high-quality decisions
 * </pre>
 *
 * <p>Unlike the old DQN-based version, these bandits:
 * <ul>
 *   <li>Run in plain Java (no tensor libraries, no GPU)</li>
 *   <li>Learn online in a few dozen observations rather than thousands</li>
 *   <li>Are interpretable — you can inspect per-action counts and mean rewards</li>
 * </ul>
 *
 * <h2>Three-Tier Policy Architecture</h2>
 * <pre>
 *   ┌─────────────────────────────────────────────────┐
 *   │ Tier 2: Bandit policies  (swarmai-core)         │  LinUCB + Thompson Sampling
 *   │   └─ Contextual + Bayesian, online learning     │  No external deps
 *   ├─────────────────────────────────────────────────┤
 *   │ Tier 1: HeuristicPolicy  (swarmai-core)         │  Hardcoded thresholds
 *   │   └─ score &gt;= 0.60 → GENERATE                    │  Zero learning
 *   └─────────────────────────────────────────────────┘
 * </pre>
 */
@Component
public class DeepRLWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(DeepRLWorkflow.class);

    // Skill-generation actions (must match the dimension passed to LinUCBBandit)
    private static final int ACTION_GENERATE = 0;
    private static final int ACTION_SKIP = 1;
    private static final int ACTION_REUSE = 2;
    private static final int ACTION_ESCALATE = 3;
    private static final String[] ACTION_NAMES = {"GENERATE", "SKIP", "REUSE", "ESCALATE"};

    // Convergence actions for Thompson sampling
    private static final int CONVERGE_CONTINUE = 0;
    private static final int CONVERGE_STOP = 1;
    private static final String[] CONVERGE_NAMES = {"CONTINUE", "STOP"};

    private static final int STATE_DIM = 8;       // 8-dim context vector
    private static final double EXPLORATION_ALPHA = 1.0;

    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public DeepRLWorkflow(ChatClient.Builder chatClientBuilder,
                          ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Runs a SELF_IMPROVING workflow guided by bandit policies.
     * Each run feeds reward signals back to the bandits; subsequent runs make
     * increasingly consistent decisions.
     *
     * @param topic the research topic
     * @param runs  number of sequential runs (more runs = stabler policy)
     */
    public void run(String topic, int runs) {
        long startMs = System.currentTimeMillis();

        LinUCBBandit skillBandit = new LinUCBBandit(ACTION_NAMES.length, STATE_DIM, EXPLORATION_ALPHA);
        ThompsonSampling convergenceBandit = new ThompsonSampling(CONVERGE_NAMES.length);

        ChatClient chatClient = chatClientBuilder.build();

        Agent analyst = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Analyze " + topic + " using available tools and generated skills")
                .backstory("Expert analyst who adapts approach based on available capabilities.")
                .chatClient(chatClient)
                .maxTurns(3)
                .build();

        Agent reviewer = Agent.builder()
                .role("Quality Reviewer")
                .goal("Review analysis quality and identify capability gaps")
                .backstory("Strict reviewer who identifies missing capabilities precisely.")
                .chatClient(chatClient)
                .build();

        int successfulRuns = 0;
        int totalIterations = 0;

        for (int i = 1; i <= runs; i++) {
            logger.info("=== Bandit RL Run {}/{} ===", i, runs);

            // Build a synthetic state vector for the bandit; in a real integration
            // this would come from the framework's IterationContext.
            double[] state = buildStateVector(i, runs);

            // Tier 2: contextual bandit recommends a skill-generation action
            int skillAction = skillBandit.selectAction(state);
            logger.info("Policy [LinUCB]: recommended action={} (count so far={})",
                    ACTION_NAMES[skillAction], skillBandit.getActionCount(skillAction));

            // Tier 2: Thompson sampling recommends continue vs stop
            int convergeAction = convergenceBandit.selectAction();
            logger.info("Policy [Thompson]: convergence recommendation={} (mean={})",
                    CONVERGE_NAMES[convergeAction],
                    String.format(Locale.US, "%.3f", convergenceBandit.getMean(convergeAction)));

            Task analyzeTask = Task.builder()
                    .description("Analyze " + topic + " thoroughly. "
                            + "Recommended policy action: " + ACTION_NAMES[skillAction] + ". "
                            + "Use all available tools and skills.")
                    .expectedOutput("Comprehensive analysis report")
                    .agent(analyst)
                    .build();

            Swarm swarm = Swarm.builder()
                    .agent(analyst)
                    .agent(reviewer)
                    .task(analyzeTask)
                    .process(ProcessType.SELF_IMPROVING)
                    .managerAgent(reviewer)
                    .config("maxIterations", 3)
                    .eventPublisher(eventPublisher)
                    .build();

            try {
                SwarmOutput output = swarm.kickoff(Map.of("topic", topic));
                int skillsGenerated = toInt(output.getMetadata().getOrDefault("skillsGenerated", 0));
                int skillsReused = toInt(output.getMetadata().getOrDefault("skillsReused", 0));
                int iterations = toInt(output.getMetadata().getOrDefault("totalIterations", 1));
                totalIterations += iterations;

                double reward = computeReward(output.isSuccessful(), skillsGenerated, skillsReused, iterations);
                skillBandit.update(state, skillAction, reward);
                convergenceBandit.update(convergeAction, output.isSuccessful());

                if (output.isSuccessful()) successfulRuns++;
                logger.info("Run {} completed: successful={}, skillsGenerated={}, skillsReused={}, "
                                + "iterations={}, reward={}",
                        i, output.isSuccessful(), skillsGenerated, skillsReused, iterations,
                        String.format(Locale.US, "%.3f", reward));
            } catch (Exception e) {
                logger.warn("Run {} failed: {}", i, e.getMessage());
                // Record the failure so the bandits learn to avoid this action in this state
                skillBandit.update(state, skillAction, -1.0);
                convergenceBandit.update(convergeAction, false);
            }
        }

        logger.info("=== Bandit RL Training Summary ===");
        logger.info("Runs successful: {}/{}", successfulRuns, runs);
        logger.info("Total SELF_IMPROVING iterations: {}", totalIterations);
        logger.info("LinUCB total updates: {}", skillBandit.getTotalUpdates());
        for (int a = 0; a < ACTION_NAMES.length; a++) {
            logger.info("  action {} → count {}", ACTION_NAMES[a], skillBandit.getActionCount(a));
        }
        for (int a = 0; a < CONVERGE_NAMES.length; a++) {
            logger.info("  Thompson {} → mean {} (n={})",
                    CONVERGE_NAMES[a],
                    String.format(Locale.US, "%.3f", convergenceBandit.getMean(a)),
                    (int) convergenceBandit.getCount(a));
        }

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("deep-rl",
                    "Bandit-based reinforcement learning (LinUCB + Thompson Sampling) "
                            + "guiding self-improving workflow decisions",
                    "Completed " + runs + " runs, " + successfulRuns + " successful, "
                            + totalIterations + " total iterations.",
                    successfulRuns > 0, System.currentTimeMillis() - startMs,
                    3, 2, "SELF_IMPROVING", "deep-reinforcement-learning-dqn");
        }
    }

    /** Build an 8-dim synthetic state vector for the contextual bandit. */
    private double[] buildStateVector(int runIndex, int totalRuns) {
        double progress = (double) runIndex / Math.max(1, totalRuns);
        return new double[] {
                1.0,                // bias term
                progress,           // run progress
                Math.sin(runIndex), // periodic feature
                Math.log(runIndex + 1.0) / 10.0,
                progress * progress,
                1.0 - progress,
                runIndex % 2 == 0 ? 1.0 : 0.0,
                Math.min(1.0, runIndex / 20.0)
        };
    }

    /**
     * Compose a reward in [-1, +1] from workflow metadata.
     * Bigger reward for success + effective skill reuse; penalize excessive iterations.
     */
    private double computeReward(boolean successful, int skillsGenerated, int skillsReused, int iterations) {
        if (!successful) return -0.5;
        double base = 0.5;
        base += Math.min(0.3, skillsReused * 0.1);         // reuse is efficient
        base -= Math.min(0.3, Math.max(0, skillsGenerated - 2) * 0.05); // penalize skill bloat
        base -= Math.min(0.3, Math.max(0, iterations - 3) * 0.1);      // penalize long loops
        return Math.max(-1.0, Math.min(1.0, base));
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }
}
