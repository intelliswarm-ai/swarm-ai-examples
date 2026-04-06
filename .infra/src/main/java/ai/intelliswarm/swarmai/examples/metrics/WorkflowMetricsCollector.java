package ai.intelliswarm.swarmai.examples.metrics;

import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared metrics collector used across all workflow examples.
 * Provides a ToolHook for automatic tool-call instrumentation and
 * budget tracking integration. Writes a JSON metrics file on completion.
 */
public class WorkflowMetricsCollector {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowMetricsCollector.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final String workflowName;
    private final BudgetTracker budgetTracker;
    private final BudgetPolicy budgetPolicy;

    // Timing
    private long startTimeMs;
    private long endTimeMs;

    // Tool call counters
    private final AtomicInteger totalToolCalls = new AtomicInteger(0);
    private final AtomicInteger toolCallsDenied = new AtomicInteger(0);
    private final AtomicInteger toolCallsWarned = new AtomicInteger(0);
    private final AtomicLong totalToolTimeMs = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicInteger> toolCallsByName = new ConcurrentHashMap<>();

    // Turn tracking
    private final AtomicInteger totalTurnsUsed = new AtomicInteger(0);
    private final AtomicInteger compactionEvents = new AtomicInteger(0);

    public WorkflowMetricsCollector(String workflowName) {
        this(workflowName, BudgetPolicy.builder()
                .maxTotalTokens(500_000)
                .maxCostUsd(5.0)
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .warningThresholdPercent(80.0)
                .build());
    }

    public WorkflowMetricsCollector(String workflowName, BudgetPolicy policy) {
        this.workflowName = workflowName;
        this.budgetPolicy = policy;
        this.budgetTracker = new InMemoryBudgetTracker(policy);
    }

    /** Call at the start of workflow execution. */
    public void start() {
        this.startTimeMs = System.currentTimeMillis();
        logger.info("[Metrics] {} started at {}", workflowName, Instant.now());
    }

    /** Call at the end of workflow execution. */
    public void stop() {
        this.endTimeMs = System.currentTimeMillis();
        logger.info("[Metrics] {} completed in {} ms", workflowName, endTimeMs - startTimeMs);
    }

    /** Returns the BudgetTracker to wire into Swarm.builder(). */
    public BudgetTracker getBudgetTracker() {
        return budgetTracker;
    }

    /** Returns the BudgetPolicy to wire into Swarm.builder(). */
    public BudgetPolicy getBudgetPolicy() {
        return budgetPolicy;
    }

    /** Returns the workflow ID used for budget tracking. */
    public String getWorkflowId() {
        return workflowName;
    }

    /** Increment turn counter (call from reactive loop hooks or after execution). */
    public void recordTurns(int turns) {
        totalTurnsUsed.addAndGet(turns);
    }

    /** Increment compaction counter. */
    public void recordCompaction() {
        compactionEvents.incrementAndGet();
    }

    /**
     * Returns a ToolHook that instruments every tool call for metrics collection.
     * Attach this to all agents via Agent.builder().toolHook(collector.metricsHook()).
     */
    public ToolHook metricsHook() {
        return new ToolHook() {
            @Override
            public ToolHookResult beforeToolUse(ToolHookContext context) {
                totalToolCalls.incrementAndGet();
                toolCallsByName
                        .computeIfAbsent(context.toolName(), k -> new AtomicInteger(0))
                        .incrementAndGet();
                return ToolHookResult.allow();
            }

            @Override
            public ToolHookResult afterToolUse(ToolHookContext context) {
                totalToolTimeMs.addAndGet(context.executionTimeMs());
                if (context.hasError()) {
                    toolCallsDenied.incrementAndGet();
                }
                return ToolHookResult.allow();
            }
        };
    }

    /**
     * Collects all metrics into a map, prints a summary, and writes to JSON file.
     */
    public Map<String, Object> collect() {
        BudgetSnapshot snapshot = budgetTracker.getSnapshot(workflowName);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("workflowName", workflowName);
        metrics.put("timestamp", Instant.now().toString());

        // Timing
        metrics.put("executionTimeMs", endTimeMs - startTimeMs);
        metrics.put("executionTimeSec", (endTimeMs - startTimeMs) / 1000.0);

        // Token usage (from budget tracker)
        if (snapshot != null) {
            metrics.put("totalTokens", snapshot.totalTokensUsed());
            metrics.put("promptTokens", snapshot.promptTokensUsed());
            metrics.put("completionTokens", snapshot.completionTokensUsed());
            metrics.put("estimatedCostUsd", snapshot.estimatedCostUsd());
            metrics.put("budgetUtilizationPercent", snapshot.tokenUtilizationPercent());
        } else {
            metrics.put("totalTokens", 0);
            metrics.put("promptTokens", 0);
            metrics.put("completionTokens", 0);
            metrics.put("estimatedCostUsd", 0.0);
            metrics.put("budgetUtilizationPercent", 0.0);
        }

        // Tool calls
        metrics.put("totalToolCalls", totalToolCalls.get());
        metrics.put("toolCallsDenied", toolCallsDenied.get());
        metrics.put("toolCallsWarned", toolCallsWarned.get());
        metrics.put("totalToolTimeMs", totalToolTimeMs.get());

        Map<String, Integer> toolBreakdown = new LinkedHashMap<>();
        toolCallsByName.forEach((name, count) -> toolBreakdown.put(name, count.get()));
        metrics.put("toolCallsByName", toolBreakdown);

        // Reactive loop metrics
        metrics.put("totalTurnsUsed", totalTurnsUsed.get());
        metrics.put("compactionEvents", compactionEvents.get());

        // Budget policy
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("maxTotalTokens", budgetPolicy.maxTotalTokens());
        budget.put("maxCostUsd", budgetPolicy.maxCostUsd());
        budget.put("onExceeded", budgetPolicy.onExceeded().name());
        metrics.put("budgetPolicy", budget);

        return metrics;
    }

    /** Print metrics summary to logger and write JSON to output directory. */
    public void report() {
        Map<String, Object> metrics = collect();

        // Print summary
        logger.info("\n" + "=".repeat(70));
        logger.info("WORKFLOW METRICS: {}", workflowName);
        logger.info("=".repeat(70));
        logger.info("  Execution Time:    {} sec", metrics.get("executionTimeSec"));
        logger.info("  Total Tokens:      {}", metrics.get("totalTokens"));
        logger.info("  Prompt Tokens:     {}", metrics.get("promptTokens"));
        logger.info("  Completion Tokens: {}", metrics.get("completionTokens"));
        logger.info("  Estimated Cost:    ${}", String.format("%.4f", (double) metrics.getOrDefault("estimatedCostUsd", 0.0)));
        logger.info("  Budget Used:       {}%", String.format("%.1f", (double) metrics.getOrDefault("budgetUtilizationPercent", 0.0)));
        logger.info("  Tool Calls:        {} (denied: {}, warned: {})",
                metrics.get("totalToolCalls"), metrics.get("toolCallsDenied"), metrics.get("toolCallsWarned"));
        logger.info("  Tool Time:         {} ms", metrics.get("totalToolTimeMs"));
        logger.info("  Turns Used:        {}", metrics.get("totalTurnsUsed"));
        logger.info("  Compactions:       {}", metrics.get("compactionEvents"));
        logger.info("=".repeat(70));

        // Write JSON
        try {
            File outputDir = new File("output");
            outputDir.mkdirs();
            File metricsFile = new File(outputDir, workflowName + "_metrics.json");
            objectMapper.writeValue(metricsFile, metrics);
            logger.info("[Metrics] Written to {}", metricsFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("[Metrics] Failed to write metrics file: {}", e.getMessage());
        }
    }

    /** Increment warned counter (call from custom hooks). */
    public void recordWarning() {
        toolCallsWarned.incrementAndGet();
    }

    /** Increment denied counter (call from custom hooks). */
    public void recordDenied() {
        toolCallsDenied.incrementAndGet();
    }
}
