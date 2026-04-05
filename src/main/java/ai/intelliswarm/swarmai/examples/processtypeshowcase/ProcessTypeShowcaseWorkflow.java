package ai.intelliswarm.swarmai.examples.processtypeshowcase;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Process Type Showcase — same analysis through 5 process types, compare results.
 *
 * <h2>Why This Matters</h2>
 * SwarmAI offers 8 process types — the most in the industry.
 * This example proves feature density by running the SAME research query
 * through 5 types and comparing output quality, token cost, and speed.
 *
 * <h2>Process Types Compared</h2>
 * <pre>
 *   1. SEQUENTIAL      [A] → [B] → [C]                    Simple pipeline
 *   2. PARALLEL         [A] ─┐                             Fan-out/fan-in
 *                        [B] ─┼→ [Synth]
 *                        [C] ─┘
 *   3. HIERARCHICAL       [Manager]                        Top-down delegation
 *                        / | \
 *                      [A] [B] [C]
 *   4. ITERATIVE        [A] → [B] → [Reviewer]            Refinement loop
 *                              ↑       │
 *                              └── REFINE
 *   5. SELF_IMPROVING   [A] → [B] → [Reviewer] → GAPS?    Dynamic skill gen
 *                              ↑       │          → SKILLS
 *                              └── RE-EXECUTE ←────┘
 * </pre>
 */
@Component
public class ProcessTypeShowcaseWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ProcessTypeShowcaseWorkflow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public ProcessTypeShowcaseWorkflow(ChatClient.Builder chatClientBuilder,
                                        ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "AI agent frameworks for enterprise";

        logger.info("\n" + "=".repeat(80));
        logger.info("PROCESS TYPE SHOWCASE");
        logger.info("=".repeat(80));
        logger.info("Topic:    {}", topic);
        logger.info("Compare:  SEQUENTIAL vs PARALLEL vs HIERARCHICAL vs ITERATIVE vs SELF_IMPROVING");
        logger.info("=".repeat(80));

        ChatClient chatClient = chatClientBuilder.build();
        List<ShowcaseResult> results = new ArrayList<>();

        // ── 1. SEQUENTIAL ──────────────────────────────────────────────
        results.add(runShowcase(chatClient, topic, ProcessType.SEQUENTIAL, "SEQUENTIAL",
                "3 agents in a pipeline: Researcher → Analyst → Writer"));

        // ── 2. PARALLEL ────────────────────────────────────────────────
        results.add(runShowcase(chatClient, topic, ProcessType.PARALLEL, "PARALLEL",
                "3 agents analyze concurrently, 1 synthesizer combines"));

        // ── 3. HIERARCHICAL ────────────────────────────────────────────
        results.add(runShowcase(chatClient, topic, ProcessType.HIERARCHICAL, "HIERARCHICAL",
                "Manager plans and delegates to 2 specialists"));

        // ── 4. ITERATIVE ───────────────────────────────────────────────
        results.add(runShowcase(chatClient, topic, ProcessType.ITERATIVE, "ITERATIVE",
                "Analyst + Reviewer loop: refine until APPROVED (max 3)"));

        // ── 5. SELF_IMPROVING ──────────────────────────────────────────
        results.add(runShowcase(chatClient, topic, ProcessType.SELF_IMPROVING, "SELF_IMPROVING",
                "Analyst + Reviewer + skill generation on capability gaps"));

        // ── Comparison Table ───────────────────────────────────────────
        printComparison(results, topic);
        saveResults(results, topic);
    }

    private ShowcaseResult runShowcase(ChatClient chatClient, String topic,
                                        ProcessType processType, String label, String description) {
        logger.info("\n" + "-".repeat(60));
        logger.info("{}: {}", label, description);
        logger.info("-".repeat(60));

        Instant start = Instant.now();
        BudgetPolicy budget = BudgetPolicy.builder()
                .maxTotalTokens(150_000).maxCostUsd(2.00)
                .onExceeded(BudgetPolicy.BudgetAction.WARN).build();
        InMemoryBudgetTracker tracker = new InMemoryBudgetTracker(budget);
        String swarmId = label.toLowerCase() + "-" + System.currentTimeMillis();

        // Build process-specific swarm
        SwarmOutput output;
        try {
            output = buildAndExecute(chatClient, topic, processType, tracker, budget, swarmId);
        } catch (Exception e) {
            logger.error("  {} failed: {}", label, e.getMessage());
            return new ShowcaseResult(label, description, false, 0, 0, 0.0,
                    0, 0, Duration.between(start, Instant.now()).toMillis(), "");
        }

        Duration elapsed = Duration.between(start, Instant.now());
        BudgetSnapshot snapshot = tracker.getSnapshot(swarmId);
        long tokens = snapshot != null ? snapshot.totalTokensUsed() : output.getTotalTokens();
        double cost = snapshot != null ? snapshot.estimatedCostUsd() : 0.0;
        int outputLen = output.getFinalOutput() != null ? output.getFinalOutput().length() : 0;

        logger.info("  Result: {} tasks, {} tokens, ${}, {} chars, {}s",
                output.getTaskOutputs().size(), tokens,
                String.format("%.4f", cost), outputLen,
                String.format("%.1f", elapsed.toMillis() / 1000.0));

        return new ShowcaseResult(label, description, output.isSuccessful(),
                tokens, outputLen, cost, output.getTaskOutputs().size(),
                (int)(output.getSuccessRate() * 100), elapsed.toMillis(),
                output.getFinalOutput() != null ? output.getFinalOutput() : "");
    }

    private SwarmOutput buildAndExecute(ChatClient chatClient, String topic,
                                         ProcessType processType,
                                         InMemoryBudgetTracker tracker,
                                         BudgetPolicy budget, String swarmId) {

        Agent researcher = Agent.builder()
                .role("Research Analyst").goal("Research " + topic)
                .backstory("Senior analyst producing data-driven research.")
                .chatClient(chatClient).permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.2).verbose(false).build();

        Agent analyst = Agent.builder()
                .role("Strategic Analyst").goal("Analyze findings on " + topic)
                .backstory("Strategy consultant distilling insights into actionable recommendations.")
                .chatClient(chatClient).permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.3).verbose(false).build();

        Agent writer = Agent.builder()
                .role("Report Writer").goal("Write executive report on " + topic)
                .backstory("Executive communications expert. Concise, data-driven.")
                .chatClient(chatClient).permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.3).verbose(false).build();

        // Reviewer for ITERATIVE and SELF_IMPROVING
        Agent reviewer = Agent.builder()
                .role("Quality Reviewer")
                .goal("Evaluate report quality. Respond APPROVED or NEEDS_REFINEMENT with feedback.")
                .backstory("Demanding editor — only approves professional-grade work.")
                .chatClient(chatClient).temperature(0.1).verbose(false).build();

        Swarm.Builder swarmBuilder = Swarm.builder()
                .id(swarmId)
                .verbose(false)
                .eventPublisher(eventPublisher)
                .budgetTracker(tracker)
                .budgetPolicy(budget);

        switch (processType) {
            case SEQUENTIAL -> {
                Task t1 = Task.builder().id("research")
                        .description("Research '" + topic + "': market, players, trends.")
                        .expectedOutput("Research brief").agent(researcher)
                        .outputFormat(OutputFormat.MARKDOWN).build();
                Task t2 = Task.builder().id("analyze")
                        .description("Analyze the research findings into strategic insights.")
                        .expectedOutput("Strategic analysis").agent(analyst)
                        .dependsOn(t1).outputFormat(OutputFormat.MARKDOWN).build();
                Task t3 = Task.builder().id("write")
                        .description("Write executive report from the analysis.")
                        .expectedOutput("Executive report").agent(writer)
                        .dependsOn(t2).outputFormat(OutputFormat.MARKDOWN).build();
                swarmBuilder.agent(researcher).agent(analyst).agent(writer)
                        .task(t1).task(t2).task(t3).process(ProcessType.SEQUENTIAL);
            }
            case PARALLEL -> {
                Task t1 = Task.builder().id("tech-analysis")
                        .description("Analyze technology landscape of '" + topic + "'.")
                        .expectedOutput("Technology report").agent(researcher)
                        .outputFormat(OutputFormat.MARKDOWN).build();
                Task t2 = Task.builder().id("market-analysis")
                        .description("Analyze market dynamics of '" + topic + "'.")
                        .expectedOutput("Market report").agent(analyst)
                        .outputFormat(OutputFormat.MARKDOWN).build();
                Task t3 = Task.builder().id("synthesis")
                        .description("Synthesize tech and market analyses into executive report.")
                        .expectedOutput("Executive synthesis").agent(writer)
                        .dependsOn(t1).dependsOn(t2).outputFormat(OutputFormat.MARKDOWN).build();
                swarmBuilder.agent(researcher).agent(analyst).agent(writer)
                        .task(t1).task(t2).task(t3).process(ProcessType.PARALLEL);
            }
            case HIERARCHICAL -> {
                Agent manager = Agent.builder()
                        .role("Research Director")
                        .goal("Plan the research on '" + topic + "' and delegate to specialists, then synthesize.")
                        .backstory("Managing director coordinating research teams.")
                        .chatClient(chatClient).temperature(0.2).verbose(false).build();
                Task t1 = Task.builder().id("research")
                        .description("Research '" + topic + "' per the director's plan.")
                        .expectedOutput("Research findings").agent(researcher)
                        .outputFormat(OutputFormat.MARKDOWN).build();
                Task t2 = Task.builder().id("analyze")
                        .description("Produce strategic analysis from research.")
                        .expectedOutput("Strategic analysis").agent(analyst)
                        .dependsOn(t1).outputFormat(OutputFormat.MARKDOWN).build();
                swarmBuilder.agent(researcher).agent(analyst).managerAgent(manager)
                        .task(t1).task(t2).process(ProcessType.HIERARCHICAL);
            }
            case ITERATIVE -> {
                Task t1 = Task.builder().id("draft")
                        .description("Draft executive report on '" + topic + "' covering market, players, trends, risks.")
                        .expectedOutput("Draft executive report").agent(researcher)
                        .outputFormat(OutputFormat.MARKDOWN).build();
                swarmBuilder.agent(researcher).managerAgent(reviewer)
                        .task(t1).process(ProcessType.ITERATIVE)
                        .config("maxIterations", 3)
                        .config("qualityCriteria", "Report must cover market size, top 3 players, and 3 actionable recommendations.");
            }
            case SELF_IMPROVING -> {
                Task t1 = Task.builder().id("analysis")
                        .description("Analyze '" + topic + "': architecture, strengths, weaknesses, readiness.")
                        .expectedOutput("Structured analysis").agent(researcher)
                        .outputFormat(OutputFormat.MARKDOWN).build();
                swarmBuilder.agent(researcher).managerAgent(reviewer)
                        .task(t1).process(ProcessType.SELF_IMPROVING)
                        .config("maxIterations", 3)
                        .config("qualityCriteria", "Identify specific tools, projects, and data points. No generic claims.");
            }
            default -> throw new IllegalArgumentException("Unsupported process type: " + processType);
        }

        return swarmBuilder.build().kickoff(Map.of("topic", topic));
    }

    private void printComparison(List<ShowcaseResult> results, String topic) {
        logger.info("\n" + "=".repeat(80));
        logger.info("PROCESS TYPE COMPARISON — '{}'", topic);
        logger.info("=".repeat(80));

        logger.info(String.format("\n%-18s %8s %10s %8s %6s %6s %8s",
                "Process", "Success", "Tokens", "Cost", "Chars", "Tasks", "Time(s)"));
        logger.info("-".repeat(74));

        for (ShowcaseResult r : results) {
            logger.info(String.format("%-18s %8s %10d %8s %6d %6d %8.1f",
                    r.processType, r.success ? "YES" : "FAIL",
                    r.tokens, "$" + String.format("%.4f", r.cost),
                    r.outputLength, r.taskCount, r.elapsedMs / 1000.0));
        }

        // Find best in each category
        results.stream().min(Comparator.comparingLong(r -> r.tokens))
                .ifPresent(r -> logger.info("\n  Most token-efficient: {} ({} tokens)", r.processType, r.tokens));
        results.stream().min(Comparator.comparingLong(r -> r.elapsedMs))
                .ifPresent(r -> logger.info("  Fastest:             {} ({}s)", r.processType,
                        String.format("%.1f", r.elapsedMs / 1000.0)));
        results.stream().max(Comparator.comparingInt(r -> r.outputLength))
                .ifPresent(r -> logger.info("  Most detailed:       {} ({} chars)", r.processType, r.outputLength));

        logger.info("=".repeat(80));
    }

    private void saveResults(List<ShowcaseResult> results, String topic) {
        try {
            Path dir = Path.of("output");
            Files.createDirectories(dir);
            List<Map<String, Object>> data = new ArrayList<>();
            for (ShowcaseResult r : results) {
                data.add(Map.of(
                        "processType", r.processType, "success", r.success,
                        "tokens", r.tokens, "cost", r.cost,
                        "outputLength", r.outputLength, "taskCount", r.taskCount,
                        "elapsedMs", r.elapsedMs));
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                    dir.resolve("process_type_showcase.json").toFile(),
                    Map.of("topic", topic, "timestamp", Instant.now().toString(), "results", data));
            logger.info("Results saved to output/process_type_showcase.json");
        } catch (Exception e) {
            logger.warn("Failed to save: {}", e.getMessage());
        }
    }

    record ShowcaseResult(String processType, String description, boolean success,
                           long tokens, int outputLength, double cost,
                           int taskCount, int successRate, long elapsedMs, String output) {}
}
