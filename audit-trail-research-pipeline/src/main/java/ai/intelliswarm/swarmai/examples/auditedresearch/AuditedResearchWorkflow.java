package ai.intelliswarm.swarmai.examples.auditedresearch;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.CompactionConfig;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import ai.intelliswarm.swarmai.tool.common.HttpRequestTool;
import ai.intelliswarm.swarmai.tool.common.WebScrapeTool;
import ai.intelliswarm.swarmai.tool.common.FileWriteTool;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import ai.intelliswarm.swarmai.observability.core.ObservabilityHelper;
import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.decision.DecisionTree;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.observability.replay.WorkflowRecording;
import ai.intelliswarm.swarmai.observability.logging.StructuredLogger;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.boot.SpringApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Component
public class AuditedResearchWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(AuditedResearchWorkflow.class);

    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final WebSearchTool webSearchTool;
    private final HttpRequestTool httpRequestTool;
    private final WebScrapeTool webScrapeTool;
    private final FileWriteTool fileWriteTool;
    private final CalculatorTool calculatorTool;
    private final ObservabilityHelper observabilityHelper;
    private final DecisionTracer decisionTracer;
    private final EventStore eventStore;
    private final StructuredLogger structuredLogger;

    // Audit state
    private final List<String> auditLog = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger sanitizedCount = new AtomicInteger(0);
    private final AtomicInteger rateLimitWarnings = new AtomicInteger(0);

    public AuditedResearchWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            WebSearchTool webSearchTool,
            HttpRequestTool httpRequestTool,
            WebScrapeTool webScrapeTool,
            FileWriteTool fileWriteTool,
            CalculatorTool calculatorTool,
            @Autowired(required = false) ObservabilityHelper observabilityHelper,
            @Autowired(required = false) DecisionTracer decisionTracer,
            @Autowired(required = false) EventStore eventStore,
            @Autowired(required = false) StructuredLogger structuredLogger) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.webSearchTool = webSearchTool;
        this.httpRequestTool = httpRequestTool;
        this.webScrapeTool = webScrapeTool;
        this.fileWriteTool = fileWriteTool;
        this.calculatorTool = calculatorTool;
        this.observabilityHelper = observabilityHelper;
        this.decisionTracer = decisionTracer;
        this.eventStore = eventStore;
        this.structuredLogger = structuredLogger;
    }

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        String topic = args.length > 0 ? String.join(" ", args) : "AI agent frameworks in enterprise 2026";
        logger.info("Starting Audited Research Pipeline for: {}", topic);

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("audited-research");
        metrics.start();

        ChatClient chatClient = chatClientBuilder.build();

        // Build custom tool hooks
        ToolHook auditHook = buildAuditHook();
        ToolHook sanitizationHook = buildSanitizationHook();
        ToolHook rateLimitHook = buildRateLimitHook();

        // Health check tools
        List<BaseTool> allTools = List.of(webSearchTool, httpRequestTool, webScrapeTool, calculatorTool);
        List<BaseTool> healthyTools = ToolHealthChecker.filterOperational(allTools);
        // Writer uses FileWriteTool to persist the final report to disk
        List<BaseTool> writeTools = List.of(fileWriteTool, calculatorTool);

        // Build agents with full feature set
        Agent researcher = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Conduct thorough, multi-turn research on the given topic. " +
                      "Use tools iteratively: search, fetch details, cross-reference, " +
                      "and refine findings across multiple turns. Cite all sources.")
                .backstory("You are a seasoned research analyst who methodically builds " +
                           "understanding through iterative investigation. You never stop at " +
                           "the first search result — you dig deeper, cross-reference, and " +
                           "verify facts across multiple sources.")
                .chatClient(chatClient)
                .tools(healthyTools)
                .maxTurns(5)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .toolHook(auditHook)
                .toolHook(sanitizationHook)
                .toolHook(rateLimitHook)
                .verbose(true)
                .maxRpm(15)
                .temperature(0.2)
                .build();

        Agent writer = Agent.builder()
                .role("Research Report Writer")
                .goal("Synthesize research findings into a comprehensive, well-structured " +
                      "report with proper citations and executive summary.")
                .backstory("You are a professional technical writer who transforms raw " +
                           "research into polished reports. You ensure every claim is " +
                           "backed by evidence from the research phase.")
                .chatClient(chatClient)
                .tools(writeTools)
                .maxTurns(1)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .toolHook(metrics.metricsHook())
                .toolHook(auditHook)
                .verbose(true)
                .maxRpm(10)
                .temperature(0.3)
                .build();

        // Build tasks
        Task researchTask = Task.builder()
                .description(String.format(
                        "Research the following topic thoroughly: %s\n\n" +
                        "INSTRUCTIONS:\n" +
                        "1. Start with broad searches to identify key themes\n" +
                        "2. Drill into the most important findings with targeted searches\n" +
                        "3. Cross-reference facts across at least 2 sources\n" +
                        "4. Identify key players, trends, and quantitative data\n" +
                        "5. Note any conflicting information or gaps\n\n" +
                        "Use multiple search queries and tool calls across your turns.\n" +
                        "Signal <CONTINUE> after each turn until your research is thorough.\n" +
                        "Signal <DONE> when you have comprehensive coverage.", topic))
                .expectedOutput("Structured research notes with:\n" +
                        "- Key findings (numbered, with sources)\n" +
                        "- Quantitative data points\n" +
                        "- Key players and their positions\n" +
                        "- Trends and predictions\n" +
                        "- Information gaps")
                .agent(researcher)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(300000)
                .build();

        Task reportTask = Task.builder()
                .description("Write a comprehensive research report based on the research findings.\n\n" +
                        "REQUIRED SECTIONS:\n" +
                        "1. Executive Summary (3-5 bullet points)\n" +
                        "2. Key Findings (detailed, with citations)\n" +
                        "3. Market Landscape / Key Players\n" +
                        "4. Trends and Predictions\n" +
                        "5. Risk Factors and Uncertainties\n" +
                        "6. Recommendations\n" +
                        "7. Sources and References\n\n" +
                        "CRITICAL: After drafting the report, you MUST call the `file_write` tool " +
                        "to persist the final markdown to `output/audited-research-report.md`. " +
                        "This demonstrates integrated file-saving as an agent capability. " +
                        "Use file_write with path=output/audited-research-report.md and the full " +
                        "report content. Do NOT end the task without successfully invoking file_write.")
                .expectedOutput("Professional research report in markdown format, persisted to output/audited-research-report.md via the file_write tool")
                .agent(writer)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/audited-research-report.md")
                .maxExecutionTime(180000)
                .dependsOn(researchTask)
                .build();

        // Initialize decision tracing
        String correlationId = UUID.randomUUID().toString();
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            decisionTracer.startTrace(correlationId, "audited-research");
            logger.info("Decision tracing enabled - Correlation ID: {}", correlationId);
        }

        // Build and execute swarm
        Swarm swarm = Swarm.builder()
                .id("audited-research")
                .agent(researcher)
                .agent(writer)
                .task(researchTask)
                .task(reportTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .maxRpm(15)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput result = swarm.kickoff(Map.of(
                "topic", topic,
                "analysisScope", "Comprehensive research with multi-turn investigation"
        ));

        // Complete tracing
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            decisionTracer.completeTrace(correlationId);
        }

        metrics.stop();

        // Display results
        logger.info("\n" + "=".repeat(80));
        logger.info("AUDITED RESEARCH PIPELINE COMPLETED");
        logger.info("=".repeat(80));
        logger.info("Topic: {}", topic);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));

        // Display audit summary
        logger.info("\nAUDIT SUMMARY:");
        logger.info("  Total tool calls logged: {}", auditLog.size());
        logger.info("  Outputs sanitized: {}", sanitizedCount.get());
        logger.info("  Rate limit warnings: {}", rateLimitWarnings.get());

        // Display decision trace
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            Optional<DecisionTree> treeOpt = decisionTracer.getDecisionTree(correlationId);
            if (treeOpt.isPresent()) {
                DecisionTree tree = treeOpt.get();
                logger.info("\nDECISION TRACE:");
                logger.info("  Total decisions: {}", tree.getNodeCount());
                logger.info("  Unique agents: {}", tree.getUniqueAgentIds().size());
                String explanation = decisionTracer.explainWorkflow(correlationId);
                logger.info("\nWorkflow Explanation:\n{}", explanation);
            }
        }

        // Save workflow recording
        if (eventStore != null) {
            Optional<WorkflowRecording> recordingOpt = eventStore.createRecording(correlationId);
            if (recordingOpt.isPresent()) {
                WorkflowRecording recording = recordingOpt.get();
                File outputDir = new File("output");
                outputDir.mkdirs();
                recording.saveToFile(new File(outputDir, "audited_research_recording.json"));
                logger.info("Workflow recording saved to output/audited_research_recording.json");
            }
        }

        logger.info("\nFinal Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("audited-research", "Multi-turn research with audit trail, tool hooks, and observability", result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startMs,
                2, 2, "SEQUENTIAL", "audit-trail-research-pipeline");
        }

        metrics.report();
    }

    /** Audit hook: logs every tool call with timestamp and parameters. */
    private ToolHook buildAuditHook() {
        return new ToolHook() {
            @Override
            public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                String entry = String.format("[%s] CALL %s by %s params=%s",
                        java.time.Instant.now(), ctx.toolName(), ctx.agentId(), ctx.inputParams());
                auditLog.add(entry);
                logger.debug("AUDIT: {}", entry);
                return ToolHookResult.allow();
            }

            @Override
            public ToolHookResult afterToolUse(ToolHookContext ctx) {
                String status = ctx.hasError() ? "ERROR" : "OK";
                String entry = String.format("[%s] RESULT %s %s (%d ms, %d chars)",
                        java.time.Instant.now(), ctx.toolName(), status,
                        ctx.executionTimeMs(),
                        ctx.output() != null ? ctx.output().length() : 0);
                auditLog.add(entry);
                return ToolHookResult.allow();
            }
        };
    }

    /** Sanitization hook: redacts email addresses and phone numbers from tool output. */
    private ToolHook buildSanitizationHook() {
        Pattern emailPattern = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
        Pattern phonePattern = Pattern.compile("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b");

        return new ToolHook() {
            @Override
            public ToolHookResult afterToolUse(ToolHookContext ctx) {
                if (ctx.output() == null) return ToolHookResult.allow();

                String sanitized = ctx.output();
                boolean changed = false;

                if (emailPattern.matcher(sanitized).find()) {
                    sanitized = emailPattern.matcher(sanitized).replaceAll("[EMAIL REDACTED]");
                    changed = true;
                }
                if (phonePattern.matcher(sanitized).find()) {
                    sanitized = phonePattern.matcher(sanitized).replaceAll("[PHONE REDACTED]");
                    changed = true;
                }

                if (changed) {
                    sanitizedCount.incrementAndGet();
                    return ToolHookResult.withModifiedOutput(sanitized);
                }
                return ToolHookResult.allow();
            }
        };
    }

    /** Rate limit hook: warns if more than 10 tool calls within 30 seconds. */
    private ToolHook buildRateLimitHook() {
        List<Long> callTimestamps = Collections.synchronizedList(new ArrayList<>());

        return new ToolHook() {
            @Override
            public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                long now = System.currentTimeMillis();
                callTimestamps.add(now);

                // Count calls in last 30 seconds
                long windowStart = now - 30_000;
                long recentCalls = callTimestamps.stream()
                        .filter(t -> t > windowStart)
                        .count();

                if (recentCalls > 10) {
                    rateLimitWarnings.incrementAndGet();
                    return ToolHookResult.warn(String.format(
                            "Rate limit warning: %d tool calls in last 30s (threshold: 10)",
                            recentCalls));
                }
                return ToolHookResult.allow();
            }
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"audited-research"});
    }
}
