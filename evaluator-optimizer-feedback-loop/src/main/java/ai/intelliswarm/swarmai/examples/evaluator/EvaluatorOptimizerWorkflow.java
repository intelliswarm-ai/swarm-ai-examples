package ai.intelliswarm.swarmai.examples.evaluator;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.Channels;
import ai.intelliswarm.swarmai.state.CompiledSwarm;
import ai.intelliswarm.swarmai.state.StateSchema;
import ai.intelliswarm.swarmai.state.SwarmGraph;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Evaluator-Optimizer Workflow -- generate, evaluate, improve loop via SwarmGraph.
 *
 * Graph topology:
 *   [START] -> [generate] -> [evaluate] --(score >= 80)--> [finalize] -> [END]
 *                                  |                                    ^
 *                                  +----(score < 80)----> [optimize] --+
 *                                  +----(iter >= 3)-----> [finalize]
 *
 * State channels:
 *   content   (lastWriteWins String)  -- current draft
 *   score     (lastWriteWins Integer) -- quality score 0-100
 *   feedback  (appender String)       -- accumulated feedback across iterations
 *   iteration (counter Integer)       -- iteration tracker
 *   topic     (lastWriteWins String)  -- original topic
 *
 * Usage: java -jar swarmai-framework.jar evaluator-optimizer "sustainable energy"
 */
@Component
public class EvaluatorOptimizerWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(EvaluatorOptimizerWorkflow.class);
    @Autowired private LLMJudge judge;
    private static final int MAX_ITERATIONS = 3;
    private static final int QUALITY_THRESHOLD = 80;
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    // Per-node colors so the live console output stays visually distinct as
    // the loop iterates. The evaluate node intentionally stays non-streaming
    // (its output is a typed JSON EvaluationResult and streaming JSON
    // produces unreadable partial-token output).
    private static final String C_RESET    = "[0m";
    private static final String C_GENERATE = "[32m"; // green
    private static final String C_OPTIMIZE = "[33m"; // yellow
    private static final String C_FINALIZE = "[36m"; // cyan
    private static final String C_OUTLIERS = "[35m"; // magenta

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;

    public EvaluatorOptimizerWorkflow(ChatClient.Builder chatClientBuilder,
                                      ApplicationEventPublisher eventPublisher) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
    }

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public void run(String... args) throws Exception {
        String topic = args.length > 0
                ? String.join(" ", args)
                : "The future of sustainable energy and its impact on global economies";

        logger.info("\n" + "=".repeat(80));
        logger.info("EVALUATOR-OPTIMIZER WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:      {}", topic);
        logger.info("Pattern:    Generate -> Evaluate -> Optimize (loop until score >= {})", QUALITY_THRESHOLD);
        logger.info("Max Iters:  {}", MAX_ITERATIONS);
        logger.info("Features:   SwarmGraph | Conditional Edges | Feedback Loop | Quality Gate");
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("evaluator-optimizer");
        metrics.start();

        // ---- State Schema ----
        StateSchema schema = StateSchema.builder()
                .channel("content",   Channels.<String>lastWriteWins())
                .channel("score",     Channels.<Integer>lastWriteWins())
                .channel("feedback",  Channels.<String>appender())
                .channel("iteration", Channels.counter())
                .channel("topic",     Channels.<String>lastWriteWins())
                .allowUndeclaredKeys(true)
                .build();

        // ---- Build the Graph ----

        // Placeholder agent and task required to pass SwarmGraph compile-time validation.
        // Actual work is done inside addNode lambdas using inline agents.
        Agent placeholderAgent = Agent.builder()
                .role("Evaluator-Optimizer Router")
                .goal("Route content through the evaluate-optimize loop")
                .backstory("You route content through an iterative quality improvement pipeline.")
                .chatClient(chatClient).build();
        Task placeholderTask = Task.builder()
                .description("Process content through the evaluator-optimizer pipeline")
                .expectedOutput("A quality-approved article")
                .agent(placeholderAgent).build();

        CompiledSwarm compiled = SwarmGraph.create()
                .addAgent(placeholderAgent)
                .addTask(placeholderTask)
                .addEdge(SwarmGraph.START, "generate")

                // GENERATE: Content Writer produces initial draft (streamed)
                .addNode("generate", state -> {
                    Agent writer = buildAgent("Content Writer",
                            "Write a well-structured article: intro, 3-4 body sections, conclusion. 400-600 words.",
                            "Award-winning writer. Balances accessibility with depth.", 0.7, metrics);
                    String draft = streamProse(writer, Task.builder()
                            .description("Write a comprehensive article about: " + state.valueOrDefault("topic", "")
                                    + "\n\nInclude: hook opening, 3-4 headed sections with specifics, "
                                    + "forward-looking conclusion. Markdown format, 400-600 words.")
                            .expectedOutput("Structured markdown article").agent(writer).build(),
                            "GENERATE", C_GENERATE);
                    logger.info("  [generate] Draft: {} words", draft.split("\\s+").length);
                    return Map.of("content", draft, "iteration", 1L);
                })
                .addEdge("generate", "evaluate")

                // EVALUATE: Quality Evaluator scores content 0-100
                .addNode("evaluate", state -> {
                    int iter = state.valueOrDefault("iteration", 1);
                    Agent evaluator = buildAgent("Quality Evaluator",
                            "Score content 0-100 with specific, actionable feedback.",
                            "Senior editorial director, 20 years experience. Score 80+ = publishable.", 0.2, metrics);
                    TaskOutput out = evaluator.executeTask(Task.builder()
                            .description(String.format(
                                    "Evaluate this article (iteration %d):\n\n---\n%s\n---",
                                    iter, trunc(state.valueOrDefault("content", ""), 3000)))
                            .expectedOutput("Numeric score 0-100, strengths, weaknesses, and one priority fix")
                            .outputType(EvaluationResult.class)
                            .agent(evaluator).build(), List.of());
                    EvaluationResult e = out.as(EvaluationResult.class);
                    int score = e != null ? Math.max(0, Math.min(100, e.score)) : 50;
                    String priority = e != null && e.priorityFix != null ? e.priorityFix : "(no specific fix)";
                    String fb = String.format("[Iter %d] Score: %d -- %s", iter, score, priority);
                    logger.info("  [evaluate] Iteration {} | Score: {}/100 | {}", iter, score, fb);
                    return Map.of("score", score, "feedback", List.of(fb));
                })

                // CONDITIONAL: route by score and iteration count
                .addConditionalEdge("evaluate", state -> {
                    int score = state.valueOrDefault("score", 0);
                    int iter = state.valueOrDefault("iteration", 1);
                    if (score >= QUALITY_THRESHOLD) {
                        logger.info("  [route] Score {} >= {} -> finalize", score, QUALITY_THRESHOLD);
                        return "finalize";
                    }
                    if (iter >= MAX_ITERATIONS) {
                        logger.info("  [route] Max iterations reached -> finalize");
                        return "finalize";
                    }
                    logger.info("  [route] Score {} < {} (iter {}/{}) -> optimize",
                            score, QUALITY_THRESHOLD, iter, MAX_ITERATIONS);
                    return "optimize";
                })

                // OPTIMIZE: improve content based on feedback (streamed)
                .addNode("optimize", state -> {
                    int iter = state.valueOrDefault("iteration", 1);
                    @SuppressWarnings("unchecked")
                    List<String> allFb = state.<List<String>>value("feedback").orElse(List.of());
                    String fbText = !allFb.isEmpty() ? String.join("\n", allFb) : "(none)";

                    Agent optimizer = buildAgent("Content Optimizer",
                            "Improve the article addressing every weakness. Keep strengths. Output full article.",
                            "Master editor. Surgical improvements tied to feedback. Never rewrites from scratch.", 0.5, metrics);
                    String improved = streamProse(optimizer, Task.builder()
                            .description(String.format(
                                    "Improve this article about '%s':\n\n---CONTENT---\n%s\n---\n\n"
                                    + "---FEEDBACK---\n%s\n---\n\n"
                                    + "Address PRIORITY_FIX first, then each weakness. "
                                    + "Preserve strengths. Output COMPLETE improved article in markdown.",
                                    state.valueOrDefault("topic", ""), trunc(state.valueOrDefault("content", ""), 2500),
                                    trunc(fbText, 1000)))
                            .expectedOutput("Complete improved article").agent(optimizer).build(),
                            "OPTIMIZE iter " + (iter + 1), C_OPTIMIZE);
                    logger.info("  [optimize] Improved: {} words (iteration {})",
                            improved.split("\\s+").length, iter + 1);
                    return Map.of("content", improved, "iteration", (long)(iter + 1));
                })
                .addEdge("optimize", "evaluate")

                // FINALIZE: Final Editor polishes approved content (streamed)
                .addNode("finalize", state -> {
                    Agent editor = buildAgent("Final Editor",
                            "Polish content: fix grammar, smooth transitions, consistent formatting. No substance changes.",
                            "Meticulous copy editor. Catches typos, ensures consistent markdown.", 0.1, metrics);
                    String polished = streamProse(editor, Task.builder()
                            .description(String.format(
                                    "Final polish (score: %d/100, %d iterations):\n\n%s\n\n"
                                    + "Fix grammar, smooth transitions, consistent formatting. "
                                    + "Do NOT change substance. Output final article only.",
                                    state.valueOrDefault("score", 0),
                                    state.valueOrDefault("iteration", 1),
                                    state.valueOrDefault("content", "")))
                            .expectedOutput("Polished final article").agent(editor).build(),
                            "FINALIZE", C_FINALIZE);
                    logger.info("  [finalize] Polished: {} words", polished.split("\\s+").length);
                    return Map.of("content", polished);
                })
                .addEdge("finalize", "investigate_outliers")

                // INVESTIGATE_OUTLIERS: drill into specific examples, edge cases, and counter-arguments
                // that the main synthesis may have glossed over. Appends an 'Outliers and Specific
                // Examples' section to the final article.
                .addNode("investigate_outliers", state -> {
                    Agent investigator = buildAgent("Outlier Investigator",
                            "Examine the finalized article and drill into specific examples, edge cases, "
                            + "counter-examples, and unusual data points the main narrative glossed over. "
                            + "Append a new section titled 'Outliers and Specific Examples'.",
                            "You are an investigative researcher who refuses to accept generalizations. "
                            + "You hunt for the specific cases, named examples, and edge conditions that "
                            + "complicate the headline story. You cite concrete numbers, named entities, "
                            + "and unusual scenarios. You NEVER restate the main article — you only add.", 0.3, metrics);
                    String outlierSection = streamProse(investigator, Task.builder()
                            .description(String.format(
                                    "Review this finalized article about '%s':\n\n---\n%s\n---\n\n"
                                    + "Produce ONLY a new markdown section titled EXACTLY:\n\n"
                                    + "## Outliers and Specific Examples\n\n"
                                    + "Under this heading include:\n"
                                    + "1. **Specific Named Examples** (3-5 concrete cases with details)\n"
                                    + "2. **Edge Cases & Exceptions** (scenarios that complicate the main narrative)\n"
                                    + "3. **Counter-Examples** (cases where the main thesis does not hold)\n"
                                    + "4. **Unusual Data Points** (statistics or trends that warrant attention)\n"
                                    + "5. **Why It Matters** (1-2 sentences on implications)\n\n"
                                    + "Do NOT rewrite or restate the main article. Output ONLY the new section.",
                                    trunc(state.valueOrDefault("topic", ""), 200),
                                    trunc(state.valueOrDefault("content", ""), 3000)))
                            .expectedOutput("A markdown section titled 'Outliers and Specific Examples'")
                            .agent(investigator).build(),
                            "OUTLIERS", C_OUTLIERS);
                    String combined = state.valueOrDefault("content", "") + "\n\n" + outlierSection;
                    logger.info("  [investigate_outliers] Outlier section: {} words",
                            outlierSection == null ? 0 : outlierSection.split("\\s+").length);
                    return Map.of("content", combined);
                })
                .addEdge("investigate_outliers", SwarmGraph.END)
                .stateSchema(schema)
                .compileOrThrow();

        // ---- Execute ----
        AgentState initialState = AgentState.of(schema, Map.of("topic", topic));
        logger.info("\nExecuting evaluator-optimizer pipeline...\n");

        long t0 = System.currentTimeMillis();
        SwarmOutput result = compiled.kickoff(initialState);
        long durationMs = System.currentTimeMillis() - t0;
        metrics.stop();

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("evaluator-optimizer", "Generate-evaluate-optimize loop with outlier investigation via SwarmGraph", result.getFinalOutput(),
                result.isSuccessful(), durationMs,
                5, 5, "SWARM_GRAPH", "evaluator-optimizer-feedback-loop");
        }

        // ---- Results ----
        logger.info("\n" + "=".repeat(80));
        logger.info("EVALUATOR-OPTIMIZER COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Topic:       {}", topic);
        logger.info("Successful:  {}", result.isSuccessful());
        logger.info("Tasks:       {}", result.getTaskOutputs().size());
        logger.info("Duration:    {} ms ({} sec)", durationMs, durationMs / 1000);

        logger.info("\nFINAL ARTICLE:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
        metrics.report();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Agent buildAgent(String role, String goal, String backstory,
                             double temp, WorkflowMetricsCollector metrics) {
        return Agent.builder().role(role).goal(goal).backstory(backstory)
                .chatClient(chatClient).maxTurns(1).temperature(temp)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook()).verbose(false).build();
    }

    /**
     * Stream a prose-producing task to {@code System.out} with a colored banner,
     * and return the accumulated text once the agent finishes. Used by the
     * generate / optimize / finalize / outlier nodes — but NOT by evaluate,
     * whose output is a typed {@link EvaluationResult} JSON object.
     */
    private String streamProse(Agent agent, Task task, String label, String color) {
        System.out.printf("%n%s>>> %s <<<%s%n%s", color, label, C_RESET, color);
        System.out.flush();

        StringBuilder accum = new StringBuilder();
        agent.executeTaskStreaming(task, List.of())
                .doOnNext(evt -> {
                    if (evt instanceof AgentEvent.TextDelta d) {
                        System.out.print(d.text());
                        System.out.flush();
                        accum.append(d.text());
                    } else if (evt instanceof AgentEvent.AgentError e) {
                        System.out.print(C_RESET);
                        System.out.println();
                        logger.error("[{}] error: {} - {}", label, e.exceptionType(), e.message());
                    }
                })
                .blockLast(STREAM_TIMEOUT);

        System.out.print(C_RESET);
        System.out.println();
        System.out.flush();
        return accum.toString();
    }

    /**
     * Typed evaluation output for the {@code evaluate} node. The framework
     * auto-injects the JSON schema into the prompt and Spring AI's
     * BeanOutputConverter parses the response — no regex fallback needed.
     */
    public static class EvaluationResult {
        public int score;                // 0-100
        public java.util.List<String> strengths;
        public java.util.List<String> weaknesses;
        public String priorityFix;       // single highest-leverage improvement
        public EvaluationResult() {}
    }

    private static String trunc(String s, int n) {
        return s == null ? "(null)" : s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /** IDE entry point. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"evaluator-optimizer"});
    }
}
