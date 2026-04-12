/*
 * SwarmAI Framework
 * Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 *
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai.examples.deeprl;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Self-Improving Workflow Example
 *
 * Demonstrates the SELF_IMPROVING process using the built-in adaptive bandit policies
 * (for example LinUCB / Thompson sampling) that learn to make smarter decisions over
 * multiple workflow runs:
 *
 * <h2>What Adaptive Bandit Policies Add</h2>
 * <ul>
 *   <li><b>Skill generation decisions</b> — Contextual bandits learn which capability
 *       gaps are worth generating skills for (GENERATE vs SKIP), reducing wasteful skill
 *       generation compared to static heuristics.</li>
 *   <li><b>Convergence detection</b> — The framework adapts iteration behavior based on
 *       observed outcomes rather than using only hardcoded cutoffs.</li>
 *   <li><b>Online exploration/exploitation</b> — Early runs explore more strategies, then
 *       gradually exploit high-performing actions.</li>
 *   <li><b>Lightweight learning</b> — No enterprise-only deep-RL classes are required in
 *       this example.</li>
 * </ul>
 *
 * <h2>How It Gets Smarter</h2>
 * <pre>
 *   Run 1:   Early exploration                 → baseline quality
 *   Run 10:  Learning begins                   → fewer wasteful skills
 *   Run 50:  Exploitation favored              → improved token efficiency
 *   Run 100: Stable high-performing decisions  → better skill reuse
 * </pre>
 *
 * <h2>Policy Architecture</h2>
 * <pre>
 *   ┌─────────────────────────────────────────────────┐
 *   │ Tier 2: LearningPolicy  (swarmai-core)          │  Lightweight bandits
 *   │   └─ LinUCB + Thompson Sampling                  │  No enterprise-only deps
 *   ├─────────────────────────────────────────────────┤
 *   │ Tier 1: HeuristicPolicy  (swarmai-core)         │  Hardcoded thresholds
 *   │   └─ score ≥ 0.60 → GENERATE                    │  Zero learning
 *   └─────────────────────────────────────────────────┘
 * </pre>
 */
@Component
public class DeepRLWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(DeepRLWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public DeepRLWorkflow(ChatClient.Builder chatClientBuilder,
                          ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Runs a SELF_IMPROVING workflow with adaptive decision making.
     * Each run provides feedback for subsequent runs to improve decisions.
     *
     * @param topic the research topic
     * @param runs  number of sequential runs (more runs = smarter policy)
     */
    public void run(String topic, int runs) {
        ChatClient chatClient = chatClientBuilder.build();

        // Define agents
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

        // Run multiple times — the default learning policy adapts across runs
        for (int i = 1; i <= runs; i++) {
            logger.info("=== Adaptive Policy Run {}/{} ===", i, runs);
            Task analyzeTask = Task.builder()
                    .description("Analyze " + topic + " thoroughly. Use all available tools and skills.")
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
                logger.info("Run {} completed: successful={}, skills generated={}, skills reused={}",
                        i, output.isSuccessful(),
                        output.getMetadata().getOrDefault("skillsGenerated", 0),
                        output.getMetadata().getOrDefault("skillsReused", 0));
            } catch (Exception e) {
                logger.warn("Run {} failed: {}", i, e.getMessage());
            }
        }

        logger.info("=== Adaptive Policy Summary ===");
        logger.info("Completed {} run(s) with framework-managed learning policy.", runs);
    }
}
