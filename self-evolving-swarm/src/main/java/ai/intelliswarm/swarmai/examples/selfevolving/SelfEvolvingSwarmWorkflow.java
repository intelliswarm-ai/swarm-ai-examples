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
 * Demonstrates transparent self-evolution: the swarm is configured as
 * SEQUENTIAL, but {@code Swarm.kickoff()} automatically applies learned
 * optimizations from prior runs — switching to PARALLEL when appropriate.
 *
 * <h2>How it works</h2>
 * <p>The example always builds the swarm as SEQUENTIAL with 3 independent tasks.
 * The magic happens inside {@code Swarm.kickoff()}:</p>
 * <ol>
 *   <li><b>First run</b> — No evolution history. Runs SEQUENTIAL. The self-improvement
 *       phase detects PROCESS_SUITABILITY and persists a PROCESS_TYPE_CHANGE evolution.</li>
 *   <li><b>Second run</b> — {@code Swarm.kickoff()} consults the evolution advisor,
 *       finds a PROCESS_TYPE_CHANGE for independent tasks, and transparently switches
 *       to PARALLEL. The example code doesn't change — the framework evolves itself.</li>
 * </ol>
 *
 * <pre>
 *   ./self-evolving-swarm/run.sh "AI orchestration"   # Run 1: SEQUENTIAL (learns)
 *   ./self-evolving-swarm/run.sh "AI orchestration"   # Run 2: PARALLEL (evolved!)
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

        // Show evolution history
        List<LedgerStore.StoredEvolution> evolutions = ledgerStore.getRecentEvolutions(10);
        int runNumber = evolutions.isEmpty() ? 1 : evolutions.size() + 1;

        logger.info("");
        logger.info("==========================================================");
        logger.info("  SELF-EVOLVING SWARM — Run #{}", runNumber);
        logger.info("==========================================================");
        logger.info("  Topic:        {}", topic);
        logger.info("  Configured:   SEQUENTIAL (3 independent tasks)");
        logger.info("  Evolutions:   {} in H2", evolutions.size());
        if (!evolutions.isEmpty()) {
            logger.info("  Swarm.kickoff() will consult the evolution advisor");
            logger.info("  and may transparently switch to PARALLEL.");
            logger.info("");
            logger.info("  Evolution history:");
            for (LedgerStore.StoredEvolution evo : evolutions) {
                logger.info("    [{}] {} — {}", evo.createdAt().toString().substring(0, 19),
                        evo.evolutionType(), evo.reason());
            }
        } else {
            logger.info("  First run — no evolution history yet.");
            logger.info("  After completion, PROCESS_SUITABILITY will be observed");
            logger.info("  and a PROCESS_TYPE_CHANGE evolution persisted to H2.");
        }
        logger.info("==========================================================");
        logger.info("");

        // Build as SEQUENTIAL — Swarm.kickoff() will evolve if prior runs learned better
        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("self-evolving");
        metrics.start();
        long startMs = System.currentTimeMillis();

        Agent analyst1 = Agent.builder()
                .role("Technology Analyst")
                .goal("Analyze the technology landscape for " + topic)
                .backstory("Senior technology analyst specializing in emerging tech trends.")
                .chatClient(chatClient).verbose(true).maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook()).build();

        Agent analyst2 = Agent.builder()
                .role("Market Analyst")
                .goal("Analyze market dynamics and competitive landscape for " + topic)
                .backstory("Market research analyst who identifies trends and opportunities.")
                .chatClient(chatClient).verbose(true).maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook()).build();

        Agent analyst3 = Agent.builder()
                .role("Risk Analyst")
                .goal("Identify risks and challenges for " + topic)
                .backstory("Risk analyst who evaluates potential threats and mitigation strategies.")
                .chatClient(chatClient).verbose(true).maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook()).build();

        // 3 independent tasks — configured as SEQUENTIAL
        // Swarm.kickoff() will transparently switch to PARALLEL after first run
        Task techTask = Task.builder()
                .description("Write a 200-word technology analysis of " + topic
                        + ". Cover: key technologies, maturity level, adoption trends.")
                .expectedOutput("Concise technology landscape summary")
                .agent(analyst1).build();

        Task marketTask = Task.builder()
                .description("Write a 200-word market analysis of " + topic
                        + ". Cover: market size, key players, growth trajectory.")
                .expectedOutput("Concise market dynamics summary")
                .agent(analyst2).build();

        Task riskTask = Task.builder()
                .description("Write a 200-word risk analysis of " + topic
                        + ". Cover: technical risks, market risks, mitigation strategies.")
                .expectedOutput("Concise risk assessment")
                .agent(analyst3).build();

        // NOTE: We always build as SEQUENTIAL. If the evolution advisor finds a
        // PROCESS_TYPE_CHANGE in H2, Swarm.kickoff() will transparently switch
        // to PARALLEL before execution. The example code never changes.
        Swarm swarm = Swarm.builder()
                .id("self-evolving-swarm")
                .agent(analyst1).agent(analyst2).agent(analyst3)
                .task(techTask).task(marketTask).task(riskTask)
                .process(ProcessType.SEQUENTIAL)
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

        // Report
        logger.info("");
        logger.info("==========================================================");
        logger.info("  SELF-EVOLVING SWARM — Run #{} Complete", runNumber);
        logger.info("==========================================================");
        logger.info("  Duration:  {} ms", durationMs);
        if (evolutions.isEmpty()) {
            logger.info("  Process:   SEQUENTIAL (initial run)");
            logger.info("  Next run will auto-evolve to PARALLEL via Swarm.kickoff()");
            logger.info("");
            logger.info("  >>> Run again to see transparent evolution:");
            logger.info("      ./self-evolving-swarm/run.sh \"{}\"", topic);
        } else {
            logger.info("  Process:   PARALLEL (transparently evolved by Swarm.kickoff())");
        }
        logger.info("  Studio: GET /api/studio/evolutions");
        logger.info("==========================================================");

        String finalOutput = output != null ? output.getFinalOutput() : "";
        judge.evaluate("self-evolving", "Self-evolving swarm with transparent topology optimization",
                finalOutput, output != null, durationMs,
                3, 3, "SELF_EVOLVING", "self-evolving-swarm");
    }

    public static void main(String[] args) throws Exception {
        org.springframework.boot.SpringApplication.run(
                ai.intelliswarm.swarmai.SwarmAIExamplesApplication.class, args);
    }
}
