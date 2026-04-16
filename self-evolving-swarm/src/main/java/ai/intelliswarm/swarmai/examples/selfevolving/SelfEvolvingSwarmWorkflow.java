package ai.intelliswarm.swarmai.examples.selfevolving;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
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
 * Demonstrates self-evolution: the swarm reads its own evolution history
 * and applies a better architecture <b>before executing</b>.
 *
 * <h2>How it works</h2>
 * <p>Each run is a single {@code kickoff()} — no iteration loop, no explicit
 * "Run 1 then Run 2". The intelligence accumulates across separate executions
 * because evolutions are persisted to H2.</p>
 *
 * <ol>
 *   <li><b>First execution</b> — No evolution history in H2. The workflow starts as
 *       SEQUENTIAL (default). After completion, the ImprovementCollector detects
 *       PROCESS_SUITABILITY ("3 independent tasks at depth 0"), and the
 *       EvolutionEngine persists a PROCESS_TYPE_CHANGE to H2.</li>
 *   <li><b>Second execution</b> — The workflow reads H2 and finds the evolution.
 *       It applies PARALLEL before kickoff. Tasks run concurrently, ~40-60% faster.
 *       The evolution is logged and visible in Studio.</li>
 *   <li><b>Subsequent executions</b> — PARALLEL is the learned default. The swarm
 *       continues to observe and may discover further optimizations.</li>
 * </ol>
 *
 * <p>This is <b>self-evolution</b>, not self-healing: the swarm becomes smarter
 * about how to use capabilities it already has. Only EXTERNAL observations
 * (framework gaps) are reported to intelliswarm.ai/contribute. INTERNAL
 * observations like PROCESS_SUITABILITY drive runtime evolution.</p>
 *
 * <p>Run it twice to see the evolution in action:
 * <pre>
 *   ./self-evolving-swarm/run.sh "AI orchestration"   # Run 1: SEQUENTIAL
 *   ./self-evolving-swarm/run.sh "AI orchestration"   # Run 2: PARALLEL (evolved)
 * </pre>
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

        // =====================================================================
        // STEP 1: Consult evolution history — has a prior run learned something?
        // =====================================================================
        ProcessType resolvedProcess = ProcessType.SEQUENTIAL; // default
        String evolutionSource = "default (SEQUENTIAL)";

        List<LedgerStore.StoredEvolution> evolutions = ledgerStore.getRecentEvolutions(10);
        for (LedgerStore.StoredEvolution evo : evolutions) {
            if ("PROCESS_TYPE_CHANGE".equals(evo.evolutionType())) {
                resolvedProcess = ProcessType.PARALLEL;
                evolutionSource = "evolved → PARALLEL (learned from prior run: " + evo.reason() + ")";
                break;
            }
        }

        boolean isEvolved = resolvedProcess == ProcessType.PARALLEL;
        int runNumber = evolutions.isEmpty() ? 1 : evolutions.size() + 1;

        logger.info("");
        logger.info("==========================================================");
        logger.info("  SELF-EVOLVING SWARM — Run #{}", runNumber);
        logger.info("==========================================================");
        logger.info("  Topic:     {}", topic);
        logger.info("  Process:   {} ({})", resolvedProcess, evolutionSource);
        if (isEvolved) {
            logger.info("  Status:    EVOLVED — applying learned optimization");
        } else {
            logger.info("  Status:    INITIAL — will observe and learn for next run");
        }
        logger.info("  Evolutions in H2: {}", evolutions.size());
        logger.info("==========================================================");
        logger.info("");

        // Show evolution history if any
        if (!evolutions.isEmpty()) {
            logger.info("  Evolution history (from H2):");
            for (LedgerStore.StoredEvolution evo : evolutions) {
                logger.info("    [{}] {} — {}",
                        evo.createdAt().toString().substring(0, 19),
                        evo.evolutionType(), evo.reason());
            }
            logger.info("");
        }

        // =====================================================================
        // STEP 2: Build and run the swarm with the resolved topology
        // =====================================================================
        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("self-evolving");
        metrics.start();
        long startMs = System.currentTimeMillis();

        Agent analyst1 = Agent.builder()
                .role("Technology Analyst")
                .goal("Analyze the technology landscape for " + topic)
                .backstory("Senior technology analyst specializing in emerging tech trends.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        Agent analyst2 = Agent.builder()
                .role("Market Analyst")
                .goal("Analyze market dynamics and competitive landscape for " + topic)
                .backstory("Market research analyst who identifies trends and opportunities.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        Agent analyst3 = Agent.builder()
                .role("Risk Analyst")
                .goal("Identify risks and challenges for " + topic)
                .backstory("Risk analyst who evaluates potential threats and mitigation strategies.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        // 3 independent tasks — no dependencies
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
                .id("self-evolving-" + resolvedProcess.name().toLowerCase())
                .agent(analyst1).agent(analyst2).agent(analyst3)
                .task(techTask).task(marketTask).task(riskTask)
                .process(resolvedProcess)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .build();

        SwarmOutput output = null;
        try {
            output = swarm.kickoff(Map.of("topic", topic));
        } catch (Exception e) {
            logger.warn("Swarm failed: {}", e.getMessage());
        }

        metrics.stop();
        long durationMs = System.currentTimeMillis() - startMs;

        // =====================================================================
        // STEP 3: Report results
        // =====================================================================
        logger.info("");
        logger.info("==========================================================");
        logger.info("  SELF-EVOLVING SWARM — Run #{} Complete", runNumber);
        logger.info("==========================================================");
        logger.info("  Process:   {}", resolvedProcess);
        logger.info("  Duration:  {} ms", durationMs);

        if (isEvolved) {
            logger.info("  Status:    EVOLVED RUN — topology was optimized from prior learning");
            logger.info("");
            logger.info("  The swarm applied PARALLEL execution because a prior run");
            logger.info("  observed that all 3 tasks are independent (depth 0).");
            logger.info("  This optimization was persisted in H2 and applied automatically.");
        } else {
            logger.info("  Status:    INITIAL RUN — observations collected for next run");
            logger.info("");
            logger.info("  The self-improvement phase (async) will detect:");
            logger.info("    PROCESS_SUITABILITY: '3 independent tasks at depth 0'");
            logger.info("  And persist a PROCESS_TYPE_CHANGE evolution to H2.");
            logger.info("");
            logger.info("  >>> Run this example again to see the evolution applied:");
            logger.info("      ./self-evolving-swarm/run.sh \"{}\"", topic);
        }

        logger.info("");
        logger.info("  Studio: GET /api/studio/evolutions for evolution timeline");
        logger.info("==========================================================");

        // Judge evaluation
        String finalOutput = output != null ? output.getFinalOutput() : "";
        judge.evaluate("self-evolving", "Self-evolving swarm with runtime topology optimization",
                finalOutput, output != null, durationMs,
                3, 3, "SELF_EVOLVING", "self-evolving-swarm");
    }

    public static void main(String[] args) throws Exception {
        org.springframework.boot.SpringApplication.run(
                ai.intelliswarm.swarmai.SwarmAIExamplesApplication.class, args);
    }
}
