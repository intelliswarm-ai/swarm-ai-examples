package ai.intelliswarm.swarmai.examples.agenttesting;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent Testing & Evaluation Workflow -- evaluates agent output quality with a
 * dedicated evaluator agent, then produces a scored report card.
 *
 * Pipeline (SEQUENTIAL):
 *   1. Content Writer  -- writes a short article on the requested topic
 *   2. Quality Evaluator -- scores the article on 5 criteria (0-10 each)
 *
 * After execution the workflow parses the evaluator's structured output,
 * prints a per-criterion report card, and determines PASS/FAIL based on
 * whether the average score meets the threshold (>= 7).
 *
 * Usage: java -jar swarmai-framework.jar agent-testing "cloud-native architecture"
 */
@Component
public class AgentTestingWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(AgentTestingWorkflow.class);
    private static final double PASS_THRESHOLD = 7.0;

    private static final String[] CRITERIA = {
            "Accuracy", "Completeness", "Clarity", "Evidence", "Relevance"
    };

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;

    public AgentTestingWorkflow(ChatClient.Builder chatClientBuilder,
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
                : "the impact of large language models on software engineering";

        logger.info("\n" + "=".repeat(80));
        logger.info("AGENT TESTING & EVALUATION WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:     {}", topic);
        logger.info("Pipeline:  Content Writer -> Quality Evaluator");
        logger.info("Criteria:  Accuracy | Completeness | Clarity | Evidence | Relevance");
        logger.info("Threshold: avg >= {}", PASS_THRESHOLD);
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("agent-testing");
        metrics.start();

        // --- Content filtering hook: blocks prompts that contain disallowed terms ---
        ToolHook contentFilterHook = buildContentFilterHook();

        // --- Agents ---
        Agent writer = Agent.builder()
                .role("Content Writer")
                .goal("Write a well-structured, factual short article (300-500 words) " +
                      "about the given topic. Include specific examples and evidence.")
                .backstory("You are an experienced technical writer known for clear, " +
                           "accurate, and evidence-backed articles.")
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(0.7)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .toolHook(contentFilterHook)
                .verbose(true)
                .build();

        Agent evaluator = Agent.builder()
                .role("Quality Evaluator")
                .goal("Score the provided article on five criteria, each 0-10. " +
                      "Be rigorous and provide a one-line justification per score.")
                .backstory("You are a senior editorial reviewer with 20 years of experience. " +
                           "You evaluate content objectively against strict quality rubrics.")
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(0.2)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        // --- Tasks ---
        Task writeTask = Task.builder()
                .description(String.format(
                        "Write a short article (300-500 words) about: %s\n\n" +
                        "Requirements:\n" +
                        "- Hook opening that establishes importance\n" +
                        "- 2-3 body sections with specific examples or data points\n" +
                        "- Concise conclusion with a forward-looking statement\n" +
                        "- Markdown format", topic))
                .expectedOutput("A 300-500 word markdown article with examples")
                .agent(writer)
                .build();

        Task evaluateTask = Task.builder()
                .description("Evaluate the article above on these five criteria.\n\n" +
                        "For EACH criterion, respond on its own line as:\n" +
                        "  CRITERION_NAME: SCORE/10 - justification\n\n" +
                        "Criteria:\n" +
                        "  Accuracy: Are claims factual and verifiable?\n" +
                        "  Completeness: Are key aspects of the topic covered?\n" +
                        "  Clarity: Is the writing clear, well-structured, and readable?\n" +
                        "  Evidence: Are claims supported with specific examples or data?\n" +
                        "  Relevance: Does the article stay focused on the topic?\n\n" +
                        "End with a line: OVERALL: brief summary of quality assessment.")
                .expectedOutput("Five scored criteria lines and an overall summary")
                .agent(evaluator)
                .dependsOn(writeTask)
                .build();

        // --- Swarm ---
        Swarm swarm = Swarm.builder()
                .agent(writer)
                .agent(evaluator)
                .task(writeTask)
                .task(evaluateTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        // --- Execute ---
        long t0 = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(Map.of("topic", topic));
        long durationMs = System.currentTimeMillis() - t0;
        metrics.stop();

        // --- Parse evaluation scores ---
        List<TaskOutput> outputs = result.getTaskOutputs();
        String evaluatorOutput = outputs.size() >= 2
                ? outputs.get(outputs.size() - 1).getRawOutput()
                : result.getFinalOutput();

        Map<String, Integer> scores = parseScores(evaluatorOutput);
        double average = scores.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
        boolean passed = average >= PASS_THRESHOLD;

        // --- Report Card ---
        logger.info("\n" + "=".repeat(80));
        logger.info("EVALUATION REPORT CARD");
        logger.info("=".repeat(80));
        logger.info("Topic: {}", topic);
        logger.info("");
        logger.info(String.format("  %-15s | %-6s | %s", "Criterion", "Score", "Bar"));
        logger.info("  " + "-".repeat(50));
        for (String criterion : CRITERIA) {
            int score = scores.getOrDefault(criterion, 0);
            String bar = "#".repeat(score) + ".".repeat(10 - score);
            logger.info(String.format("  %-15s | %2d/10  | %s", criterion, score, bar));
        }
        logger.info("  " + "-".repeat(50));
        logger.info(String.format("  %-15s | %4.1f   | %s",
                "AVERAGE", average, passed ? "PASS" : "FAIL"));
        logger.info("");
        logger.info("Verdict: {}", passed
                ? "PASSED -- article meets quality threshold"
                : "FAILED -- article needs improvement (avg < " + PASS_THRESHOLD + ")");
        logger.info("Duration: {} ms ({} sec)", durationMs, durationMs / 1000);
        logger.info("Tasks:   {}", outputs.size());
        logger.info("Success: {}", result.isSuccessful());
        logger.info("=".repeat(80));

        metrics.report();
    }

    // =========================================================================
    // Helpers (package-visible for unit testing)
    // =========================================================================

    /**
     * Parses evaluator output for criterion scores.
     * Expects lines like: "Accuracy: 8/10 - explanation" or "ACCURACY: 8"
     */
    static Map<String, Integer> parseScores(String evaluatorOutput) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        if (evaluatorOutput == null) return scores;

        for (String criterion : CRITERIA) {
            Pattern pattern = Pattern.compile(
                    "(?i)" + criterion + "\\s*:\\s*(\\d{1,2})(?:/10)?");
            Matcher matcher = pattern.matcher(evaluatorOutput);
            if (matcher.find()) {
                int score = Integer.parseInt(matcher.group(1));
                scores.put(criterion, Math.min(10, Math.max(0, score)));
            }
        }
        return scores;
    }

    /**
     * Builds a content filter ToolHook that denies tool calls whose input
     * contains blocked terms (e.g., competitor proprietary data requests).
     */
    static ToolHook buildContentFilterHook() {
        return new ToolHook() {
            private static final List<String> BLOCKED_TERMS = List.of(
                    "proprietary", "confidential", "internal-only"
            );

            @Override
            public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                String params = ctx.inputParams() != null ? ctx.inputParams().toString() : "";
                String paramsLower = params.toLowerCase();
                for (String term : BLOCKED_TERMS) {
                    if (paramsLower.contains(term)) {
                        return ToolHookResult.deny(
                                "Content filter: blocked term '" + term + "' detected in tool input");
                    }
                }
                return ToolHookResult.allow();
            }
        };
    }

    /** IDE entry point. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"agent-testing"});
    }
}
