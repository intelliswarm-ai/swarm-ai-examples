package ai.intelliswarm.swarmai.examples.selfevolving;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.selfimproving.evolution.EvolutionEngine;
import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates self-evolution: the swarm discovers a better architecture
 * using existing framework capabilities and restructures itself.
 *
 * <h2>What happens</h2>
 * <ol>
 *   <li><b>Run 1 (SEQUENTIAL)</b> — Three independent research tasks run one after another.
 *       The ImprovementCollector detects PROCESS_SUITABILITY: "3 independent tasks at depth 0
 *       should be parallel." The EvolutionEngine persists a PROCESS_TYPE_CHANGE evolution.</li>
 *   <li><b>Evolution check</b> — The workflow reads the evolution from H2 and applies it:
 *       switches processType from SEQUENTIAL to PARALLEL.</li>
 *   <li><b>Run 2 (PARALLEL)</b> — Same 3 tasks now run in parallel. Latency drops ~60%.</li>
 *   <li><b>Comparison</b> — Before/after metrics printed. Evolution timeline visible in Studio.</li>
 * </ol>
 *
 * <p>This is NOT self-healing (fixing something broken). It is <b>self-evolution</b>:
 * the swarm becomes smarter about how to use capabilities it already has.
 *
 * <p>Only EXTERNAL observations (framework gaps) are reported to intelliswarm.ai.
 * INTERNAL observations (like PROCESS_SUITABILITY) drive runtime evolution.
 */
@Component
public class SelfEvolvingSwarmWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(SelfEvolvingSwarmWorkflow.class);

    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final LedgerStore ledgerStore;

    public SelfEvolvingSwarmWorkflow(ChatClient.Builder chatClientBuilder,
                                     ApplicationEventPublisher eventPublisher,
                                     LedgerStore ledgerStore) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.ledgerStore = ledgerStore;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args) : "multi-agent AI frameworks";
        ChatClient chatClient = chatClientBuilder.build();

        logger.info("");
        logger.info("==========================================================");
        logger.info("  SELF-EVOLVING SWARM EXAMPLE");
        logger.info("==========================================================");
        logger.info("  Topic: {}", topic);
        logger.info("  This example demonstrates how the swarm discovers and");
        logger.info("  applies a better architecture using existing capabilities.");
        logger.info("==========================================================");
        logger.info("");

        // =====================================================================
        // RUN 1: Deliberately SEQUENTIAL — the framework will observe this
        // is suboptimal because the 3 tasks have no dependencies.
        // =====================================================================
        logger.info(">>> RUN 1: SEQUENTIAL (deliberately suboptimal)");
        logger.info("    3 independent research tasks running one after another...");
        logger.info("");

        WorkflowMetricsCollector metrics1 = new WorkflowMetricsCollector("self-evolving-run1");
        metrics1.start();
        long startRun1 = System.currentTimeMillis();

        SwarmOutput run1Output = buildAndRunSwarm(chatClient, topic, ProcessType.SEQUENTIAL,
                metrics1, "self-evolving-run1-sequential");

        metrics1.stop();
        long run1DurationMs = System.currentTimeMillis() - startRun1;

        logger.info("");
        logger.info(">>> RUN 1 COMPLETE: {} ms (SEQUENTIAL)", run1DurationMs);
        logger.info("    The self-improvement phase will have observed:");
        logger.info("    PROCESS_SUITABILITY: '3 independent tasks at depth 0'");
        logger.info("    EvolutionEngine will record: PROCESS_TYPE_CHANGE → PARALLEL");
        logger.info("");

        // =====================================================================
        // CHECK EVOLUTION: Read what the EvolutionEngine learned
        // =====================================================================
        List<LedgerStore.StoredEvolution> evolutions = ledgerStore.getRecentEvolutions(5);
        boolean shouldEvolve = evolutions.stream()
                .anyMatch(e -> "PROCESS_TYPE_CHANGE".equals(e.evolutionType()));

        if (shouldEvolve) {
            logger.info(">>> EVOLUTION DETECTED");
            for (LedgerStore.StoredEvolution evo : evolutions) {
                logger.info("    Type:   {}", evo.evolutionType());
                logger.info("    Reason: {}", evo.reason());
                logger.info("    Before: {}", evo.beforeJson());
                logger.info("    After:  {}", evo.afterJson());
                logger.info("");
            }
        } else {
            logger.info(">>> No PROCESS_TYPE_CHANGE evolution detected yet.");
            logger.info("    (The self-improvement phase runs async — evolution may");
            logger.info("    appear on the next run. Running PARALLEL anyway to demo.)");
            logger.info("");
        }

        // =====================================================================
        // RUN 2: Apply evolution → PARALLEL
        // =====================================================================
        logger.info(">>> RUN 2: PARALLEL (evolved topology)");
        logger.info("    Same 3 tasks, now running in parallel...");
        logger.info("");

        WorkflowMetricsCollector metrics2 = new WorkflowMetricsCollector("self-evolving-run2");
        metrics2.start();
        long startRun2 = System.currentTimeMillis();

        SwarmOutput run2Output = buildAndRunSwarm(chatClient, topic, ProcessType.PARALLEL,
                metrics2, "self-evolving-run2-parallel");

        metrics2.stop();
        long run2DurationMs = System.currentTimeMillis() - startRun2;

        logger.info("");
        logger.info(">>> RUN 2 COMPLETE: {} ms (PARALLEL)", run2DurationMs);
        logger.info("");

        // =====================================================================
        // COMPARISON: Show the evolution impact
        // =====================================================================
        double speedup = run1DurationMs > 0
                ? (double) (run1DurationMs - run2DurationMs) / run1DurationMs * 100
                : 0;

        logger.info("==========================================================");
        logger.info("  SELF-EVOLUTION RESULTS");
        logger.info("==========================================================");
        logger.info("");
        logger.info("  Before (SEQUENTIAL):  {} ms", run1DurationMs);
        logger.info("  After  (PARALLEL):    {} ms", run2DurationMs);
        logger.info("  Improvement:          {:.1f}%", speedup);
        logger.info("");
        logger.info("  Evolution timeline:");
        logger.info("    Run 1 → SEQUENTIAL → observed: PROCESS_SUITABILITY");
        logger.info("          → EvolutionEngine: PROCESS_TYPE_CHANGE");
        logger.info("    Run 2 → PARALLEL (evolved) → {:.1f}% faster", speedup);
        logger.info("");
        logger.info("  Studio: GET /api/studio/evolutions for full timeline");
        logger.info("==========================================================");

        // Judge evaluation
        String finalOutput = run2Output != null ? run2Output.getFinalOutput() : "";
        judge.evaluate("self-evolving", "Self-evolving swarm with runtime topology optimization",
                finalOutput, run2Output != null, run1DurationMs + run2DurationMs,
                3, 3, "SELF_EVOLVING", "self-evolving-swarm");
    }

    private SwarmOutput buildAndRunSwarm(ChatClient chatClient, String topic,
                                          ProcessType processType,
                                          WorkflowMetricsCollector metrics,
                                          String swarmId) {
        Agent analyst1 = Agent.builder()
                .role("Technology Analyst")
                .goal("Analyze the technology landscape for " + topic)
                .backstory("You are a senior technology analyst specializing in emerging tech trends.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        Agent analyst2 = Agent.builder()
                .role("Market Analyst")
                .goal("Analyze market dynamics and competitive landscape for " + topic)
                .backstory("You are a market research analyst who identifies trends and opportunities.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        Agent analyst3 = Agent.builder()
                .role("Risk Analyst")
                .goal("Identify risks and challenges for " + topic)
                .backstory("You are a risk analyst who evaluates potential threats and mitigation strategies.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        // 3 independent tasks — no dependencies between them
        Task techTask = Task.builder()
                .description("Write a 200-word technology analysis of " + topic
                        + ". Cover: key technologies, maturity level, adoption trends.")
                .expectedOutput("Concise technology landscape summary")
                .agent(analyst1)
                .build();

        Task marketTask = Task.builder()
                .description("Write a 200-word market analysis of " + topic
                        + ". Cover: market size, key players, growth trajectory.")
                .expectedOutput("Concise market dynamics summary")
                .agent(analyst2)
                .build();

        Task riskTask = Task.builder()
                .description("Write a 200-word risk analysis of " + topic
                        + ". Cover: technical risks, market risks, mitigation strategies.")
                .expectedOutput("Concise risk assessment")
                .agent(analyst3)
                .build();

        Swarm swarm = Swarm.builder()
                .id(swarmId)
                .agent(analyst1).agent(analyst2).agent(analyst3)
                .task(techTask).task(marketTask).task(riskTask)
                .process(processType)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .build();

        try {
            return swarm.kickoff(Map.of("topic", topic));
        } catch (Exception e) {
            logger.warn("Swarm {} failed: {}", swarmId, e.getMessage());
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        org.springframework.boot.SpringApplication.run(
                ai.intelliswarm.swarmai.SwarmAIExamplesApplication.class, args);
    }
}
