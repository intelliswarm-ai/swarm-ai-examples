package ai.intelliswarm.swarmai.examples.selfimprovementpipeline;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.process.SelfImprovingProcess;
import ai.intelliswarm.swarmai.rl.HeuristicPolicy;
import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.classifier.ImprovementClassifier;
import ai.intelliswarm.swarmai.selfimproving.collector.ImprovementCollector;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.extractor.PatternExtractor;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementExporter;
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
 * 10% Self-Improvement Pipeline — the framework's "crown jewel" differentiator.
 *
 * <h2>Concept</h2>
 * Every SwarmAI workflow automatically reserves 10% of its token budget for
 * framework-level self-improvement. This is NOT per-workflow optimization —
 * it produces improvements that benefit ALL future users.
 *
 * <h2>Pipeline</h2>
 * <pre>
 *   Phase 1: Execute N workflows normally (90% budget)
 *        │
 *        ▼
 *   Phase 2: ImprovementCollector extracts observations (6 categories)
 *        │    - FAILURE: task failures
 *        │    - EXPENSIVE_TASK: tasks using >40% of tokens
 *        │    - CONVERGENCE_PATTERN: early convergence signals
 *        │    - TOOL_SELECTION: low success rate tools
 *        │    - SUCCESSFUL_SKILL: reusable skills
 *        │    - ANTI_PATTERN: agent spinning, max iterations
 *        ▼
 *   Phase 3: PatternExtractor generalizes to domain-agnostic GenericRules
 *        │    - Cross-validates against historical data
 *        │    - Strips domain-specific terms
 *        │    - Computes confidence scores
 *        ��
 *   Phase 4: ImprovementClassifier routes rules to tiers
 *        │    - TIER_1_AUTOMATIC: confidence ≥ 0.85, data-only → auto-apply
 *        │    - TIER_2_REVIEW: confidence ≥ 0.70, safe change → PR review
 *        │    - TIER_3_PROPOSAL: everything else → GitHub issue
 *        ▼
 *   Phase 5: ImprovementAggregator tracks Community Investment Ledger
 *            - Total workflows analyzed
 *            - Total tokens invested in improvement
 *            - Estimated tokens saved for future users
 *            - ROI calculation
 * </pre>
 *
 * <h2>Why This Matters</h2>
 * <p>100 enterprises × 10 workflows/day × 10% budget = 3,000 improvement
 * observations/day. After 90 days, the framework has accumulated intelligence
 * equivalent to a dedicated optimization team running 24/7.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   ./scripts/run.sh self-improvement-pipeline "AI frameworks" 5
 * </pre>
 */
@Component
public class SelfImprovementPipelineWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(SelfImprovementPipelineWorkflow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public SelfImprovementPipelineWorkflow(ChatClient.Builder chatClientBuilder,
                                            ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String baseTopic = args.length > 0 ? args[0] : "AI agent frameworks";
        int workflowRuns = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        logger.info("\n" + "=".repeat(80));
        logger.info("10%% SELF-IMPROVEMENT PIPELINE");
        logger.info("=".repeat(80));
        logger.info("Base topic:     {}", baseTopic);
        logger.info("Workflow runs:  {}", workflowRuns);
        logger.info("Budget reserve: 10%% per workflow for framework improvement");
        logger.info("=".repeat(80));

        // ── Pipeline Components ────────────────────────────────────────

        SelfImprovementConfig config = new SelfImprovementConfig();
        config.setEnabled(true);

        ImprovementCollector collector = new ImprovementCollector();
        PatternExtractor extractor = new PatternExtractor(config);
        ImprovementClassifier classifier = new ImprovementClassifier(config);
        ImprovementAggregator aggregator = new ImprovementAggregator(config);
        ImprovementPhase improvementPhase = new ImprovementPhase(
                config, collector, extractor, classifier, aggregator);

        ChatClient chatClient = chatClientBuilder.build();

        // ── Diverse Topics for Cross-Workflow Evidence ─────────────────
        // The pipeline needs observations from DIFFERENT workflow shapes
        // to generalize rules (not just one topic repeated).

        List<String> topics = List.of(
                baseTopic,
                "distributed systems architecture",
                "machine learning in production",
                "cloud-native microservices",
                "real-time data streaming"
        );

        // ── Phase 1: Execute Workflows (90% budget each) ──────────────

        logger.info("\n" + "-".repeat(80));
        logger.info("PHASE 1: Execute {} workflows (90%% budget each)", workflowRuns);
        logger.info("-".repeat(80));

        List<ExecutionTrace> traces = new ArrayList<>();

        for (int i = 0; i < Math.min(workflowRuns, topics.size()); i++) {
            String topic = topics.get(i);
            logger.info("\n  --- Workflow {}/{}: '{}' ---", i + 1, workflowRuns, topic);

            ExecutionTrace trace = executeWorkflowAndCollectTrace(
                    chatClient, topic, "workflow-" + (i + 1));
            traces.add(trace);

            logger.info("  Completed: {} tasks, {} tokens, {} iterations, {} skills",
                    trace.taskTraces().size(),
                    trace.totalTokens(),
                    trace.iterationCount(),
                    trace.skillsGenerated().size());
        }

        // ── Phase 2: Collect Observations ──────────────────────────────

        logger.info("\n" + "-".repeat(80));
        logger.info("PHASE 2: Extract observations from {} execution traces", traces.size());
        logger.info("-".repeat(80));

        List<SpecificObservation> allObservations = new ArrayList<>();
        for (ExecutionTrace trace : traces) {
            List<SpecificObservation> obs = collector.collect(trace);
            allObservations.addAll(obs);
            logger.info("  Trace '{}': {} observations extracted", trace.swarmId(), obs.size());
        }

        // Summary by type
        Map<SpecificObservation.ObservationType, Long> byType = new LinkedHashMap<>();
        for (SpecificObservation obs : allObservations) {
            byType.merge(obs.type(), 1L, Long::sum);
        }
        logger.info("\n  Observation breakdown:");
        for (var entry : byType.entrySet()) {
            logger.info("    {}: {}", entry.getKey(), entry.getValue());
        }
        logger.info("  Total: {}", allObservations.size());

        // ── Phase 3: Extract Patterns → GenericRules ───────────────────

        logger.info("\n" + "-".repeat(80));
        logger.info("PHASE 3: Generalize observations into domain-agnostic rules");
        logger.info("-".repeat(80));

        // Feed observations to the extractor for cross-validation history
        extractor.recordObservations(allObservations);

        // Group by type and extract rules per category
        List<GenericRule> rules = new ArrayList<>();
        Map<SpecificObservation.ObservationType, List<SpecificObservation>> grouped = new LinkedHashMap<>();
        for (SpecificObservation obs : allObservations) {
            grouped.computeIfAbsent(obs.type(), k -> new ArrayList<>()).add(obs);
        }

        for (var entry : grouped.entrySet()) {
            if (entry.getValue().size() >= 2) { // Need 2+ observations to generalize
                GenericRule rule = extractor.extract(entry.getValue());
                if (rule != null) {
                    rules.add(rule);
                    logger.info("  Rule: [{}] confidence={} → '{}'",
                            rule.category(), String.format("%.2f", rule.confidence()),
                            rule.recommendation());
                }
            }
        }
        logger.info("  Total rules extracted: {}", rules.size());

        // ── Phase 4: Classify into Tiers ──────���────────────────────────

        logger.info("\n" + "-".repeat(80));
        logger.info("PHASE 4: Classify rules into improvement tiers");
        logger.info("-".repeat(80));

        List<ImprovementProposal> proposals = new ArrayList<>();
        int tier1 = 0, tier2 = 0, tier3 = 0;

        for (GenericRule rule : rules) {
            ImprovementProposal proposal = classifier.classify(rule);
            proposals.add(proposal);
            aggregator.submit(proposal);

            switch (proposal.tier()) {
                case TIER_1_AUTOMATIC -> tier1++;
                case TIER_2_REVIEW -> tier2++;
                case TIER_3_PROPOSAL -> tier3++;
            }

            logger.info("  [{}] {} → target={}, value={}",
                    proposal.tier(), rule.category(),
                    proposal.improvement().targetFile(),
                    proposal.improvement().proposedValue());
        }

        logger.info("\n  Tier breakdown:");
        logger.info("    TIER_1_AUTOMATIC (auto-apply): {}", tier1);
        logger.info("    TIER_2_REVIEW (PR review):     {}", tier2);
        logger.info("    TIER_3_PROPOSAL (GitHub issue): {}", tier3);

        // ── Phase 5: Community Investment Ledger ───────────────────────

        logger.info("\n" + "-".repeat(80));
        logger.info("PHASE 5: Community Investment Ledger");
        logger.info("-".repeat(80));

        ImprovementAggregator.CommunityInvestmentLedger.Snapshot ledger =
                aggregator.getCommunityInvestment();

        logger.info("  Total workflow runs:          {}", ledger.totalWorkflowRuns());
        logger.info("  Total tokens invested (10%%):  {}", ledger.totalTokensInvested());
        logger.info("  Proposals generated:          {}", ledger.totalProposalsGenerated());
        logger.info("  Improvements shipped:         {}", ledger.totalImprovementsShipped());
        logger.info("  Estimated tokens saved:       {}", ledger.estimatedTokensSaved());
        logger.info("  ROI:                          {}x", String.format("%.1f", ledger.roi()));

        // ── Aggregated Release Intelligence ────────────────────────────

        logger.info("\n" + "-".repeat(80));
        logger.info("RELEASE INTELLIGENCE");
        logger.info("-".repeat(80));

        ImprovementAggregator.ReleaseIntelligence release = aggregator.aggregateForRelease();
        logger.info("  Tier 1 (auto-ship):  {}", release.tier1Automatic().size());
        logger.info("  Tier 2 (PR review):  {}", release.tier2Review().size());
        logger.info("  Tier 3 (proposals):  {}", release.tier3Proposals().size());

        // ── Export ──��──────────────────────────────────────────────────

        Path outputDir = Path.of("output", "self-improvement");
        Files.createDirectories(outputDir);
        aggregator.writeIntelligenceArtifacts(outputDir);

        ImprovementExporter exporter = new ImprovementExporter(aggregator);
        ImprovementExporter.ExportResult exportResult = exporter.export(outputDir.resolve("contribution.json"));
        logger.info("\n  Exported {} improvements to {}", exportResult.improvementCount(), exportResult.exportPath());

        ImprovementExporter.PendingSummary pending = exporter.getPendingSummary();
        logger.info("  {}", pending.toOpsMessage());

        // ── Final Summary ──────────────────────────────────────────────

        logger.info("\n" + "=".repeat(80));
        logger.info("SELF-IMPROVEMENT PIPELINE — SUMMARY");
        logger.info("=".repeat(80));
        logger.info("Workflows analyzed:  {}", traces.size());
        logger.info("Observations:        {}", allObservations.size());
        logger.info("Rules extracted:     {}", rules.size());
        logger.info("Proposals:           {} (Tier1={}, Tier2={}, Tier3={})",
                proposals.size(), tier1, tier2, tier3);
        logger.info("Tokens invested:     {} (10%% of {} total)",
                ledger.totalTokensInvested(),
                traces.stream().mapToLong(ExecutionTrace::totalTokens).sum());
        logger.info("ROI:                 {}x", String.format("%.1f", ledger.roi()));
        logger.info("Output:              {}", outputDir.toAbsolutePath());
        logger.info("=".repeat(80));
    }

    /**
     * Execute a single self-improving workflow and build an ExecutionTrace
     * capturing all metrics for the improvement pipeline.
     */
    private ExecutionTrace executeWorkflowAndCollectTrace(
            ChatClient chatClient, String topic, String swarmId) {

        Instant start = Instant.now();
        Memory memory = new InMemoryMemory();

        BudgetPolicy budgetPolicy = BudgetPolicy.builder()
                .maxTotalTokens(100_000)
                .maxCostUsd(1.00)
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .build();
        InMemoryBudgetTracker budgetTracker = new InMemoryBudgetTracker(budgetPolicy);

        Agent analyst = Agent.builder()
                .role("Research Analyst")
                .goal("Produce a structured analysis of: " + topic)
                .backstory("You are a senior analyst. You produce structured reports with data-backed claims.")
                .chatClient(chatClient)
                .memory(memory)
                .permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.2)
                .verbose(false)
                .build();

        Agent reviewer = Agent.builder()
                .role("Quality Reviewer")
                .goal("Evaluate the analysis. Respond APPROVED if complete, or identify CAPABILITY_GAPS.")
                .backstory("Demanding editor who only approves professional-grade work.")
                .chatClient(chatClient)
                .memory(memory)
                .permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.1)
                .verbose(false)
                .build();

        Task task = Task.builder()
                .id("analysis-" + swarmId)
                .description("Analyze '" + topic + "': architecture, strengths, weaknesses, market position.")
                .expectedOutput("Structured 4-section report")
                .agent(analyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", topic);
        inputs.put("__budgetTracker", budgetTracker);
        inputs.put("__budgetSwarmId", swarmId);

        SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(analyst), reviewer, eventPublisher,
                3, "Analysis must cover all 4 sections with specific data.",
                memory, new HeuristicPolicy());

        SwarmOutput output = process.execute(List.of(task), inputs, swarmId);
        Duration duration = Duration.between(start, Instant.now());
        BudgetSnapshot snapshot = budgetTracker.getSnapshot(swarmId);

        // Build ExecutionTrace from the SwarmOutput
        List<ExecutionTrace.TaskTrace> taskTraces = new ArrayList<>();
        for (var to : output.getTaskOutputs()) {
            taskTraces.add(new ExecutionTrace.TaskTrace(
                    to.getTaskId(),
                    to.getAgentId() != null ? to.getAgentId() : "unknown",
                    to.isSuccessful(),
                    to.getPromptTokens() != null ? to.getPromptTokens() : 0,
                    to.getCompletionTokens() != null ? to.getCompletionTokens() : 0,
                    1, // turnCount
                    List.of(), // toolsUsed
                    null, // failureReason
                    Duration.ofMillis(to.getExecutionTimeMs() != null ? to.getExecutionTimeMs() : 0)
            ));
        }

        Map<String, Object> meta = output.getMetadata() != null ? output.getMetadata() : Map.of();
        int iterations = meta.containsKey("iterations") ? ((Number) meta.get("iterations")).intValue() : 1;
        int skillCount = meta.containsKey("skillsGenerated") ? ((Number) meta.get("skillsGenerated")).intValue() : 0;

        List<ExecutionTrace.SkillTrace> skills = new ArrayList<>();
        for (int s = 0; s < skillCount; s++) {
            skills.add(new ExecutionTrace.SkillTrace(
                    "skill-" + s, "generated-skill-" + s, "capability gap",
                    true, 0, 0.7));
        }

        // Build WorkflowShape for pattern matching
        WorkflowShape shape = new WorkflowShape(
                output.getTaskOutputs().size(),
                1, // maxDependencyDepth
                skillCount > 0,
                false, // hasParallelTasks
                false, // hasCyclicDependencies
                Set.of("general"),
                "SELF_IMPROVING",
                2, // agentCount
                0.0, // avgToolsPerAgent
                true, // hasBudgetConstraint
                false  // hasGovernanceGates
        );

        ExecutionTrace.Builder traceBuilder = ExecutionTrace.builder()
                .swarmId(swarmId)
                .workflowShape(shape)
                .swarmOutput(output)
                .totalPromptTokens(snapshot != null ? snapshot.promptTokensUsed() : output.getTotalPromptTokens())
                .totalCompletionTokens(snapshot != null ? snapshot.completionTokensUsed() : output.getTotalCompletionTokens())
                .modelName("mistral:7b")
                .iterationCount(iterations)
                .convergedAtIteration(iterations)
                .totalDuration(duration);

        for (ExecutionTrace.TaskTrace tt : taskTraces) {
            traceBuilder.addTaskTrace(tt);
        }
        for (ExecutionTrace.SkillTrace st : skills) {
            traceBuilder.addSkillTrace(st);
        }

        return traceBuilder.build();
    }
}
