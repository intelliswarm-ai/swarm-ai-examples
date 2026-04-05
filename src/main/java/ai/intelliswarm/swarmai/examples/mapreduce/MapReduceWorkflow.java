package ai.intelliswarm.swarmai.examples.mapreduce;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Map-Reduce Analysis — distributed processing using the PARALLEL process type.
 *
 * <h2>Pattern</h2>
 * <pre>
 *   MAP PHASE (PARALLEL — Layer 0)
 *   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
 *   │ Mapper 1     │  │ Mapper 2     │  │ Mapper 3     │  │ Mapper 4     │
 *   │ (Technology) │  │ (Market)     │  │ (Regulation) │  │ (Social)     │
 *   └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
 *          │                 │                  │                  │
 *          ▼                 ▼                  ▼                  ▼
 *   REDUCE PHASE (Layer 1 — depends on all mappers)
 *   ┌────────────────────────────────────────────────────────────────────┐
 *   │                      Reducer Agent                                │
 *   │  Synthesizes all mapper outputs into a unified executive report   │
 *   └────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>What This Demonstrates</h2>
 * <ul>
 *   <li><b>PARALLEL process</b> with topological layer sorting</li>
 *   <li><b>Task dependencies</b> — reducer waits for all mappers to complete</li>
 *   <li><b>Data parallelism</b> — same problem decomposed into independent dimensions</li>
 *   <li><b>Context passing</b> — mapper outputs automatically flow to reducer as context</li>
 *   <li><b>Budget tracking</b> — aggregate cost across all parallel agents</li>
 * </ul>
 */
@Component
public class MapReduceWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MapReduceWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public MapReduceWorkflow(ChatClient.Builder chatClientBuilder,
                              ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "AI adoption in enterprise";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("map-reduce");
        metrics.start();

        logger.info("\n" + "=".repeat(70));
        logger.info("MAP-REDUCE ANALYSIS");
        logger.info("=".repeat(70));
        logger.info("Topic:    {}", topic);
        logger.info("Pattern:  4 parallel mappers → 1 reducer");
        logger.info("Process:  PARALLEL (topological layers)");
        logger.info("=".repeat(70));

        ChatClient chatClient = chatClientBuilder.build();

        // ── MAP AGENTS — Each analyzes a different dimension ───────────

        Agent techMapper = Agent.builder()
                .role("Technology Analyst")
                .goal("Analyze the technology landscape for: " + topic)
                .backstory("You are a senior technology analyst at Gartner. " +
                           "You focus on technical capabilities, maturity, and innovation trajectory.")
                .chatClient(chatClient)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .temperature(0.2)
                .verbose(true)
                .build();

        Agent marketMapper = Agent.builder()
                .role("Market Analyst")
                .goal("Analyze the market dynamics for: " + topic)
                .backstory("You are a market research analyst at McKinsey. " +
                           "You focus on market size, growth rates, competitive landscape, and adoption curves.")
                .chatClient(chatClient)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .temperature(0.2)
                .verbose(true)
                .build();

        Agent regulationMapper = Agent.builder()
                .role("Regulatory Analyst")
                .goal("Analyze the regulatory environment for: " + topic)
                .backstory("You are a regulatory affairs consultant. " +
                           "You track legislation, compliance requirements, and policy trends globally.")
                .chatClient(chatClient)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .temperature(0.2)
                .verbose(true)
                .build();

        Agent socialMapper = Agent.builder()
                .role("Social Impact Analyst")
                .goal("Analyze the workforce and social implications of: " + topic)
                .backstory("You are a workforce strategist at Deloitte. " +
                           "You study how technology transforms jobs, skills, and organizational structures.")
                .chatClient(chatClient)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .temperature(0.2)
                .verbose(true)
                .build();

        // ── REDUCE AGENT — Synthesizes all mapper outputs ──────────────

        Agent reducer = Agent.builder()
                .role("Executive Strategist")
                .goal("Synthesize the technology, market, regulatory, and social analyses " +
                      "into a unified strategic recommendation for: " + topic)
                .backstory("You are a partner at BCG. You produce executive-level strategic " +
                           "recommendations by integrating multi-dimensional analyses. " +
                           "Your reports are concise, data-backed, and actionable.")
                .chatClient(chatClient)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .maxTurns(2)
                .temperature(0.3)
                .verbose(true)
                .build();

        // ── MAP TASKS (Layer 0 — run in parallel) ──────────────────────

        Task techTask = Task.builder()
                .id("map-tech")
                .description(String.format(
                        "TECHNOLOGY ANALYSIS for '%s':\n" +
                        "1. Current state of technology maturity\n" +
                        "2. Key enabling technologies and their readiness\n" +
                        "3. Technical barriers to adoption\n" +
                        "4. Innovation trajectory (next 3 years)", topic))
                .expectedOutput("Technology analysis report (2-3 paragraphs)")
                .agent(techMapper)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Task marketTask = Task.builder()
                .id("map-market")
                .description(String.format(
                        "MARKET ANALYSIS for '%s':\n" +
                        "1. Total addressable market and growth rate\n" +
                        "2. Key segments and their adoption curves\n" +
                        "3. Competitive landscape (top 5 vendors)\n" +
                        "4. Market dynamics and inflection points", topic))
                .expectedOutput("Market analysis report (2-3 paragraphs)")
                .agent(marketMapper)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Task regTask = Task.builder()
                .id("map-regulation")
                .description(String.format(
                        "REGULATORY ANALYSIS for '%s':\n" +
                        "1. Current regulatory framework (US, EU, Asia)\n" +
                        "2. Pending legislation and likely impact\n" +
                        "3. Compliance requirements for enterprises\n" +
                        "4. Regulatory risks and mitigation strategies", topic))
                .expectedOutput("Regulatory analysis report (2-3 paragraphs)")
                .agent(regulationMapper)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Task socialTask = Task.builder()
                .id("map-social")
                .description(String.format(
                        "SOCIAL & WORKFORCE ANALYSIS for '%s':\n" +
                        "1. Impact on existing job roles and skills\n" +
                        "2. New roles and competencies emerging\n" +
                        "3. Organizational change requirements\n" +
                        "4. Workforce transition strategies", topic))
                .expectedOutput("Social impact analysis report (2-3 paragraphs)")
                .agent(socialMapper)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        // ── REDUCE TASK (Layer 1 — depends on all map tasks) ───────────

        Task reduceTask = Task.builder()
                .id("reduce")
                .description(String.format(
                        "EXECUTIVE STRATEGIC SYNTHESIS for '%s':\n\n" +
                        "You have received four parallel analyses (technology, market, " +
                        "regulatory, social). Synthesize them into a single strategic recommendation.\n\n" +
                        "STRUCTURE:\n" +
                        "1. EXECUTIVE SUMMARY — Key finding in 50 words\n" +
                        "2. STRATEGIC LANDSCAPE — Cross-dimensional insights\n" +
                        "3. OPPORTUNITIES — Top 3 with supporting evidence from all analyses\n" +
                        "4. RISKS — Top 3 with cross-dimensional risk factors\n" +
                        "5. RECOMMENDATION — Concrete next steps with timeline", topic))
                .expectedOutput("Executive strategic recommendation (4-5 paragraphs)")
                .agent(reducer)
                .dependsOn(techTask)
                .dependsOn(marketTask)
                .dependsOn(regTask)
                .dependsOn(socialTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/map_reduce_" + topic.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase() + ".md")
                .build();

        // ── BUILD & EXECUTE ────────────────────────────────────────────
        // The PARALLEL process handles layers automatically:
        //   Layer 0: techTask, marketTask, regTask, socialTask (run concurrently)
        //   Layer 1: reduceTask (runs after all Layer 0 tasks complete)

        Swarm swarm = Swarm.builder()
                .id("map-reduce-" + System.currentTimeMillis())
                .agent(techMapper)
                .agent(marketMapper)
                .agent(regulationMapper)
                .agent(socialMapper)
                .agent(reducer)
                .task(techTask)
                .task(marketTask)
                .task(regTask)
                .task(socialTask)
                .task(reduceTask)
                .process(ProcessType.PARALLEL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        logger.info("\nExecuting map-reduce pipeline...");
        logger.info("  Layer 0 (MAP):    4 agents analyzing in parallel");
        logger.info("  Layer 1 (REDUCE): 1 agent synthesizing results\n");

        SwarmOutput result = swarm.kickoff(Map.of("topic", topic));

        // ── RESULTS ────────────────────────────────────────────────────

        logger.info("\n" + "=".repeat(70));
        logger.info("MAP-REDUCE RESULTS");
        logger.info("=".repeat(70));
        logger.info("Success:     {}", result.isSuccessful());
        logger.info("Tasks:       {} ({} map + 1 reduce)", result.getTaskOutputs().size(),
                result.getTaskOutputs().size() - 1);
        logger.info("Success rate: {}%", (int)(result.getSuccessRate() * 100));

        logger.info("\n--- Per-Task Breakdown ---");
        for (var output : result.getTaskOutputs()) {
            String phase = output.getTaskId().startsWith("map-") ? "MAP" : "REDUCE";
            logger.info("  [{}] {} | {} chars | {} tokens",
                    phase, output.getTaskId(),
                    output.getRawOutput() != null ? output.getRawOutput().length() : 0,
                    output.getTotalTokens() != null ? output.getTotalTokens() : 0);
        }

        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));

        logger.info("\n--- Strategic Synthesis ---\n{}", result.getFinalOutput());
        logger.info("=".repeat(70));

        metrics.stop();
        metrics.report();
    }
}
