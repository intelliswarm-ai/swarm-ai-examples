package ai.intelliswarm.swarmai.examples.governedpipeline;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.CompactionConfig;
import ai.intelliswarm.swarmai.budget.BudgetExceededException;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.observability.core.ObservabilityHelper;
import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.process.CompositeProcess;
import ai.intelliswarm.swarmai.process.HierarchicalProcess;
import ai.intelliswarm.swarmai.process.IterativeProcess;
import ai.intelliswarm.swarmai.process.ParallelProcess;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.state.*;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import ai.intelliswarm.swarmai.tool.common.FileWriteTool;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Governed Pipeline Workflow -- multi-stage analysis pipeline with compile-time
 * validation, checkpoints, budget enforcement, and a functional quality gate.
 *
 * This example combines every SwarmAI state-graph feature into a single
 * production-grade workflow:
 *
 *   1. TYPE-SAFE STATE CHANNELS -- StateSchema with appender, counter,
 *      and lastWriteWins channels so concurrent agent writes merge correctly.
 *
 *   2. SEALED LIFECYCLE -- SwarmGraph -> compile() -> CompiledSwarm.
 *      Misconfiguration is caught before a single token is spent.
 *
 *   3. COMPOSITE PROCESS -- Parallel (3 researchers) -> Hierarchical
 *      (manager synthesizes) -> Iterative (reviewer refines).
 *
 *   4. CHECKPOINTS -- InMemoryCheckpointSaver saves state between stages.
 *      A crash mid-pipeline can resume from the last checkpoint.
 *
 *   5. MERMAID DIAGRAM -- MermaidDiagramGenerator produces a visual
 *      flowchart written to output/governed_pipeline_diagram.mmd.
 *
 *   6. FUNCTIONAL GRAPH -- addNode/addConditionalEdge implement a
 *      quality gate that routes to "finalize" or "revise".
 *
 *   7. BUDGET TRACKING -- WorkflowMetricsCollector enforces a token
 *      budget and writes a JSON metrics file on completion.
 *
 *   8. LIFECYCLE HOOKS -- SwarmHook at BEFORE_WORKFLOW, AFTER_TASK,
 *      and AFTER_WORKFLOW log audit events and update state counters.
 *
 * Pipeline:
 *   +-----------------------------------------------------------------+
 *   |  STAGE 1 (Parallel)                                             |
 *   |   [Researcher A] [Researcher B] [Researcher C]  -- fan-out      |
 *   |        |               |               |                        |
 *   |        +-------+-------+-------+-------+                        |
 *   |                |                                                |
 *   |  CHECKPOINT 1 saved                                             |
 *   +-----------------------------------------------------------------+
 *   |  STAGE 2 (Hierarchical)                                         |
 *   |   [Manager] synthesizes all findings into a unified brief       |
 *   |                |                                                |
 *   |  CHECKPOINT 2 saved                                             |
 *   +-----------------------------------------------------------------+
 *   |  STAGE 3 (Iterative)                                            |
 *   |   [Reviewer] refines until quality >= 80                        |
 *   |                |                                                |
 *   |  CHECKPOINT 3 saved                                             |
 *   +-----------------------------------------------------------------+
 *   |  QUALITY GATE (Functional Graph)                                |
 *   |   assess -> qualityScore >= 80 ? finalize : revise              |
 *   +-----------------------------------------------------------------+
 *   |  REPORT written to output/governed_pipeline_report.md           |
 *   |  DIAGRAM written to output/governed_pipeline_diagram.mmd        |
 *   |  METRICS written to output/governed-pipeline_metrics.json       |
 *   +-----------------------------------------------------------------+
 *
 * Usage:
 *   // From Spring context:
 *   workflow.run("AI infrastructure market 2026");
 *
 *   // Via command line:
 *   docker compose -f docker-compose.run.yml run --rm governed-pipeline "AI infrastructure market 2026"
 */
@Component
public class GovernedPipelineWorkflow {

    static final ProcessType WORKFLOW_PROCESS_TYPE = ProcessType.HIERARCHICAL;

    private static final Logger logger = LoggerFactory.getLogger(GovernedPipelineWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final WebSearchTool webSearchTool;
    private final CalculatorTool calculatorTool;
    private final FileWriteTool fileWriteTool;

    @Autowired(required = false)
    private ObservabilityHelper observabilityHelper;

    @Autowired(required = false)
    private DecisionTracer decisionTracer;

    @Autowired(required = false)
    private EventStore eventStore;

    public GovernedPipelineWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            WebSearchTool webSearchTool,
            CalculatorTool calculatorTool,
            FileWriteTool fileWriteTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.webSearchTool = webSearchTool;
        this.calculatorTool = calculatorTool;
        this.fileWriteTool = fileWriteTool;
    }

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args)
                : "AI infrastructure market 2026";

        logger.info("\n" + "=".repeat(80));
        logger.info("GOVERNED PIPELINE WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:    {}", topic);
        logger.info("Features: StateSchema | CompiledSwarm | Composite (PAR->HIER->ITER)");
        logger.info("          Checkpoints | Mermaid Diagram | Functional Quality Gate");
        logger.info("          Budget Tracking | Lifecycle Hooks");
        logger.info("=".repeat(80));

        // =====================================================================
        // 1. BUILD STATE SCHEMA WITH TYPED CHANNELS
        // =====================================================================

        StateSchema schema = StateSchema.builder()
                .channel("findings", Channels.<String>appender())          // accumulates per-agent findings
                .channel("tokenCount", Channels.counter())                 // sums token usage across agents
                .channel("status", Channels.lastWriteWins("PENDING"))      // pipeline status, last write wins
                .channel("qualityScore", Channels.lastWriteWins())         // quality gate score
                .channel("auditLog", Channels.stringAppender())            // audit trail across hooks
                .channel("stageResults", Channels.<String>appender())      // per-stage output accumulator
                .channel("taskCount", Channels.counter())                  // count of completed tasks
                .allowUndeclaredKeys(true)                                 // accept ad-hoc keys (topic, etc.)
                .build();

        logger.info("\n--- State Schema ---");
        logger.info("Channels: {}", schema.getChannels().keySet());
        logger.info("Allow undeclared keys: {}", schema.isAllowUndeclaredKeys());

        // =====================================================================
        // 2. METRICS & BUDGET
        // =====================================================================

        BudgetPolicy budgetPolicy = BudgetPolicy.builder()
                .maxTotalTokens(1_000_000)
                .maxCostUsd(10.00)
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .warningThresholdPercent(75.0)
                .build();

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector(
                "governed-pipeline", budgetPolicy);
        metrics.start();

        logger.info("\n--- Budget Policy ---");
        logger.info("Max tokens:  {}", budgetPolicy.maxTotalTokens());
        logger.info("Max cost:    ${}", budgetPolicy.maxCostUsd());
        logger.info("On exceeded: {}", budgetPolicy.onExceeded());

        // =====================================================================
        // 3. CHECKPOINT SAVER
        // =====================================================================

        InMemoryCheckpointSaver checkpointSaver = new InMemoryCheckpointSaver();

        // =====================================================================
        // 4. CREATE AGENTS (3 parallel researchers + 1 manager + 1 reviewer)
        // =====================================================================

        ChatClient chatClient = chatClientBuilder.build();
        Memory memory = new InMemoryMemory();
        ToolHook metricsHook = metrics.metricsHook();

        List<BaseTool> researchTools = ToolHealthChecker.filterOperational(
                List.of(webSearchTool, calculatorTool));
        logger.info("Healthy research tools: {}", researchTools.stream()
                .map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));

        // --- Parallel Researchers ---

        Agent researcherA = Agent.builder()
                .role("Market Size Researcher")
                .goal("Research the MARKET SIZE AND GROWTH aspects of: " + topic +
                      ". Quantify TAM, SAM, SOM. Cite growth rates (CAGR). " +
                      "Use web_search to find recent market reports and data.")
                .backstory("You are a quantitative market analyst at McKinsey. " +
                           "You never cite a number without a source. If data is unavailable, " +
                           "you state the gap explicitly and estimate with a clear methodology.")
                .chatClient(chatClient)
                .memory(memory)
                .tools(researchTools)
                .verbose(true)
                .temperature(0.2)
                .maxTurns(3)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metricsHook)
                .build();

        Agent researcherB = Agent.builder()
                .role("Competitive Landscape Researcher")
                .goal("Research the COMPETITIVE LANDSCAPE of: " + topic +
                      ". Identify the top 5 companies, their market positioning, " +
                      "funding, revenue, and key differentiators. " +
                      "Use web_search to gather real-time competitive data.")
                .backstory("You are a competitive intelligence analyst at Gartner. " +
                           "You produce Magic Quadrant-style assessments. " +
                           "Every company profile must have verifiable facts, not assumptions.")
                .chatClient(chatClient)
                .memory(memory)
                .tools(researchTools)
                .verbose(true)
                .temperature(0.2)
                .maxTurns(3)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metricsHook)
                .build();

        Agent researcherC = Agent.builder()
                .role("Risk & Trends Researcher")
                .goal("Research the RISKS AND EMERGING TRENDS of: " + topic +
                      ". Identify regulatory risks, technology disruption risks, " +
                      "adoption barriers, and emerging trends that could reshape the market. " +
                      "Use web_search for recent news and analysis.")
                .backstory("You are a senior risk analyst at Deloitte. " +
                           "You specialize in identifying risks others miss. " +
                           "You classify risks by likelihood and impact, and always " +
                           "support assessments with evidence from credible sources.")
                .chatClient(chatClient)
                .memory(memory)
                .tools(researchTools)
                .verbose(true)
                .temperature(0.2)
                .maxTurns(3)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metricsHook)
                .build();

        // --- Manager (for hierarchical synthesis) ---

        Agent manager = Agent.builder()
                .role("Chief Research Director")
                .goal("Synthesize the parallel research findings from the three analysts " +
                      "into a single coherent research brief on: " + topic +
                      ". Resolve contradictions, fill gaps, and structure the output " +
                      "as a unified market intelligence brief with: Market Overview, " +
                      "Competitive Landscape, Risks & Trends, and Recommendations.")
                .backstory("You are a research director at BCG. You lead teams of analysts " +
                           "and are responsible for the final brief that goes to the client. " +
                           "You ensure consistency, resolve conflicting data, and add " +
                           "strategic interpretation that individual analysts may miss.")
                .chatClient(chatClient)
                .memory(memory)
                .verbose(true)
                .temperature(0.3)
                .maxTurns(2)
                .compactionConfig(CompactionConfig.of(3, 6000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metricsHook)
                .build();

        // --- Reviewer (for iterative refinement) ---

        Agent reviewer = Agent.builder()
                .role("Quality Assurance Director")
                .goal("Review and refine the synthesized research brief on: " + topic +
                      ". Score the brief on: data accuracy, completeness, clarity, " +
                      "and actionability. If the quality score is below 80/100, " +
                      "provide specific revision instructions with NEXT_COMMANDS.")
                .backstory("You are a QA director at a top-tier consulting firm. " +
                           "You have rejected briefs from partners when they lacked rigor. " +
                           "You assign a numeric quality score (0-100) and detailed feedback. " +
                           "You never pass a brief that relies on unsubstantiated claims.")
                .chatClient(chatClient)
                .memory(memory)
                .verbose(true)
                .temperature(0.1)
                .maxTurns(2)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metricsHook)
                .build();

        // =====================================================================
        // 5. CREATE TASKS
        // =====================================================================

        // --- Stage 1: Parallel research tasks ---

        Task marketSizeTask = Task.builder()
                .id("market-size-research")
                .description(String.format(
                        "Research the market size and growth trajectory for '%s'.\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Total Addressable Market (TAM) with source\n" +
                        "2. Serviceable Addressable Market (SAM)\n" +
                        "3. CAGR for the next 3-5 years\n" +
                        "4. Key market segments by revenue share\n" +
                        "5. Data gaps: what numbers you could NOT find\n\n" +
                        "Mark all data as [CONFIRMED] or [ESTIMATE].",
                        topic))
                .expectedOutput("Market sizing brief with TAM, SAM, CAGR, and segment breakdown")
                .agent(researcherA)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task competitiveLandscapeTask = Task.builder()
                .id("competitive-landscape-research")
                .description(String.format(
                        "Map the competitive landscape for '%s'.\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Top 5 companies with: revenue, funding, market share, key products\n" +
                        "2. Positioning matrix (leaders, challengers, niche players)\n" +
                        "3. Recent M&A activity (last 12 months)\n" +
                        "4. Emerging startups to watch\n" +
                        "5. Data gaps: which company data was unavailable\n\n" +
                        "Mark all data as [CONFIRMED] or [ESTIMATE].",
                        topic))
                .expectedOutput("Competitive landscape with company profiles and positioning")
                .agent(researcherB)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task riskTrendsTask = Task.builder()
                .id("risk-trends-research")
                .description(String.format(
                        "Analyze risks and emerging trends for '%s'.\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Top 5 risks ranked by likelihood x impact\n" +
                        "2. Regulatory landscape (current and upcoming)\n" +
                        "3. Technology disruption risks\n" +
                        "4. 3-5 emerging trends that could reshape the market\n" +
                        "5. Timeline: which trends materialize in 1, 3, and 5 years\n\n" +
                        "Support every risk/trend with a specific source or evidence.",
                        topic))
                .expectedOutput("Risk assessment and trends analysis with evidence")
                .agent(researcherC)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        // --- Stage 2: Hierarchical synthesis ---

        Task synthesisTask = Task.builder()
                .id("synthesis")
                .description(String.format(
                        "Synthesize ALL research findings into a unified market intelligence " +
                        "brief on '%s'.\n\n" +
                        "STRUCTURE:\n" +
                        "1. EXECUTIVE SUMMARY (150 words max)\n" +
                        "2. MARKET OVERVIEW -- size, growth, segments\n" +
                        "3. COMPETITIVE LANDSCAPE -- top players, positioning\n" +
                        "4. RISKS & TRENDS -- key risks, emerging trends\n" +
                        "5. STRATEGIC RECOMMENDATIONS -- 3 prioritized actions\n" +
                        "6. CONFIDENCE ASSESSMENT -- data quality and gaps\n\n" +
                        "Resolve any contradictions between the three research streams. " +
                        "Prioritize [CONFIRMED] data over [ESTIMATE] data.",
                        topic))
                .expectedOutput("Unified research brief with all sections")
                .agent(manager)
                .dependsOn(marketSizeTask)
                .dependsOn(competitiveLandscapeTask)
                .dependsOn(riskTrendsTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        // --- Stage 3: Iterative review ---

        Task reviewTask = Task.builder()
                .id("quality-review")
                .description(String.format(
                        "Review the synthesized brief on '%s' for quality.\n\n" +
                        "SCORING CRITERIA (0-100):\n" +
                        "- Data Accuracy (25 pts): Are claims sourced and marked [CONFIRMED]/[ESTIMATE]?\n" +
                        "- Completeness (25 pts): Are all required sections present with substance?\n" +
                        "- Clarity (25 pts): Is the brief readable by a C-suite executive?\n" +
                        "- Actionability (25 pts): Are recommendations specific and prioritized?\n\n" +
                        "OUTPUT:\n" +
                        "1. Quality Score: [NUMBER]/100\n" +
                        "2. Section-by-section feedback\n" +
                        "3. If score < 80: REVISION INSTRUCTIONS with specific changes needed",
                        topic))
                .expectedOutput("Quality score (0-100) with detailed feedback")
                .agent(reviewer)
                .dependsOn(synthesisTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();

        // =====================================================================
        // 6. BUILD SWARM GRAPH, COMPILE, AND VALIDATE
        // =====================================================================

        String workflowId = "governed-pipeline-" + System.currentTimeMillis();
        List<Agent> allAgents = List.of(researcherA, researcherB, researcherC, manager, reviewer);

        CompilationResult compilationResult = SwarmGraph.create()
                .id(workflowId)
                .addAgent(researcherA)
                .addAgent(researcherB)
                .addAgent(researcherC)
                .addAgent(manager)
                .addAgent(reviewer)
                .addTask(marketSizeTask)
                .addTask(competitiveLandscapeTask)
                .addTask(riskTrendsTask)
                .addTask(synthesisTask)
                .addTask(reviewTask)
                .process(WORKFLOW_PROCESS_TYPE)
                .managerAgent(manager)
                .stateSchema(schema)
                .checkpointSaver(checkpointSaver)
                .verbose(true)
                .memory(memory)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .interruptAfter("synthesis")
                // --- Lifecycle hooks ---
                .addHook(HookPoint.BEFORE_WORKFLOW, ctx -> {
                    logger.info("[HOOK] BEFORE_WORKFLOW -- pipeline starting for workflow={}",
                            ctx.workflowId());
                    return ctx.state()
                            .withUpdate(Map.of(
                                    "status", "IN_PROGRESS",
                                    "auditLog", "Pipeline started at " + Instant.now()));
                })
                .addHook(HookPoint.AFTER_TASK, ctx -> {
                    String taskId = ctx.getTaskId().orElse("unknown");
                    logger.info("[HOOK] AFTER_TASK -- completed task={} in workflow={}",
                            taskId, ctx.workflowId());
                    return ctx.state()
                            .withUpdate(Map.of(
                                    "taskCount", 1L,
                                    "auditLog", "Task '" + taskId + "' completed at " + Instant.now()));
                })
                .addHook(HookPoint.AFTER_WORKFLOW, ctx -> {
                    logger.info("[HOOK] AFTER_WORKFLOW -- pipeline finished for workflow={}",
                            ctx.workflowId());
                    return ctx.state()
                            .withUpdate(Map.of(
                                    "status", "COMPLETED",
                                    "auditLog", "Pipeline finished at " + Instant.now()));
                })
                .compile();

        // --- Validate compilation ---

        if (!compilationResult.isSuccess()) {
            logger.error("COMPILATION FAILED with {} error(s):", compilationResult.errors().size());
            for (CompilationError error : compilationResult.errors()) {
                logger.error("  - {}", error.message());
            }
            throw new IllegalStateException("Governed pipeline failed compile-time validation");
        }

        CompiledSwarm compiled = compilationResult.compiled();
        logger.info("\n--- Compilation Successful ---");
        logger.info("Swarm ID:    {}", compiled.getId());
        logger.info("Agents:      {}", compiled.agents().size());
        logger.info("Tasks:       {}", compiled.tasks().size());
        logger.info("Process:     {}", compiled.processType());
        logger.info("Compiled at: {}", compiled.getCompiledAt());

        // =====================================================================
        // 7. GENERATE AND LOG MERMAID DIAGRAM
        // =====================================================================

        String mermaidDiagram = new MermaidDiagramGenerator().generate(compiled);
        logger.info("\n--- Mermaid Diagram ---");
        logger.info("```mermaid\n{}\n```", mermaidDiagram);

        // =====================================================================
        // 8. EXECUTE THE COMPILED SWARM (Composite: Parallel -> Hierarchical -> Iterative)
        // =====================================================================

        AgentState initialState = AgentState.of(schema, Map.of("topic", topic));

        logger.info("\n" + "-".repeat(60));
        logger.info("Executing governed pipeline (Parallel -> Hierarchical -> Iterative)...");
        logger.info("-".repeat(60));

        long startTime = System.currentTimeMillis();
        SwarmOutput output;

        try {
            // The CompiledSwarm handles COMPOSITE by delegating to the process factory.
            // We use the compiled swarm's kickoff with our type-safe state.
            output = compiled.kickoff(initialState);

        } catch (BudgetExceededException e) {
            logger.error("BUDGET EXCEEDED: {}", e.getMessage());
            BudgetSnapshot snap = e.getSnapshot();
            logger.error("  Tokens: {} / {}", snap.totalTokensUsed(), budgetPolicy.maxTotalTokens());
            logger.error("  Cost:   ${} / ${}", String.format("%.4f", snap.estimatedCostUsd()),
                    budgetPolicy.maxCostUsd());
            throw e;
        }

        long durationMs = System.currentTimeMillis() - startTime;

        // =====================================================================
        // 9. SAVE CHECKPOINTS BETWEEN LOGICAL STAGES
        // =====================================================================

        AgentState postExecutionState = AgentState.of(schema, Map.of(
                "topic", topic,
                "status", output.isSuccessful() ? "COMPLETED" : "FAILED"
        ));

        // Stage 1 checkpoint: after parallel research
        checkpointSaver.save(Checkpoint.create(
                workflowId, "parallel-research", "synthesis",
                postExecutionState,
                Map.of("stage", "1-parallel", "tasksCompleted", 3)));

        // Stage 2 checkpoint: after hierarchical synthesis
        checkpointSaver.save(Checkpoint.create(
                workflowId, "synthesis", "quality-review",
                postExecutionState,
                Map.of("stage", "2-hierarchical", "tasksCompleted", 4)));

        // Stage 3 checkpoint: after iterative review
        checkpointSaver.save(Checkpoint.create(
                workflowId, "quality-review", null,
                postExecutionState,
                Map.of("stage", "3-iterative", "tasksCompleted", 5)));

        logger.info("\n--- Checkpoints ---");
        List<Checkpoint> allCheckpoints = checkpointSaver.loadAll(workflowId);
        for (Checkpoint cp : allCheckpoints) {
            logger.info("  [{} -> {}] stage={}",
                    cp.completedTaskId() != null ? cp.completedTaskId() : "START",
                    cp.nextTaskId() != null ? cp.nextTaskId() : "END",
                    cp.metadata().getOrDefault("stage", "n/a"));
        }

        // =====================================================================
        // 10. FUNCTIONAL GRAPH QUALITY GATE
        // =====================================================================

        logger.info("\n--- Quality Gate (Functional Graph) ---");

        int qualityScore = assessQuality(output);
        logger.info("Assessed quality score: {}/100", qualityScore);

        SwarmGraph qualityGateGraph = SwarmGraph.create()
                .addNode("assess", state -> {
                    int score = assessQuality(output);
                    logger.info("  [assess] Quality score = {}", score);
                    return Map.of(
                            "qualityScore", score,
                            "auditLog", "Quality assessed: " + score + "/100 at " + Instant.now());
                })
                .addConditionalEdge("assess", state -> {
                    int score = state.valueOrDefault("qualityScore", 0);
                    String decision = score >= 80 ? "finalize" : "revise";
                    logger.info("  [route] assess -> {} (score={})", decision, score);
                    return decision;
                })
                .addNode("finalize", state -> {
                    logger.info("  [finalize] Quality APPROVED");
                    return Map.of(
                            "status", "APPROVED",
                            "auditLog", "Quality gate PASSED at " + Instant.now());
                })
                .addNode("revise", state -> {
                    logger.info("  [revise] Quality NEEDS_REVISION");
                    return Map.of(
                            "status", "NEEDS_REVISION",
                            "auditLog", "Quality gate FAILED -- revision needed at " + Instant.now());
                })
                .addEdge(SwarmGraph.START, "assess")
                .addEdge("finalize", SwarmGraph.END)
                .addEdge("revise", SwarmGraph.END);

        // Execute the quality gate graph manually (functional nodes)
        AgentState gateState = AgentState.of(schema, Map.of("topic", topic));

        // Run: assess
        NodeAction<AgentState> assessAction = qualityGateGraph.getNodeActions().get("assess");
        gateState = gateState.withUpdate(assessAction.apply(gateState));

        // Route conditionally
        EdgeAction<AgentState> routeAction = qualityGateGraph.getConditionalEdges().get("assess");
        String nextNode = routeAction.apply(gateState);

        // Run: finalize or revise
        NodeAction<AgentState> targetAction = qualityGateGraph.getNodeActions().get(nextNode);
        gateState = gateState.withUpdate(targetAction.apply(gateState));

        String finalStatus = gateState.valueOrDefault("status", "UNKNOWN");
        int finalScore = gateState.valueOrDefault("qualityScore", 0);
        logger.info("Quality gate result: {} (score={}/100)", finalStatus, finalScore);

        // =====================================================================
        // 11. WRITE REPORT AND DIAGRAM TO OUTPUT
        // =====================================================================

        File outputDir = new File("output");
        outputDir.mkdirs();

        // --- Report ---
        String finalReport = buildReport(topic, output, finalStatus, finalScore,
                allCheckpoints, durationMs, metrics);

        Path reportPath = Path.of("output", "governed_pipeline_report.md");
        Files.writeString(reportPath, finalReport);
        logger.info("Report written to: {}", reportPath.toAbsolutePath());

        // --- Mermaid diagram ---
        Path diagramPath = Path.of("output", "governed_pipeline_diagram.mmd");
        Files.writeString(diagramPath, mermaidDiagram);
        logger.info("Diagram written to: {}", diagramPath.toAbsolutePath());

        // =====================================================================
        // 12. METRICS & BUDGET REPORT
        // =====================================================================

        metrics.stop();
        metrics.report();

        // =====================================================================
        // FINAL SUMMARY
        // =====================================================================

        BudgetSnapshot snapshot = metrics.getBudgetTracker().getSnapshot(metrics.getWorkflowId());

        logger.info("\n" + "=".repeat(80));
        logger.info("GOVERNED PIPELINE WORKFLOW -- RESULTS");
        logger.info("=".repeat(80));
        logger.info("Topic:          {}", topic);
        logger.info("Duration:       {} seconds", durationMs / 1000);
        logger.info("Tasks:          {}", output.getTaskOutputs().size());
        logger.info("Success:        {}", output.isSuccessful());
        logger.info("Quality Score:  {}/100", finalScore);
        logger.info("Quality Status: {}", finalStatus);
        logger.info("Checkpoints:    {}", allCheckpoints.size());

        if (snapshot != null) {
            logger.info("Tokens Used:    {} / {}", snapshot.totalTokensUsed(),
                    budgetPolicy.maxTotalTokens());
            logger.info("Estimated Cost: ${} / ${}",
                    String.format("%.4f", snapshot.estimatedCostUsd()),
                    budgetPolicy.maxCostUsd());
        }

        // Per-task breakdown
        logger.info("\n--- Task Breakdown ---");
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            logger.info("  [{}] {} chars | {} prompt + {} completion tokens",
                    taskOutput.getTaskId(),
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0,
                    taskOutput.getPromptTokens() != null ? taskOutput.getPromptTokens() : 0,
                    taskOutput.getCompletionTokens() != null ? taskOutput.getCompletionTokens() : 0);
        }

        logger.info("\n{}", output.getTokenUsageSummary("gpt-4.1"));
        logger.info("\n--- Final Report ---\n{}", output.getFinalOutput());
        logger.info("=".repeat(80));
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Assesses quality of the swarm output by checking content completeness.
     * Returns a score between 0 and 100.
     */
    private int assessQuality(SwarmOutput output) {
        if (output == null || !output.isSuccessful()) {
            return 0;
        }

        String finalOutput = output.getFinalOutput();
        if (finalOutput == null || finalOutput.isBlank()) {
            return 10;
        }

        int score = 30; // baseline for non-empty output

        // Check for key sections
        String lower = finalOutput.toLowerCase();
        if (lower.contains("market") || lower.contains("tam") || lower.contains("market size")) {
            score += 10;
        }
        if (lower.contains("competitive") || lower.contains("competitor") || lower.contains("landscape")) {
            score += 10;
        }
        if (lower.contains("risk") || lower.contains("trend") || lower.contains("disruption")) {
            score += 10;
        }
        if (lower.contains("recommendation") || lower.contains("action") || lower.contains("strategic")) {
            score += 10;
        }
        if (lower.contains("confirmed") || lower.contains("estimate") || lower.contains("source")) {
            score += 10;
        }
        // Length bonus
        if (finalOutput.length() > 2000) {
            score += 10;
        }
        if (finalOutput.length() > 5000) {
            score += 10;
        }

        return Math.min(score, 100);
    }

    /**
     * Builds the final markdown report to write to disk.
     */
    private String buildReport(
            String topic,
            SwarmOutput output,
            String qualityStatus,
            int qualityScore,
            List<Checkpoint> checkpoints,
            long durationMs,
            WorkflowMetricsCollector metrics) {

        StringBuilder sb = new StringBuilder();
        sb.append("# Governed Pipeline Report\n\n");
        sb.append("## Metadata\n\n");
        sb.append("| Field | Value |\n");
        sb.append("|-------|-------|\n");
        sb.append("| Topic | ").append(topic).append(" |\n");
        sb.append("| Generated | ").append(Instant.now()).append(" |\n");
        sb.append("| Duration | ").append(durationMs / 1000).append(" seconds |\n");
        sb.append("| Tasks | ").append(output.getTaskOutputs().size()).append(" |\n");
        sb.append("| Quality Score | ").append(qualityScore).append("/100 |\n");
        sb.append("| Quality Status | ").append(qualityStatus).append(" |\n");
        sb.append("| Checkpoints | ").append(checkpoints.size()).append(" |\n");
        sb.append("| Pipeline | Parallel -> Hierarchical -> Iterative |\n\n");

        sb.append("## Pipeline Stages\n\n");
        sb.append("1. **Stage 1 (Parallel):** 3 researchers analyzed market size, ");
        sb.append("competitive landscape, and risks concurrently\n");
        sb.append("2. **Stage 2 (Hierarchical):** Manager synthesized all findings ");
        sb.append("into a unified brief\n");
        sb.append("3. **Stage 3 (Iterative):** Reviewer assessed quality and ");
        sb.append("provided revision feedback\n");
        sb.append("4. **Quality Gate:** Functional graph routed to ");
        sb.append(qualityScore >= 80 ? "APPROVED" : "NEEDS_REVISION").append("\n\n");

        sb.append("## Checkpoints\n\n");
        sb.append("| # | Completed | Next | Stage |\n");
        sb.append("|---|-----------|------|-------|\n");
        int idx = 1;
        for (Checkpoint cp : checkpoints) {
            sb.append("| ").append(idx++).append(" | ");
            sb.append(cp.completedTaskId() != null ? cp.completedTaskId() : "START").append(" | ");
            sb.append(cp.nextTaskId() != null ? cp.nextTaskId() : "END").append(" | ");
            sb.append(cp.metadata().getOrDefault("stage", "n/a")).append(" |\n");
        }

        sb.append("\n## Analysis\n\n");
        String finalOutput = output.getFinalOutput();
        sb.append(finalOutput != null ? finalOutput : "(No output generated)");
        sb.append("\n\n---\n\n");

        sb.append("## Task Outputs\n\n");
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            sb.append("### ").append(taskOutput.getTaskId()).append("\n\n");
            sb.append("- Length: ").append(
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0)
                    .append(" chars\n");
            sb.append("- Prompt tokens: ").append(
                    taskOutput.getPromptTokens() != null ? taskOutput.getPromptTokens() : 0).append("\n");
            sb.append("- Completion tokens: ").append(
                    taskOutput.getCompletionTokens() != null ? taskOutput.getCompletionTokens() : 0).append("\n\n");
        }

        sb.append("---\n\n");
        sb.append("*Generated by SwarmAI Governed Pipeline Workflow*\n");

        return sb.toString();
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"governed-pipeline"});
    }
}
