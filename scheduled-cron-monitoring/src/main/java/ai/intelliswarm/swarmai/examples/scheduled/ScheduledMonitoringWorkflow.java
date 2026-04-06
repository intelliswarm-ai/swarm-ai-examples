package ai.intelliswarm.swarmai.examples.scheduled;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.common.FileReadTool;
import ai.intelliswarm.swarmai.tool.common.FileWriteTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scheduled / Monitoring Agent Workflow
 *
 * Simulates a recurring monitoring agent: 3 iterations with file-persisted state,
 * followed by a Trend Analyst that reads all reports and produces an evolution summary.
 *
 *   Iter 1: [Monitor] --> analyze topic --> write report_1.md
 *   Iter 2: [Monitor] --> read report_1 --> compare + write report_2.md
 *   Iter 3: [Monitor] --> read report_2 --> compare + write report_3.md
 *   Final:  [Trend Analyst] --> read all 3 --> scheduled_trend_summary.md
 *
 * Usage: java -jar swarmai-framework.jar scheduled "system health monitoring best practices"
 */
@Component
public class ScheduledMonitoringWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledMonitoringWorkflow.class);
    private static final int NUM_ITERATIONS = 3;
    private static final String REPORT_DIR = "output";
    private static final String REPORT_PREFIX = "scheduled_report_";

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final FileReadTool fileReadTool;
    private final FileWriteTool fileWriteTool;

    public ScheduledMonitoringWorkflow(ChatClient.Builder chatClientBuilder,
                                       ApplicationEventPublisher eventPublisher,
                                       FileReadTool fileReadTool,
                                       FileWriteTool fileWriteTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.fileReadTool = fileReadTool;
        this.fileWriteTool = fileWriteTool;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args)
                : "system health monitoring best practices";

        logger.info("\n" + "=".repeat(80));
        logger.info("SCHEDULED MONITORING WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:       {}", topic);
        logger.info("Iterations:  {} (simulating scheduled runs)", NUM_ITERATIONS);
        logger.info("Pattern:     Monitor -> Compare -> Trend Detect (file-persisted state)");
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("scheduled-monitoring");
        metrics.start();
        ChatClient chatClient = chatClientBuilder.build();
        List<String> reportPaths = new ArrayList<>();
        List<String> iterationSummaries = new ArrayList<>();
        long workflowStart = System.currentTimeMillis();

        // =====================================================================
        // MONITORING ITERATIONS -- each is a fresh Swarm execution
        // =====================================================================
        for (int iter = 1; iter <= NUM_ITERATIONS; iter++) {
            String reportPath = REPORT_DIR + "/" + REPORT_PREFIX + iter + ".md";
            reportPaths.add(reportPath);
            String simTime = LocalDateTime.now().plusHours(iter - 1)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            logger.info("\n" + "-".repeat(80));
            logger.info("ITERATION {}/{} -- Simulated time: {}", iter, NUM_ITERATIONS, simTime);
            logger.info("-".repeat(80));

            String prevPath = iter > 1 ? REPORT_DIR + "/" + REPORT_PREFIX + (iter - 1) + ".md" : null;
            String desc = iter == 1
                    ? buildBaselinePrompt(topic, reportPath, simTime)
                    : buildComparePrompt(topic, reportPath, prevPath, iter, simTime);

            Agent monitor = Agent.builder()
                    .role("Monitoring Agent")
                    .goal("Analyze '" + topic + "' for scheduled run " + iter + ". "
                          + (iter == 1 ? "Produce a baseline report."
                                       : "Read the previous report and identify changes."))
                    .backstory("You are an automated monitoring agent on a recurring schedule. "
                              + "You track changes over time and always write findings to a report "
                              + "file using file_write so the next run can compare.")
                    .chatClient(chatClient)
                    .tool(fileReadTool).tool(fileWriteTool)
                    .maxTurns(3)
                    .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                    .toolHook(metrics.metricsHook())
                    .verbose(true).temperature(0.2).build();

            Task monitorTask = Task.builder()
                    .description(desc)
                    .expectedOutput("Monitoring report written to " + reportPath)
                    .agent(monitor).outputFormat(OutputFormat.MARKDOWN)
                    .maxExecutionTime(120000).build();

            Swarm iterSwarm = Swarm.builder()
                    .id("scheduled-monitor-iter-" + iter)
                    .agent(monitor).task(monitorTask)
                    .process(ProcessType.SEQUENTIAL).verbose(true).maxRpm(15)
                    .eventPublisher(eventPublisher)
                    .budgetTracker(metrics.getBudgetTracker())
                    .budgetPolicy(metrics.getBudgetPolicy()).build();

            SwarmOutput iterResult = iterSwarm.kickoff(Map.of("topic", topic));
            String summary = iterResult.getFinalOutput();
            iterationSummaries.add(summary != null ? summary : "(no output)");
            logger.info("  Iteration {} complete -- {} chars", iter,
                    summary != null ? summary.length() : 0);
        }

        // =====================================================================
        // TREND ANALYSIS -- reads all reports and produces evolution summary
        // =====================================================================
        logger.info("\n" + "-".repeat(80));
        logger.info("TREND ANALYSIS -- Reading all {} reports", NUM_ITERATIONS);
        logger.info("-".repeat(80));

        Agent trendAnalyst = Agent.builder()
                .role("Trend Analyst")
                .goal("Read all " + NUM_ITERATIONS + " monitoring reports and produce a trend "
                      + "analysis showing how '" + topic + "' evolved across scheduled runs.")
                .backstory("You specialize in identifying patterns across time-series observations. "
                          + "You distill sequential reports into trend lines: what improved, what "
                          + "degraded, what emerged, and what the trajectory suggests.")
                .chatClient(chatClient)
                .tool(fileReadTool).tool(fileWriteTool)
                .maxTurns(3)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .toolHook(metrics.metricsHook())
                .verbose(true).temperature(0.3).build();

        StringBuilder tp = new StringBuilder("Read these monitoring reports with file_read:\n");
        for (String path : reportPaths) tp.append("  - ").append(path).append("\n");
        tp.append("\nWrite a trend summary to: ").append(REPORT_DIR)
          .append("/scheduled_trend_summary.md\n\n")
          .append("REQUIRED SECTIONS:\n")
          .append("1. **Overview** -- Topic and number of runs analyzed\n")
          .append("2. **Evolution Timeline** -- What changed from run to run\n")
          .append("3. **Stable Findings** -- What remained consistent\n")
          .append("4. **Emerging Trends** -- New patterns that appeared over time\n")
          .append("5. **Trajectory** -- Where things are heading\n")
          .append("6. **Recommendations** -- Actions based on the observed evolution\n");

        Task trendTask = Task.builder()
                .description(tp.toString())
                .expectedOutput("Trend analysis written to " + REPORT_DIR + "/scheduled_trend_summary.md")
                .agent(trendAnalyst).outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000).build();

        Swarm trendSwarm = Swarm.builder()
                .id("scheduled-trend-analysis")
                .agent(trendAnalyst).task(trendTask)
                .process(ProcessType.SEQUENTIAL).verbose(true).maxRpm(15)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy()).build();

        SwarmOutput trendResult = trendSwarm.kickoff(Map.of("topic", topic));
        long durationSec = (System.currentTimeMillis() - workflowStart) / 1000;
        metrics.stop();

        // =====================================================================
        // RESULTS -- Evolution Timeline
        // =====================================================================
        logger.info("\n" + "=".repeat(80));
        logger.info("SCHEDULED MONITORING WORKFLOW COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Topic:      {}", topic);
        logger.info("Duration:   {} seconds", durationSec);
        logger.info("Iterations: {}", NUM_ITERATIONS);
        logger.info("\nEvolution Timeline:");
        for (int i = 0; i < iterationSummaries.size(); i++) {
            String s = iterationSummaries.get(i);
            logger.info("  Run {}: {}", i + 1, s.length() > 200 ? s.substring(0, 200) + "..." : s);
        }
        logger.info("\nGenerated Reports:");
        for (String path : reportPaths) logger.info("  - {}", path);
        logger.info("  - {}/scheduled_trend_summary.md", REPORT_DIR);
        logger.info("\n{}", trendResult.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("\nTrend Analysis:\n{}", trendResult.getFinalOutput());
        logger.info("=".repeat(80));
        metrics.report();
    }

    // =====================================================================
    // Prompt Builders
    // =====================================================================

    private String buildBaselinePrompt(String topic, String reportPath, String simTime) {
        return "SCHEDULED MONITORING RUN 1 -- Baseline\nSimulated time: " + simTime + "\n\n"
             + "Analyze the current state of: " + topic + "\n\n"
             + "Produce a baseline monitoring report covering:\n"
             + "1. **Current State** -- Key observations about the topic right now\n"
             + "2. **Key Metrics** -- Important indicators and their current values\n"
             + "3. **Notable Items** -- Anything that stands out or warrants attention\n"
             + "4. **Monitoring Baseline** -- Reference points for future comparison\n\n"
             + "Use file_write with mode='overwrite' to save the report to: " + reportPath + "\n"
             + "Include the simulated timestamp in the report header.";
    }

    private String buildComparePrompt(String topic, String reportPath,
                                       String prevPath, int iter, String simTime) {
        String label = iter == 3 ? "Trend Detection" : "Change Detection";
        return "SCHEDULED MONITORING RUN " + iter + " -- " + label + "\n"
             + "Simulated time: " + simTime + "\n\n"
             + "First, use file_read to read the previous report at: " + prevPath + "\n\n"
             + "Then analyze the current state of: " + topic + "\n\n"
             + "Compare against the previous report and produce:\n"
             + "1. **Current State** -- Key observations now\n"
             + "2. **Changes Detected** -- What differs from the previous run\n"
             + "3. **New Findings** -- Anything not in the previous report\n"
             + "4. **Stable Items** -- What has not changed\n"
             + "5. **Trend Indicators** -- Direction of change (improving/degrading/stable)\n\n"
             + "Use file_write with mode='overwrite' to save the report to: " + reportPath + "\n"
             + "Include the simulated timestamp and reference the previous report.";
    }

    /** IDE entry point. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"scheduled"});
    }
}
