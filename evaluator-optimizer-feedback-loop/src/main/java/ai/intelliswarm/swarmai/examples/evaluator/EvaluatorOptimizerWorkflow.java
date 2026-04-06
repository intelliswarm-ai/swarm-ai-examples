package ai.intelliswarm.swarmai.examples.evaluator;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final int MAX_ITERATIONS = 3;
    private static final int QUALITY_THRESHOLD = 80;

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

                // GENERATE: Content Writer produces initial draft
                .addNode("generate", state -> {
                    Agent writer = buildAgent("Content Writer",
                            "Write a well-structured article: intro, 3-4 body sections, conclusion. 400-600 words.",
                            "Award-winning writer. Balances accessibility with depth.", 0.7, metrics);
                    TaskOutput out = writer.executeTask(Task.builder()
                            .description("Write a comprehensive article about: " + state.valueOrDefault("topic", "")
                                    + "\n\nInclude: hook opening, 3-4 headed sections with specifics, "
                                    + "forward-looking conclusion. Markdown format, 400-600 words.")
                            .expectedOutput("Structured markdown article").agent(writer).build(), List.of());
                    logger.info("  [generate] Draft: {} words", out.getRawOutput().split("\\s+").length);
                    return Map.of("content", out.getRawOutput(), "iteration", 1L);
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
                                    "Evaluate this article (iteration %d):\n\n---\n%s\n---\n\n"
                                    + "Respond EXACTLY as:\nSCORE: [0-100]\nSTRENGTHS: [bullets]\n"
                                    + "WEAKNESSES: [bullets]\nPRIORITY_FIX: [single improvement]",
                                    iter, trunc(state.valueOrDefault("content", ""), 3000)))
                            .expectedOutput("SCORE, STRENGTHS, WEAKNESSES, PRIORITY_FIX")
                            .agent(evaluator).build(), List.of());
                    int score = extractScore(out.getRawOutput());
                    String fb = String.format("[Iter %d] Score: %d -- %s",
                            iter, score, extractPriorityFix(out.getRawOutput()));
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

                // OPTIMIZE: improve content based on feedback
                .addNode("optimize", state -> {
                    int iter = state.valueOrDefault("iteration", 1);
                    @SuppressWarnings("unchecked")
                    List<String> allFb = state.<List<String>>value("feedback").orElse(List.of());
                    String fbText = !allFb.isEmpty() ? String.join("\n", allFb) : "(none)";

                    Agent optimizer = buildAgent("Content Optimizer",
                            "Improve the article addressing every weakness. Keep strengths. Output full article.",
                            "Master editor. Surgical improvements tied to feedback. Never rewrites from scratch.", 0.5, metrics);
                    TaskOutput out = optimizer.executeTask(Task.builder()
                            .description(String.format(
                                    "Improve this article about '%s':\n\n---CONTENT---\n%s\n---\n\n"
                                    + "---FEEDBACK---\n%s\n---\n\n"
                                    + "Address PRIORITY_FIX first, then each weakness. "
                                    + "Preserve strengths. Output COMPLETE improved article in markdown.",
                                    state.valueOrDefault("topic", ""), trunc(state.valueOrDefault("content", ""), 2500),
                                    trunc(fbText, 1000)))
                            .expectedOutput("Complete improved article").agent(optimizer).build(), List.of());
                    logger.info("  [optimize] Improved: {} words (iteration {})",
                            out.getRawOutput().split("\\s+").length, iter + 1);
                    return Map.of("content", out.getRawOutput(), "iteration", (long)(iter + 1));
                })
                .addEdge("optimize", "evaluate")

                // FINALIZE: Final Editor polishes approved content
                .addNode("finalize", state -> {
                    Agent editor = buildAgent("Final Editor",
                            "Polish content: fix grammar, smooth transitions, consistent formatting. No substance changes.",
                            "Meticulous copy editor. Catches typos, ensures consistent markdown.", 0.1, metrics);
                    TaskOutput out = editor.executeTask(Task.builder()
                            .description(String.format(
                                    "Final polish (score: %d/100, %d iterations):\n\n%s\n\n"
                                    + "Fix grammar, smooth transitions, consistent formatting. "
                                    + "Do NOT change substance. Output final article only.",
                                    state.valueOrDefault("score", 0),
                                    state.valueOrDefault("iteration", 1),
                                    state.valueOrDefault("content", "")))
                            .expectedOutput("Polished final article").agent(editor).build(), List.of());
                    logger.info("  [finalize] Polished: {} words", out.getRawOutput().split("\\s+").length);
                    return Map.of("content", out.getRawOutput());
                })
                .addEdge("finalize", SwarmGraph.END)
                .stateSchema(schema)
                .compileOrThrow();

        // ---- Execute ----
        AgentState initialState = AgentState.of(schema, Map.of("topic", topic));
        logger.info("\nExecuting evaluator-optimizer pipeline...\n");

        long t0 = System.currentTimeMillis();
        SwarmOutput result = compiled.kickoff(initialState);
        long durationMs = System.currentTimeMillis() - t0;
        metrics.stop();

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
                .toolHook(metrics.metricsHook()).verbose(true).build();
    }

    private static int extractScore(String text) {
        if (text == null) return 50;
        Matcher m = Pattern.compile("SCORE:\\s*(\\d+)").matcher(text.toUpperCase());
        if (m.find()) return Math.min(100, Math.max(0, Integer.parseInt(m.group(1))));
        m = Pattern.compile("(?i)score[^\\d]{0,10}(\\d{1,3})").matcher(text);
        if (m.find()) return Math.min(100, Math.max(0, Integer.parseInt(m.group(1))));
        return 50;
    }

    private static String extractPriorityFix(String text) {
        if (text == null) return "(no feedback)";
        for (String marker : new String[]{"PRIORITY_FIX:", "PRIORITY FIX:"}) {
            int idx = text.toUpperCase().indexOf(marker);
            if (idx >= 0) {
                int start = idx + marker.length();
                int end = text.indexOf("\n", start);
                return text.substring(start, end == -1 ? text.length() : end).trim();
            }
        }
        return "(no specific fix)";
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
