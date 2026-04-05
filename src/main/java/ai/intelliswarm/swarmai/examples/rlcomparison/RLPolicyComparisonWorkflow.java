package ai.intelliswarm.swarmai.examples.rlcomparison;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.enterprise.rl.deep.DeepRLPolicy;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.SelfImprovingProcess;
import ai.intelliswarm.swarmai.rl.ExperienceBuffer;
import ai.intelliswarm.swarmai.rl.HeuristicPolicy;
import ai.intelliswarm.swarmai.rl.LearningPolicy;
import ai.intelliswarm.swarmai.rl.PolicyEngine;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * RL Policy Comparison — runs the same self-improving task through all 3 RL policies
 * and compares their learning curves.
 *
 * <h2>The 3 Policies</h2>
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────────────────┐
 *   │  HeuristicPolicy (Baseline)                                             │
 *   │  • Static thresholds: generate if score >= 0.60, stop after 3 stale     │
 *   │  • No learning — same decisions every time                              │
 *   │  • Selection weights: always [0.5, 0.3, 0.2]                           │
 *   └──────────────────────────────────────────────────────────────────────────┘
 *
 *   ┌──────────────────────────────────────────────────────────────────────────┐
 *   │  LearningPolicy (Contextual Bandits)                                    │
 *   │  • LinUCB bandit for skill generation (8-dim state → 4 actions)         │
 *   │  • Thompson Sampling for convergence (Beta posteriors)                  │
 *   │  • Bayesian weight optimizer (evolutionary 3-dim search)               │
 *   │  • 50 cold-start decisions delegate to Heuristic, then learn           │
 *   └──────────────────────────────────────────────────────────────────────────┘
 *
 *   ┌──────────────────────────────────────────────────────────────────────────┐
 *   │  DeepRLPolicy (DQN — Enterprise)                                        │
 *   │  • Dual Deep Q-Networks (policy + target) with experience replay        │
 *   │  • Epsilon-greedy exploration (1.0 → 0.05 over 500 steps)              │
 *   │  • Trains every 10 decisions, updates target net every 50               │
 *   │  • Learns across workflow runs via persistent experience buffer          │
 *   └──────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>What This Measures</h2>
 * <ul>
 *   <li>Iterations to convergence (should decrease with learning)</li>
 *   <li>Total tokens consumed (should decrease as policy improves)</li>
 *   <li>Skills generated vs reused (reuse ratio should increase)</li>
 *   <li>Output quality (reviewer approval rate)</li>
 *   <li>Wall-clock time per run</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   ./scripts/run.sh rl-policy-comparison "AI agent frameworks" 3
 * </pre>
 */
@Component
public class RLPolicyComparisonWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(RLPolicyComparisonWorkflow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public RLPolicyComparisonWorkflow(ChatClient.Builder chatClientBuilder,
                                       ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "AI agent frameworks for enterprise";
        int runsPerPolicy = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        logger.info("\n" + "=".repeat(80));
        logger.info("RL POLICY COMPARISON");
        logger.info("=".repeat(80));
        logger.info("Topic:           {}", topic);
        logger.info("Runs per policy: {}", runsPerPolicy);
        logger.info("Policies:        HeuristicPolicy, LearningPolicy, DeepRLPolicy");
        logger.info("=".repeat(80));

        ChatClient chatClient = chatClientBuilder.build();

        // ── Define the 3 policies ──────────────────────────────────────

        Map<String, PolicyEngine> policies = new LinkedHashMap<>();
        policies.put("Heuristic", new HeuristicPolicy());
        policies.put("Learning (LinUCB+Thompson)", new LearningPolicy(10, 1.0, 5000));
        policies.put("DeepRL (DQN)", new DeepRLPolicy(DeepRLPolicy.DeepRLConfig.defaults()));

        // ── Run each policy N times ────────────────────────────────────

        Map<String, List<RunMetrics>> allResults = new LinkedHashMap<>();

        for (var entry : policies.entrySet()) {
            String policyName = entry.getKey();
            PolicyEngine policy = entry.getValue();

            logger.info("\n" + "-".repeat(80));
            logger.info("POLICY: {}", policyName);
            logger.info("-".repeat(80));

            List<RunMetrics> policyResults = new ArrayList<>();

            for (int run = 1; run <= runsPerPolicy; run++) {
                logger.info("\n  --- Run {}/{} ---", run, runsPerPolicy);

                RunMetrics metrics = executeSelfImprovingRun(
                        chatClient, policy, topic, policyName, run);
                policyResults.add(metrics);

                logger.info("  Result: {} iterations, {} tokens, {} skills, approved={}",
                        metrics.iterations, metrics.totalTokens,
                        metrics.skillsGenerated, metrics.approved);
            }

            allResults.put(policyName, policyResults);
        }

        // ── Comparison Report ──────────────────────────────────────────

        printComparisonReport(allResults, runsPerPolicy);
        saveResults(allResults, topic, runsPerPolicy);
    }

    /**
     * Execute one self-improving workflow run with a specific PolicyEngine.
     */
    private RunMetrics executeSelfImprovingRun(
            ChatClient chatClient, PolicyEngine policy,
            String topic, String policyName, int runNumber) {

        Instant start = Instant.now();
        Memory memory = new InMemoryMemory();

        // Budget: enough for a self-improving loop
        BudgetPolicy budgetPolicy = BudgetPolicy.builder()
                .maxTotalTokens(200_000)
                .maxCostUsd(2.00)
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .build();
        InMemoryBudgetTracker budgetTracker = new InMemoryBudgetTracker(budgetPolicy);
        String workflowId = policyName.replaceAll("[^a-zA-Z0-9]", "_") + "-run-" + runNumber;

        // ── Agents ─────────────────────────────────────────────────

        Agent analyst = Agent.builder()
                .role("Research Analyst")
                .goal("Produce a comprehensive, structured analysis of: " + topic)
                .backstory("You are a senior analyst at a technology research firm. " +
                           "Your analyses cover architecture, strengths, weaknesses, and market positioning. " +
                           "You use tools when available and never fabricate data.")
                .chatClient(chatClient)
                .memory(memory)
                .permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.2)
                .verbose(false)
                .build();

        Agent reviewer = Agent.builder()
                .role("Quality Reviewer")
                .goal("Evaluate the analysis for completeness and accuracy. " +
                      "Respond with APPROVED if comprehensive, or identify CAPABILITY_GAPS " +
                      "if the agent needs new tools to improve.")
                .backstory("You are a demanding editor who only approves work that meets " +
                           "professional standards. You specifically identify what tools " +
                           "or capabilities are missing.")
                .chatClient(chatClient)
                .memory(memory)
                .permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.1)
                .verbose(false)
                .build();

        // ── Tasks ──────────────────────────────────────────────────

        Task analysisTask = Task.builder()
                .id("analysis")
                .description(String.format(
                        "Analyze '%s'. Cover:\n" +
                        "1. ARCHITECTURE: Core design patterns and abstractions\n" +
                        "2. STRENGTHS: Key advantages over alternatives\n" +
                        "3. WEAKNESSES: Limitations and gaps\n" +
                        "4. MARKET POSITION: Adoption, community, enterprise readiness\n" +
                        "Use available tools for data gathering.", topic))
                .expectedOutput("Structured 4-section analysis report")
                .agent(analyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        // ── SelfImprovingProcess with specific PolicyEngine ────────

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", topic);
        inputs.put("__budgetTracker", budgetTracker);
        inputs.put("__budgetSwarmId", workflowId);

        SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(analyst),
                reviewer,
                eventPublisher,
                5,  // maxIterations
                "Analysis must cover all 4 sections with specific data points. " +
                "Identify concrete strengths/weaknesses, not generic statements.",
                memory,
                policy  // ← THE VARIABLE: Heuristic vs Learning vs DQN
        );

        SwarmOutput output = process.execute(List.of(analysisTask), inputs, workflowId);

        // ── Collect metrics ────────────────────────────────────────

        Duration elapsed = Duration.between(start, Instant.now());
        BudgetSnapshot snapshot = budgetTracker.getSnapshot(workflowId);

        int iterations = 0;
        int skillsGenerated = 0;
        int skillsReused = 0;
        boolean approved = output.isSuccessful();

        Map<String, Object> meta = output.getMetadata();
        if (meta != null) {
            iterations = meta.containsKey("iterations") ? ((Number) meta.get("iterations")).intValue() : 0;
            skillsGenerated = meta.containsKey("skillsGenerated") ? ((Number) meta.get("skillsGenerated")).intValue() : 0;
            skillsReused = meta.containsKey("skillsReused") ? ((Number) meta.get("skillsReused")).intValue() : 0;
        }

        long totalTokens = snapshot != null ? snapshot.totalTokensUsed() : output.getTotalTokens();
        double cost = snapshot != null ? snapshot.estimatedCostUsd() : 0.0;

        return new RunMetrics(
                policyName, runNumber, iterations, totalTokens, cost,
                skillsGenerated, skillsReused, approved, elapsed.toMillis(),
                output.getFinalOutput() != null ? output.getFinalOutput().length() : 0
        );
    }

    /**
     * Print a formatted comparison table across all policies and runs.
     */
    private void printComparisonReport(Map<String, List<RunMetrics>> allResults, int runsPerPolicy) {
        logger.info("\n" + "=".repeat(80));
        logger.info("RL POLICY COMPARISON — RESULTS");
        logger.info("=".repeat(80));

        // Per-policy averages
        logger.info("\n--- Averages Across {} Runs ---", runsPerPolicy);
        logger.info(String.format("%-30s %10s %10s %8s %8s %8s %8s",
                "Policy", "Avg Iter", "Avg Tokens", "Avg Cost", "Skills+", "Reused", "Approved"));
        logger.info("-".repeat(88));

        for (var entry : allResults.entrySet()) {
            String name = entry.getKey();
            List<RunMetrics> runs = entry.getValue();

            double avgIter = runs.stream().mapToInt(r -> r.iterations).average().orElse(0);
            double avgTokens = runs.stream().mapToLong(r -> r.totalTokens).average().orElse(0);
            double avgCost = runs.stream().mapToDouble(r -> r.cost).average().orElse(0);
            int totalSkills = runs.stream().mapToInt(r -> r.skillsGenerated).sum();
            int totalReused = runs.stream().mapToInt(r -> r.skillsReused).sum();
            long approvedCount = runs.stream().filter(r -> r.approved).count();

            logger.info(String.format("%-30s %10.1f %10.0f %8s %8d %8d %7d/%d",
                    name, avgIter, avgTokens,
                    "$" + String.format("%.4f", avgCost),
                    totalSkills, totalReused,
                    approvedCount, runs.size()));
        }

        // Per-run detail
        logger.info("\n--- Per-Run Detail ---");
        logger.info(String.format("%-30s %4s %6s %10s %8s %6s %6s %8s %8s",
                "Policy", "Run", "Iters", "Tokens", "Cost", "Skill+", "Reuse", "Approved", "Time(s)"));
        logger.info("-".repeat(94));

        for (var entry : allResults.entrySet()) {
            for (RunMetrics m : entry.getValue()) {
                logger.info(String.format("%-30s %4d %6d %10d %8s %6d %6d %8s %8.1f",
                        m.policyName, m.runNumber, m.iterations, m.totalTokens,
                        "$" + String.format("%.4f", m.cost),
                        m.skillsGenerated, m.skillsReused,
                        m.approved ? "YES" : "NO",
                        m.elapsedMs / 1000.0));
            }
        }

        // Learning curve indicators
        logger.info("\n--- Learning Curve Indicators ---");
        for (var entry : allResults.entrySet()) {
            List<RunMetrics> runs = entry.getValue();
            if (runs.size() >= 2) {
                RunMetrics first = runs.get(0);
                RunMetrics last = runs.get(runs.size() - 1);
                double tokenDelta = first.totalTokens > 0
                        ? ((last.totalTokens - first.totalTokens) * 100.0 / first.totalTokens) : 0;
                int iterDelta = last.iterations - first.iterations;
                logger.info("  {}: tokens {}{}%, iterations {}{}",
                        entry.getKey(),
                        tokenDelta >= 0 ? "+" : "", String.format("%.1f", tokenDelta),
                        iterDelta >= 0 ? "+" : "", iterDelta);
            }
        }

        logger.info("\n" + "=".repeat(80));
    }

    /**
     * Save comparison results to JSON for further analysis.
     */
    private void saveResults(Map<String, List<RunMetrics>> allResults,
                              String topic, int runsPerPolicy) {
        try {
            Path outputDir = Path.of("output");
            Files.createDirectories(outputDir);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("topic", topic);
            report.put("runsPerPolicy", runsPerPolicy);
            report.put("timestamp", Instant.now().toString());

            Map<String, Object> policyResults = new LinkedHashMap<>();
            for (var entry : allResults.entrySet()) {
                List<Map<String, Object>> runs = new ArrayList<>();
                for (RunMetrics m : entry.getValue()) {
                    runs.add(Map.of(
                            "run", m.runNumber,
                            "iterations", m.iterations,
                            "totalTokens", m.totalTokens,
                            "cost", m.cost,
                            "skillsGenerated", m.skillsGenerated,
                            "skillsReused", m.skillsReused,
                            "approved", m.approved,
                            "elapsedMs", m.elapsedMs,
                            "outputLength", m.outputLength
                    ));
                }
                policyResults.put(entry.getKey(), runs);
            }
            report.put("policies", policyResults);

            Path file = outputDir.resolve("rl_policy_comparison.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            logger.info("Results saved to {}", file);
        } catch (IOException e) {
            logger.warn("Failed to save results: {}", e.getMessage());
        }
    }

    /**
     * Metrics collected for a single self-improving workflow run.
     */
    record RunMetrics(
            String policyName,
            int runNumber,
            int iterations,
            long totalTokens,
            double cost,
            int skillsGenerated,
            int skillsReused,
            boolean approved,
            long elapsedMs,
            int outputLength
    ) {}
}
