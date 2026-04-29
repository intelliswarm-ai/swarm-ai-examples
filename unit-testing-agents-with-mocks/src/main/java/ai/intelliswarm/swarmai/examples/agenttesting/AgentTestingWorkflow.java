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
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired private LLMJudge judge;
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
        long startMs = System.currentTimeMillis();
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

        // Outlier Investigator — runs AFTER the evaluator to surface specific weak
        // passages, named examples, and edge cases that the aggregate scores hide.
        Agent outlierInvestigator = Agent.builder()
                .role("Outlier & Edge Case Investigator")
                .goal("After the article is scored, drill into SPECIFIC passages, claims, and " +
                      "examples that deserve targeted attention. Produce a markdown section " +
                      "titled 'Outliers and Specific Examples' that gets appended to the final output.")
                .backstory("You are an investigative editor who refuses to be satisfied with " +
                           "aggregate scores. You pull out the 3-5 specific sentences that most " +
                           "strongly support or undermine the article, name the exact claims that " +
                           "need verification, and surface the edge cases the author ignored.")
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(0.3)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .toolHook(contentFilterHook)
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
                .description("Evaluate the article above on five criteria, each scored 0-10:\n" +
                        "  Accuracy: Are claims factual and verifiable?\n" +
                        "  Completeness: Are key aspects of the topic covered?\n" +
                        "  Clarity: Is the writing clear, well-structured, and readable?\n" +
                        "  Evidence: Are claims supported with specific examples or data?\n" +
                        "  Relevance: Does the article stay focused on the topic?\n\n" +
                        "Provide brief justification for each score and an overall summary.")
                .expectedOutput("Score (0-10) per criterion plus overall summary")
                .outputType(EvaluationScores.class)
                .agent(evaluator)
                .dependsOn(writeTask)
                .build();

        Task outlierTask = Task.builder()
                .description("The article and its evaluation scores are above. Your job is to drill " +
                        "into SPECIFIC passages and edge cases that deserve attention beyond the " +
                        "aggregate scoring. Produce ONLY a markdown section titled EXACTLY:\n\n" +
                        "## Outliers and Specific Examples\n\n" +
                        "Under this heading include:\n" +
                        "1. **Strongest Specific Claim** - quote the single best-supported sentence\n" +
                        "2. **Weakest Specific Claim** - quote the single most dubious sentence\n" +
                        "3. **Named Examples** - list 3 concrete cases/entities/statistics the article cites\n" +
                        "4. **Missing Edge Cases** - 2-3 scenarios or counter-examples the article ignores\n" +
                        "5. **Fact-Check Candidates** - 2-3 claims that a reviewer should verify independently\n\n" +
                        "Do NOT restate the article or the scores. Output ONLY the new section.")
                .expectedOutput("A markdown section titled 'Outliers and Specific Examples' with quotes and named examples")
                .agent(outlierInvestigator)
                .dependsOn(evaluateTask)
                .build();

        // --- Swarm ---
        Swarm swarm = Swarm.builder()
                .agent(writer)
                .agent(evaluator)
                .agent(outlierInvestigator)
                .task(writeTask)
                .task(evaluateTask)
                .task(outlierTask)
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

        // --- Read evaluation scores from the typed output (no regex needed) ---
        List<TaskOutput> outputs = result.getTaskOutputs();
        TaskOutput evaluatorOut = outputs.size() >= 3
                ? outputs.get(1)
                : (outputs.size() >= 2 ? outputs.get(outputs.size() - 1) : null);
        String outlierOutput = outputs.size() >= 3
                ? outputs.get(outputs.size() - 1).getRawOutput()
                : "";

        EvaluationScores typed = evaluatorOut != null ? evaluatorOut.as(EvaluationScores.class) : null;
        Map<String, Integer> scores = scoresFromTyped(typed,
                evaluatorOut != null ? evaluatorOut.getRawOutput() : "");
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
        if (outlierOutput != null && !outlierOutput.isBlank()) {
            logger.info("\n--- Outlier Investigation ---\n{}", outlierOutput);
        }
        logger.info("=".repeat(80));

        String combinedFinalOutput = result.getFinalOutput();
        if (outlierOutput != null && !outlierOutput.isBlank()
                && combinedFinalOutput != null
                && !combinedFinalOutput.contains("Outliers and Specific Examples")) {
            combinedFinalOutput = combinedFinalOutput + "\n\n" + outlierOutput;
        }

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("agent-testing", "Agent output quality scoring with outlier investigation", combinedFinalOutput,
                result.isSuccessful(), System.currentTimeMillis() - startMs,
                3, 3, "SEQUENTIAL", "unit-testing-agents-with-mocks");
        }

        metrics.report();
    }

    // =========================================================================
    // Helpers (package-visible for unit testing)
    // =========================================================================

    /**
     * Typed evaluator output. {@code Task.outputType(EvaluationScores.class)}
     * auto-injects the JSON schema into the prompt, and Spring AI's
     * BeanOutputConverter parses the response — no regex needed.
     */
    public static class EvaluationScores {
        public int accuracy;        // 0-10
        public int completeness;    // 0-10
        public int clarity;         // 0-10
        public int evidence;        // 0-10
        public int relevance;       // 0-10
        public String overall;      // brief summary of quality assessment
        public EvaluationScores() {}
    }

    /**
     * Maps the typed scores into the legacy criterion->score map used by the
     * report-card renderer. Falls back to a parse of the raw text when the
     * typed payload is missing (e.g., legacy mock fixtures in tests).
     */
    static Map<String, Integer> scoresFromTyped(EvaluationScores typed, String rawFallback) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        if (typed != null) {
            scores.put("Accuracy",     clamp10(typed.accuracy));
            scores.put("Completeness", clamp10(typed.completeness));
            scores.put("Clarity",      clamp10(typed.clarity));
            scores.put("Evidence",     clamp10(typed.evidence));
            scores.put("Relevance",    clamp10(typed.relevance));
            return scores;
        }
        // Fallback for tests that feed pre-canned text directly (no JSON).
        return parseScores(rawFallback);
    }

    private static int clamp10(int v) { return Math.min(10, Math.max(0, v)); }

    /** Legacy parser kept so unit tests with text-only fixtures continue to pass. */
    static Map<String, Integer> parseScores(String evaluatorOutput) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        if (evaluatorOutput == null) return scores;
        for (String criterion : CRITERIA) {
            Pattern pattern = Pattern.compile(
                    "(?i)" + criterion + "\\s*:\\s*(\\d{1,2})(?:/10)?");
            Matcher matcher = pattern.matcher(evaluatorOutput);
            if (matcher.find()) {
                int score = Integer.parseInt(matcher.group(1));
                scores.put(criterion, clamp10(score));
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
