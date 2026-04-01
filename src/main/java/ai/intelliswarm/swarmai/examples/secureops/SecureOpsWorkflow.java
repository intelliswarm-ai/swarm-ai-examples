package ai.intelliswarm.swarmai.examples.secureops;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.CompactionConfig;
import ai.intelliswarm.swarmai.budget.BudgetExceededException;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.logging.StructuredLogger;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.observability.replay.InMemoryEventStore;
import ai.intelliswarm.swarmai.observability.replay.WorkflowRecording;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.skill.GeneratedSkill;
import ai.intelliswarm.swarmai.skill.SkillAssessment;
import ai.intelliswarm.swarmai.skill.SkillCurator;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import ai.intelliswarm.swarmai.tool.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import org.springframework.boot.SpringApplication;

/**
 * Secure Ops Workflow - Security Assessment with Tiered Permissions and Full Observability
 *
 * A composite workflow that demonstrates:
 *   - TIERED PERMISSIONS:    READ_ONLY (recon), WORKSPACE_WRITE (analysis/report)
 *   - TOOL HOOKS:            Compliance hook blocks restricted domains (.gov/.mil),
 *                             timing hook tracks SLA violations, metrics hook counts calls
 *   - SKILL CURATION:        SkillCurator.assess() grades any skills generated during
 *                             self-improving iterations
 *   - DECISION TRACING:      DecisionTracer captures WHY agents made each choice
 *   - EVENT REPLAY:          EventStore + WorkflowRecording for full replay
 *   - STRUCTURED LOGGING:    MDC-enriched logs with correlation IDs
 *   - BUDGET TRACKING:       Per-phase cost tracking via WorkflowMetricsCollector
 *   - REACTIVE MULTI-TURN:   maxTurns(3) on recon agent for progressive gathering
 *   - COMPACTION:             Automatic compaction on long-running agents
 *
 * Workflow Phases:
 *   1. RECON:     READ_ONLY agent progressively gathers information (3 turns)
 *   2. ANALYSIS:  WORKSPACE_WRITE agent analyzes findings and generates assessment
 *   3. REPORT:    WORKSPACE_WRITE agent writes final security assessment report
 *
 * NOTE: This workflow performs security ASSESSMENT for educational/CTF use only.
 *       It does NOT perform actual exploitation.
 *
 * Usage:
 *   docker compose -f docker-compose.run.yml run --rm secure-ops \
 *     "Analyze API security best practices for REST endpoints"
 */
@Component
public class SecureOpsWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(SecureOpsWorkflow.class);

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;
    private final List<BaseTool> allTools;

    // Observability components — injected when observability is enabled, null otherwise
    @Autowired(required = false)
    private DecisionTracer decisionTracer;

    @Autowired(required = false)
    private EventStore eventStore;

    @Autowired(required = false)
    private StructuredLogger structuredLogger;

    // Compliance stats tracked across all hooks
    private final AtomicInteger complianceDenied = new AtomicInteger(0);
    private final AtomicInteger slaWarnings = new AtomicInteger(0);
    private final AtomicLong totalToolTimeMs = new AtomicLong(0);

    public SecureOpsWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            WebSearchTool webSearchTool,
            HttpRequestTool httpRequestTool,
            WebScrapeTool webScrapeTool,
            FileWriteTool fileWriteTool,
            ShellCommandTool shellCommandTool) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
        this.allTools = List.of(
            webSearchTool, httpRequestTool, webScrapeTool, fileWriteTool, shellCommandTool
        );
    }

    public void run(String... args) {
        String topic = args.length > 0 ? String.join(" ", args)
                : "Analyze API security best practices for REST endpoints";

        String correlationId = "secureops-" + System.currentTimeMillis();
        String swarmId = "secure-ops-swarm-" + System.currentTimeMillis();

        logger.info("\n" + "=".repeat(80));
        logger.info("SECURE OPS WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:         {}", topic);
        logger.info("Correlation:   {}", correlationId);
        logger.info("Process:       SEQUENTIAL (recon -> analysis -> report)");
        logger.info("Permissions:   READ_ONLY (recon) | WORKSPACE_WRITE (analysis, report)");
        logger.info("Hooks:         compliance + timing + metrics");
        logger.info("Observability: decision tracing={}, event replay={}, structured logging={}",
                decisionTracer != null, eventStore != null, structuredLogger != null);
        logger.info("=".repeat(80));

        // =====================================================================
        // PHASE 0: INITIALIZE OBSERVABILITY & METRICS
        // =====================================================================

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("secure-ops",
                BudgetPolicy.builder()
                        .maxTotalTokens(500_000)
                        .maxCostUsd(3.00)
                        .onExceeded(BudgetPolicy.BudgetAction.WARN)
                        .warningThresholdPercent(80.0)
                        .build());
        metrics.start();

        // Initialize decision tracing (if available)
        if (decisionTracer != null) {
            decisionTracer.startTrace(correlationId, swarmId);
            logger.info("[Observability] Decision tracing started for {}", correlationId);
        }

        // Initialize structured logging context
        if (structuredLogger != null) {
            structuredLogger.logSwarmStart(swarmId, 3, 3);
        }

        // If no EventStore from Spring, create a standalone one for the demo
        EventStore localEventStore = eventStore;
        if (localEventStore == null) {
            ObservabilityProperties props = new ObservabilityProperties();
            props.setEnabled(true);
            props.setReplayEnabled(true);
            localEventStore = new InMemoryEventStore(props);
            logger.info("[Observability] Created standalone InMemoryEventStore");
        }

        // =====================================================================
        // PHASE 1: DEFINE TOOL HOOKS
        // =====================================================================

        // Hook 1: Compliance hook - blocks calls targeting restricted domains
        ToolHook complianceHook = new ToolHook() {
            @Override
            public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                String params = ctx.inputParams() != null ? ctx.inputParams().toString() : "";
                if (params.contains(".gov") || params.contains(".mil")) {
                    complianceDenied.incrementAndGet();
                    metrics.recordDenied();
                    logger.warn("[Compliance] DENIED tool={} - restricted domain detected in params",
                            ctx.toolName());
                    return ToolHookResult.deny("Compliance: restricted domain (.gov/.mil) - access denied");
                }
                return ToolHookResult.allow();
            }
        };

        // Hook 2: Timing hook - warns when tool calls exceed SLA threshold
        ToolHook timingHook = new ToolHook() {
            @Override
            public ToolHookResult afterToolUse(ToolHookContext ctx) {
                totalToolTimeMs.addAndGet(ctx.executionTimeMs());
                if (ctx.executionTimeMs() > 10_000) {
                    slaWarnings.incrementAndGet();
                    metrics.recordWarning();
                    logger.warn("[SLA] Tool {} took {} ms (threshold: 10000 ms)",
                            ctx.toolName(), ctx.executionTimeMs());
                    return ToolHookResult.warn(
                            "SLA: tool " + ctx.toolName() + " took " + ctx.executionTimeMs() + "ms (exceeds 10s threshold)");
                }
                return ToolHookResult.allow();
            }
        };

        // Hook 3: Metrics hook from WorkflowMetricsCollector (counts calls + time)
        ToolHook metricsHook = metrics.metricsHook();

        // =====================================================================
        // PHASE 2: HEALTH-CHECK TOOLS
        // =====================================================================

        List<BaseTool> healthyTools = ToolHealthChecker.filterOperational(allTools);
        logger.info("Healthy tools: {}", healthyTools.stream()
                .map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));

        // Partition tools by permission tier
        List<BaseTool> reconTools = healthyTools.stream()
                .filter(t -> {
                    String name = t.getFunctionName();
                    return name.equals("web_search") || name.equals("http_request") ||
                           name.equals("web_scrape");
                })
                .collect(Collectors.toList());

        List<BaseTool> analysisTools = healthyTools.stream()
                .filter(t -> {
                    String name = t.getFunctionName();
                    return name.equals("web_search") || name.equals("http_request") ||
                           name.equals("web_scrape") || name.equals("file_write");
                })
                .collect(Collectors.toList());

        List<BaseTool> reportTools = healthyTools.stream()
                .filter(t -> t.getFunctionName().equals("file_write"))
                .collect(Collectors.toList());

        logger.info("Recon tools:    {}", reconTools.stream().map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));
        logger.info("Analysis tools: {}", analysisTools.stream().map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));
        logger.info("Report tools:   {}", reportTools.stream().map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));

        // =====================================================================
        // PHASE 3: CREATE AGENTS (3 tiers)
        // =====================================================================

        // Agent 1: Recon - READ_ONLY, maxTurns(3), compaction, all 3 hooks
        Agent reconAgent = Agent.builder()
                .role("Security Reconnaissance Specialist")
                .goal("Progressively gather security-related information about: " + topic + ". " +
                      "Use multiple search strategies across your 3 turns:\n" +
                      "  Turn 1: Broad search for known vulnerabilities, OWASP guidelines, and CVEs\n" +
                      "  Turn 2: Deep-dive into specific findings - fetch actual security advisories\n" +
                      "  Turn 3: Gather remediation guidance and industry best practices\n\n" +
                      "IMPORTANT: Do NOT access any .gov or .mil domains. " +
                      "Focus on public security resources like OWASP, NIST NVD, and vendor documentation.")
                .backstory("You are a certified security researcher (OSCP, CEH) who specializes in " +
                           "reconnaissance and information gathering. You follow responsible disclosure " +
                           "practices and never access restricted government systems. Your thoroughness " +
                           "in the recon phase determines the quality of the entire assessment.")
                .chatClient(chatClient)
                .tools(reconTools)
                .verbose(true)
                .temperature(0.2)
                .maxTurns(3)
                .compactionConfig(CompactionConfig.of(2, 3000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(complianceHook)
                .toolHook(timingHook)
                .toolHook(metricsHook)
                .build();

        // Agent 2: Analysis - WORKSPACE_WRITE, maxTurns(1), timing + metrics hooks
        Agent analysisAgent = Agent.builder()
                .role("Security Assessment Analyst")
                .goal("Analyze all reconnaissance findings and produce a structured security assessment. " +
                      "Categorize findings by severity (CRITICAL, HIGH, MEDIUM, LOW, INFO). " +
                      "For each finding, provide:\n" +
                      "  - Vulnerability description and affected components\n" +
                      "  - Attack vector and potential impact (CVSS-style)\n" +
                      "  - Evidence from recon data\n" +
                      "  - Recommended remediation with specific implementation steps\n\n" +
                      "Write intermediate analysis notes to /app/output/secure_ops_analysis.txt.")
                .backstory("You are a senior security analyst with 15 years of experience in application " +
                           "security, penetration testing, and security architecture review. You translate " +
                           "raw reconnaissance data into actionable security assessments that development " +
                           "teams can act on. You are meticulous about evidence and never exaggerate risk.")
                .chatClient(chatClient)
                .tools(analysisTools)
                .verbose(true)
                .temperature(0.2)
                .maxTurns(1)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .toolHook(timingHook)
                .toolHook(metricsHook)
                .build();

        // Agent 3: Reporter - WORKSPACE_WRITE, maxTurns(1), metrics hook only
        Agent reportAgent = Agent.builder()
                .role("Security Report Writer")
                .goal("Write the final security assessment report in professional markdown format. " +
                      "Your ENTIRE response must BE the report. Structure:\n\n" +
                      "# Security Assessment Report\n" +
                      "## Executive Summary\n" +
                      "## Scope & Methodology\n" +
                      "## Findings Summary (table: ID | Severity | Title | Status)\n" +
                      "## Detailed Findings (per finding: description, impact, evidence, remediation)\n" +
                      "## Risk Matrix\n" +
                      "## Remediation Roadmap (prioritized timeline)\n" +
                      "## Appendix: Tools & References\n\n" +
                      "Write the report to output/secure_ops_report.md.")
                .backstory("You are a security documentation specialist who writes assessment reports " +
                           "for Fortune 500 clients. Your reports are known for being clear, actionable, " +
                           "and compliant with industry standards (NIST, ISO 27001, SOC 2). Executives " +
                           "trust your risk ratings and development teams implement your recommendations.")
                .chatClient(chatClient)
                .tools(reportTools)
                .verbose(true)
                .temperature(0.3)
                .maxTurns(1)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .toolHook(metricsHook)
                .build();

        // =====================================================================
        // PHASE 4: CREATE TASKS (sequential with dependencies)
        // =====================================================================

        Task reconTask = Task.builder()
                .id("recon")
                .description(String.format(
                        "Perform security reconnaissance on: '%s'\n\n" +
                        "RECONNAISSANCE STRATEGY:\n" +
                        "1. Search for known vulnerabilities, CVEs, and OWASP Top 10 relevance\n" +
                        "2. Fetch security advisories from public sources (NVD, vendor docs)\n" +
                        "3. Gather best practices and hardening guidelines\n\n" +
                        "TOOL STRATEGY:\n" +
                        "- web_search: broad searches for vulnerabilities and best practices\n" +
                        "- http_request: fetch specific advisory pages and API documentation\n" +
                        "- web_scrape: extract detailed content from security resources\n\n" +
                        "CONSTRAINTS:\n" +
                        "- Do NOT access .gov or .mil domains (compliance policy)\n" +
                        "- Document all sources with URLs\n" +
                        "- Tag each finding with confidence level [CONFIRMED/SUSPECTED/THEORETICAL]",
                        topic))
                .expectedOutput("Structured reconnaissance findings with sources, confidence levels, and categorization")
                .agent(reconAgent)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)  // 3 min for recon
                .build();

        Task analysisTask = Task.builder()
                .id("analysis")
                .description("Analyze ALL reconnaissance findings and produce a structured security assessment.\n\n" +
                        "For each finding:\n" +
                        "  1. Assign severity: CRITICAL / HIGH / MEDIUM / LOW / INFO\n" +
                        "  2. Describe the attack vector and potential impact\n" +
                        "  3. Reference the evidence from recon data\n" +
                        "  4. Provide specific remediation steps with code examples where applicable\n\n" +
                        "Create a risk matrix mapping likelihood vs impact.\n" +
                        "Save analysis notes to /app/output/secure_ops_analysis.txt.")
                .expectedOutput("Structured security assessment with severity ratings, risk matrix, and remediation steps")
                .agent(analysisAgent)
                .dependsOn(reconTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)  // 3 min for analysis
                .build();

        Task reportTask = Task.builder()
                .id("report")
                .description("Write a comprehensive, professional security assessment report.\n\n" +
                        "REQUIRED SECTIONS:\n" +
                        "1. Executive Summary (150 words max) - key risk and top recommendation\n" +
                        "2. Scope & Methodology - what was assessed and how\n" +
                        "3. Findings Summary Table - ID, Severity, Title, Status columns\n" +
                        "4. Detailed Findings - full write-up per finding with evidence\n" +
                        "5. Risk Matrix - likelihood vs impact grid\n" +
                        "6. Remediation Roadmap - prioritized by severity with timeline\n" +
                        "7. Appendix - tools used, references, methodology notes\n\n" +
                        "FORMATTING:\n" +
                        "- Use markdown tables for structured data\n" +
                        "- Include code blocks for remediation examples\n" +
                        "- Tag confidence levels: [CONFIRMED], [SUSPECTED], [THEORETICAL]")
                .expectedOutput("Professional security assessment report in markdown")
                .agent(reportAgent)
                .dependsOn(analysisTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/secure_ops_report.md")
                .maxExecutionTime(180000)  // 3 min for report
                .build();

        // =====================================================================
        // PHASE 5: EXECUTE SWARM (SEQUENTIAL process)
        // =====================================================================

        logger.info("\n" + "-".repeat(60));
        logger.info("EXECUTING SECURE OPS WORKFLOW");
        logger.info("-".repeat(60));

        Swarm swarm = Swarm.builder()
                .id(swarmId)
                .agent(reconAgent)
                .agent(analysisAgent)
                .agent(reportAgent)
                .task(reconTask)
                .task(analysisTask)
                .task(reportTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .maxRpm(20)
                .language("en")
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        long startTime = System.currentTimeMillis();
        SwarmOutput result;

        try {
            result = swarm.kickoff(Map.of("topic", topic));
        } catch (BudgetExceededException e) {
            logger.error("BUDGET EXCEEDED: {}", e.getMessage());
            completePendingObservability(correlationId, swarmId, localEventStore, metrics, startTime);
            throw e;
        }

        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // =====================================================================
        // PHASE 6: SKILL CURATION (if skills were generated)
        // =====================================================================

        @SuppressWarnings("unchecked")
        List<GeneratedSkill> generatedSkills = (List<GeneratedSkill>) result.getMetadata()
                .getOrDefault("generatedSkills", List.of());

        if (!generatedSkills.isEmpty()) {
            logger.info("\n--- Skill Curation ---");
            SkillCurator curator = new SkillCurator();

            for (GeneratedSkill skill : generatedSkills) {
                SkillAssessment assessment = curator.assess(skill, generatedSkills);
                logger.info("  Skill '{}': {}/100 ({}) - {}",
                        skill.getName(),
                        assessment.totalScore(),
                        assessment.grade(),
                        assessment.passesCurationBar() ? "PASSES" : "FAILS");

                for (String note : assessment.assessmentNotes()) {
                    logger.info("    {}", note);
                }
            }
        } else {
            logger.info("\n--- Skill Curation ---");
            logger.info("  No skills generated during this run (non-self-improving mode)");
        }

        // =====================================================================
        // PHASE 7: SAVE OBSERVABILITY ARTIFACTS
        // =====================================================================

        // 7a: Decision trace
        if (decisionTracer != null) {
            decisionTracer.completeTrace(correlationId);
            String explanation = decisionTracer.explainWorkflow(correlationId);

            logger.info("\n--- Decision Trace ---");
            logger.info("{}", explanation);

            // Save to file
            try {
                File outputDir = new File("output");
                outputDir.mkdirs();
                File traceFile = new File(outputDir, "secure_ops_decision_trace.txt");
                try (FileWriter writer = new FileWriter(traceFile)) {
                    writer.write(explanation);
                }
                logger.info("Decision trace saved to: {}", traceFile.getAbsolutePath());
            } catch (IOException e) {
                logger.warn("Failed to save decision trace: {}", e.getMessage());
            }
        }

        // 7b: Event recording
        if (localEventStore.hasEvents(correlationId)) {
            try {
                WorkflowRecording recording = localEventStore.createRecording(correlationId).orElseThrow();
                File outputDir = new File("output");
                outputDir.mkdirs();
                File recordingFile = new File(outputDir, "secure_ops_recording.json");
                recording.saveToFile(recordingFile);
                logger.info("Workflow recording saved to: {} ({} events)",
                        recordingFile.getAbsolutePath(),
                        recording.getTimeline().size());
            } catch (IOException e) {
                logger.warn("Failed to save workflow recording: {}", e.getMessage());
            } catch (NoSuchElementException e) {
                logger.info("No events recorded for correlation {} (events may be published to Spring context)",
                        correlationId);
            }
        } else {
            logger.info("No events in store for {} (events published to Spring ApplicationEventPublisher)",
                    correlationId);
        }

        // 7c: Structured logging summary
        if (structuredLogger != null) {
            structuredLogger.logSwarmComplete(swarmId, result.isSuccessful(), duration * 1000);
        }

        // =====================================================================
        // PHASE 8: WRITE REPORT TO OUTPUT FILE
        // =====================================================================

        String reportContent = result.getTaskOutputs().stream()
                .map(TaskOutput::getRawOutput)
                .filter(Objects::nonNull)
                .reduce((a, b) -> b)  // last output is the report
                .orElse("(no report generated)");

        try {
            File outputDir = new File("output");
            outputDir.mkdirs();
            File reportFile = new File(outputDir, "secure_ops_report.md");
            try (FileWriter writer = new FileWriter(reportFile)) {
                writer.write(reportContent);
            }
            logger.info("Report written to: {}", reportFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write report file: {}", e.getMessage());
        }

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("SECURE OPS WORKFLOW COMPLETE");
        logger.info("=".repeat(80));

        // Core metrics
        logger.info("\n--- Workflow ---");
        logger.info("  Topic:           {}", topic);
        logger.info("  Duration:        {} seconds", duration);
        logger.info("  Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("  Success:         {}", result.isSuccessful());
        logger.info("  Success rate:    {}%", (int) (result.getSuccessRate() * 100));

        // Per-task breakdown
        logger.info("\n--- Task Breakdown ---");
        for (TaskOutput taskOutput : result.getTaskOutputs()) {
            logger.info("  [{}] {} chars | {} prompt + {} completion tokens",
                    taskOutput.getTaskId(),
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0,
                    taskOutput.getPromptTokens() != null ? taskOutput.getPromptTokens() : 0,
                    taskOutput.getCompletionTokens() != null ? taskOutput.getCompletionTokens() : 0);
        }

        // Compliance stats
        logger.info("\n--- Compliance & SLA ---");
        logger.info("  Calls denied (compliance): {}", complianceDenied.get());
        logger.info("  SLA warnings (>10s):       {}", slaWarnings.get());
        logger.info("  Total tool time:           {} ms", totalToolTimeMs.get());

        // Permission tiers
        logger.info("\n--- Permission Tiers ---");
        logger.info("  Recon agent:    {} (maxTurns=3, compaction=on)", reconAgent.getPermissionMode());
        logger.info("  Analysis agent: {} (maxTurns=1)", analysisAgent.getPermissionMode());
        logger.info("  Report agent:   {} (maxTurns=1)", reportAgent.getPermissionMode());

        // Budget
        logger.info("\n--- Budget ---");
        BudgetSnapshot snapshot = metrics.getBudgetTracker().getSnapshot(swarmId);
        if (snapshot != null) {
            logger.info("  Tokens used:      {} / {} ({}%)",
                    snapshot.totalTokensUsed(), metrics.getBudgetPolicy().maxTotalTokens(),
                    String.format("%.1f", snapshot.tokenUtilizationPercent()));
            logger.info("  Prompt tokens:    {}", snapshot.promptTokensUsed());
            logger.info("  Completion tokens:{}", snapshot.completionTokensUsed());
            logger.info("  Estimated cost:   ${} / ${}",
                    String.format("%.4f", snapshot.estimatedCostUsd()),
                    metrics.getBudgetPolicy().maxCostUsd());
        } else {
            logger.info("  (No budget data - tokens not reported by provider)");
        }

        // Observability
        logger.info("\n--- Observability ---");
        logger.info("  Decision tracing: {}", decisionTracer != null ? "enabled" : "disabled");
        logger.info("  Event replay:     {}", localEventStore.getTotalEventCount() + " events stored");
        logger.info("  Structured logs:  {}", structuredLogger != null ? "enabled" : "disabled");

        // Skills
        logger.info("\n--- Skills ---");
        logger.info("  Skills generated: {}", result.getMetadata().getOrDefault("skillsGenerated", 0));

        // Token usage
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));

        // Final report preview
        String preview = reportContent.length() > 500
                ? reportContent.substring(0, 500) + "\n... (truncated, see output/secure_ops_report.md)"
                : reportContent;
        logger.info("\n--- Report Preview ---\n{}", preview);
        logger.info("=".repeat(80));

        // Stop metrics and write JSON
        metrics.stop();
        metrics.report();
    }

    /**
     * Completes any pending observability actions on error paths.
     */
    private void completePendingObservability(
            String correlationId, String swarmId,
            EventStore localEventStore, WorkflowMetricsCollector metrics,
            long startTime) {
        long duration = (System.currentTimeMillis() - startTime) / 1000;

        if (decisionTracer != null) {
            decisionTracer.completeTrace(correlationId);
        }
        if (structuredLogger != null) {
            structuredLogger.logSwarmComplete(swarmId, false, duration * 1000);
        }
        metrics.stop();
        metrics.report();
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"secure-ops"});
    }
}
