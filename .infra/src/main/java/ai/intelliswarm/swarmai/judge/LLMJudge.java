package ai.intelliswarm.swarmai.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-Judge: evaluates workflow outputs using an external LLM (OpenAI or Anthropic).
 *
 * Uses raw HTTP calls to avoid conflicts with the project's Spring AI autoconfiguration
 * (which is excluded for OpenAI/Anthropic to keep Ollama as the default provider).
 */
@Component
public class LLMJudge {

    private static final Logger logger = LoggerFactory.getLogger(LLMJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final JudgeConfig config;

    public LLMJudge(JudgeConfig config) {
        this.config = config;
    }

    public boolean isAvailable() {
        return config.isAvailable();
    }

    /**
     * Evaluate a workflow's output and save the result.
     *
     * @param workflowName   identifier (e.g. "bare-minimum")
     * @param description    what the workflow is supposed to do
     * @param finalOutput    the raw text output from the workflow
     * @param successful     whether the workflow completed without errors
     * @param executionTimeMs wall-clock time
     * @param agentCount     number of agents used
     * @param taskCount      number of tasks executed
     * @param processType    SEQUENTIAL, PARALLEL, etc.
     * @param exampleDir     directory to save result into (e.g. "hello-world-single-agent")
     * @return the JudgeResult, or null if judging is disabled/unavailable
     */
    public JudgeResult evaluate(String workflowName, String description, String finalOutput,
                                boolean successful, long executionTimeMs,
                                int agentCount, int taskCount, String processType,
                                String exampleDir) {
        if (!isAvailable()) {
            logger.info("[Judge] Skipping evaluation — judge not available (provider={}, enabled={})",
                    config.getProvider(), config.isEnabled());
            return null;
        }

        logger.info("[Judge] Evaluating workflow: {} (judge={}/{}, workflow-model={})",
                workflowName, config.getProvider(), config.getModel(), config.getWorkflowModel());

        try {
            String prompt = buildPrompt(workflowName, description, finalOutput, successful,
                    executionTimeMs, agentCount, taskCount, processType);

            String response = callLLM(prompt);
            JudgeResult result = parseResponse(response, workflowName, successful,
                    executionTimeMs, agentCount, taskCount, processType);

            // Save to example directory with date stamp
            File outputDir = new File(exampleDir, config.getOutputDir());
            String dateStamp = LocalDate.now().toString();
            result.save(outputDir, dateStamp);

            // Also save the raw workflow output as a benchmark for future comparison
            try {
                File benchmarkFile = new File(outputDir, workflowName + "_output_" + dateStamp + ".txt");
                java.nio.file.Files.writeString(benchmarkFile.toPath(),
                        finalOutput != null ? finalOutput : "(no output)");
            } catch (Exception ex) {
                logger.warn("[Judge] Failed to save benchmark output: {}", ex.getMessage());
            }

            logger.info("[Judge] Result saved to {}/{}_judge_result_{}.json (score: {}/100)",
                    outputDir.getPath(), workflowName, dateStamp, result.getOverallScore());

            printSummary(result);
            return result;
        } catch (Exception e) {
            logger.error("[Judge] Evaluation failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private String buildPrompt(String workflowName, String description, String finalOutput,
                                boolean successful, long executionTimeMs,
                                int agentCount, int taskCount, String processType) {
        String truncatedOutput = finalOutput;
        if (truncatedOutput != null && truncatedOutput.length() > 8000) {
            truncatedOutput = truncatedOutput.substring(0, 8000) + "\n... [truncated]";
        }

        return String.format("""
                You are an expert evaluator performing TWO assessments of a multi-agent workflow \
                built with the SwarmAI Java framework.

                CONTEXT: The underlying workflow model is '%s'. \
                When scoring FRAMEWORK dimensions, do not penalize for LLM text quality. \
                When scoring OUTPUT dimensions, evaluate the output on its own merits but note \
                what could improve via framework changes vs model upgrade.

                WORKFLOW: %s
                DESCRIPTION: %s
                PROCESS TYPE: %s
                AGENTS USED: %d
                TASKS EXECUTED: %d
                EXECUTION TIME: %d ms
                COMPLETED SUCCESSFULLY: %s

                === WORKFLOW OUTPUT ===
                %s
                === END OUTPUT ===

                =====================================================
                PART 1: FRAMEWORK ASSESSMENT (0-100 each)
                =====================================================
                Evaluate what the FRAMEWORK does, independent of LLM quality.

                1. ORCHESTRATION: Agent coordination, routing, handoffs, task dependency resolution. \
                Did the framework execute the workflow pipeline correctly?

                2. TASK_DECOMPOSITION: Is the goal broken into well-structured, distinct agent tasks? \
                Are dependencies correct? Do agent roles avoid overlap?

                3. TOOL_INTEGRATION: Tool management, hooks, permission enforcement, error recovery. \
                Score 50 (neutral) if no tools expected for this workflow.

                4. ERROR_HANDLING: Retries, budget enforcement, timeouts, fallbacks, meaningful errors. \
                Score what the framework PROVIDES, not whether errors occurred.

                5. OBSERVABILITY: Metrics, logging, tracing, budget tracking, event publishing. \
                Can an operator understand and diagnose the execution?

                6. ARCHITECTURE_PATTERN: Is the chosen pattern (sequential/parallel/hierarchical/graph/ \
                self-improving) appropriate? Does the example demonstrate it clearly?

                =====================================================
                PART 2: OUTPUT ASSESSMENT (0-100 each)
                =====================================================
                Now imagine you received the SAME high-level prompt ("%s") as a single \
                direct LLM call (no framework, no agents, just one prompt → one response). \
                Compare the workflow output against what that single call would likely produce.

                7. OUTPUT_QUALITY: Is the workflow output well-written, structured, and informative? \
                Judge the actual content on its own merits.

                8. GOAL_ACHIEVEMENT: Does the output accomplish what the workflow description promised? \
                Is it complete, or does it miss key aspects?

                9. FRAMEWORK_VALUE_ADD: Did the multi-agent orchestration produce a BETTER result \
                than a single LLM call would have? Consider: \
                - Did task decomposition lead to more thorough coverage? \
                - Did specialist agents add depth that a generalist wouldn't? \
                - Did the feedback loop / review / handoff improve quality? \
                - Score >50 means the framework added value, <50 means a single call would have been equivalent or better.

                10. OUTPUT_IMPROVEMENT_POTENTIAL: How much could the output improve by changing \
                the FRAMEWORK or EXAMPLE (not by upgrading the LLM)? \
                - Better task descriptions, more agents, different process type, tool usage? \
                - Score 0-100 where 100 = output is already optimal for this framework design, \
                  0 = major improvements possible via framework/example changes.

                =====================================================
                PART 3: IMPROVEMENT RECOMMENDATIONS
                =====================================================
                For each suggestion, prefix with the owner:
                - FRAMEWORK: fix in swarm-ai core library
                - EXAMPLE: fix in this specific workflow
                - MODEL: would only improve with a better LLM (note but don't prioritize)

                Respond in EXACTLY this JSON format (no markdown fences, just raw JSON):
                {
                  "overallScore": <0-100, weighted: 60%% framework + 40%% output>,
                  "orchestration": <0-100>,
                  "taskDecomposition": <0-100>,
                  "toolIntegration": <0-100>,
                  "errorHandling": <0-100>,
                  "observability": <0-100>,
                  "architecturePattern": <0-100>,
                  "outputQuality": <0-100>,
                  "goalAchievement": <0-100>,
                  "frameworkValueAdd": <0-100>,
                  "outputImprovementPotential": <0-100>,
                  "verdict": "<one sentence: framework maturity + whether multi-agent added value>",
                  "strengths": ["<strength with FRAMEWORK/EXAMPLE/OUTPUT prefix>", ...],
                  "weaknesses": ["<weakness with FRAMEWORK/EXAMPLE/OUTPUT prefix>", ...],
                  "frameworkIssues": ["<FRAMEWORK: specific bug or limitation>", ...],
                  "exampleIssues": ["<EXAMPLE: specific issue in this workflow>", ...],
                  "improvements": ["<FRAMEWORK|EXAMPLE|MODEL: actionable improvement>", ...],
                  "baselineComparison": "<2-3 sentences: would a single LLM call produce equivalent output? What specifically did the multi-agent approach add or fail to add?>"
                }
                """,
                config.getWorkflowModel(),
                workflowName, description, processType, agentCount, taskCount,
                executionTimeMs, successful, truncatedOutput != null ? truncatedOutput : "(no output)",
                description);
    }

    private String callLLM(String prompt) throws Exception {
        if ("openai".equalsIgnoreCase(config.getProvider())) {
            return callOpenAI(prompt);
        } else if ("anthropic".equalsIgnoreCase(config.getProvider())) {
            return callAnthropic(prompt);
        }
        throw new IllegalStateException("Unknown judge provider: " + config.getProvider());
    }

    /**
     * Public helper so other components (e.g. ImprovementAggregator) can reuse the judge LLM
     * for clustering/classification tasks without duplicating HTTP client code.
     */
    public String callLLMDirect(String prompt, int maxTokens) throws Exception {
        if ("openai".equalsIgnoreCase(config.getProvider())) {
            return callOpenAIWithTokens(prompt, maxTokens);
        } else if ("anthropic".equalsIgnoreCase(config.getProvider())) {
            return callAnthropicWithTokens(prompt, maxTokens);
        }
        throw new IllegalStateException("Unknown judge provider: " + config.getProvider());
    }

    private String callOpenAIWithTokens(String prompt, int maxTokens) throws Exception {
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "max_tokens", maxTokens,
                "temperature", 0.1,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + config.getOpenaiApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }
        return MAPPER.readTree(response.body()).at("/choices/0/message/content").asText();
    }

    private String callAnthropicWithTokens(String prompt, int maxTokens) throws Exception {
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", config.getAnthropicApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
        }
        return MAPPER.readTree(response.body()).at("/content/0/text").asText();
    }

    private String callOpenAI(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "max_tokens", 2000,
                "temperature", 0.1,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + config.getOpenaiApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        return root.at("/choices/0/message/content").asText();
    }

    private String callAnthropic(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "max_tokens", 2000,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", config.getAnthropicApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        return root.at("/content/0/text").asText();
    }

    private JudgeResult parseResponse(String response, String workflowName, boolean successful,
                                       long executionTimeMs, int agentCount, int taskCount,
                                       String processType) {
        JudgeResult result = new JudgeResult();
        result.setWorkflowName(workflowName);
        result.setJudgeModel(config.getProvider() + "/" + config.getModel());
        result.setWorkflowModel(config.getWorkflowModel());
        result.setSuccessful(successful);
        result.setExecutionTimeMs(executionTimeMs);
        result.setAgentCount(agentCount);
        result.setTaskCount(taskCount);
        result.setProcessType(processType);

        try {
            // Strip markdown fences if present
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }

            JsonNode root = MAPPER.readTree(json);

            result.setOverallScore(root.path("overallScore").asInt(50));
            result.setOrchestration(root.path("orchestration").asInt(50));
            result.setTaskDecomposition(root.path("taskDecomposition").asInt(50));
            result.setToolIntegration(root.path("toolIntegration").asInt(50));
            result.setErrorHandling(root.path("errorHandling").asInt(50));
            result.setObservability(root.path("observability").asInt(50));
            result.setArchitecturePattern(root.path("architecturePattern").asInt(50));
            result.setVerdict(root.path("verdict").asText("No verdict provided"));
            result.setOutputQuality(root.path("outputQuality").asInt(50));
            result.setGoalAchievement(root.path("goalAchievement").asInt(50));
            result.setFrameworkValueAdd(root.path("frameworkValueAdd").asInt(50));
            result.setOutputImprovementPotential(root.path("outputImprovementPotential").asInt(50));
            result.setStrengths(jsonArrayToList(root.path("strengths")));
            result.setWeaknesses(jsonArrayToList(root.path("weaknesses")));
            result.setFrameworkIssues(jsonArrayToList(root.path("frameworkIssues")));
            result.setExampleIssues(jsonArrayToList(root.path("exampleIssues")));
            result.setImprovements(jsonArrayToList(root.path("improvements")));
            result.setBaselineComparison(root.path("baselineComparison").asText(""));
        } catch (Exception e) {
            logger.warn("[Judge] Failed to parse JSON response, extracting scores manually: {}", e.getMessage());
            result.setOverallScore(extractInt(response, "overallScore", 50));
            result.setOrchestration(extractInt(response, "orchestration", 50));
            result.setTaskDecomposition(extractInt(response, "taskDecomposition", 50));
            result.setToolIntegration(extractInt(response, "toolIntegration", 50));
            result.setErrorHandling(extractInt(response, "errorHandling", 50));
            result.setObservability(extractInt(response, "observability", 50));
            result.setArchitecturePattern(extractInt(response, "architecturePattern", 50));
            result.setVerdict("Parse error - raw response available in logs");
            result.setOutputQuality(extractInt(response, "outputQuality", 50));
            result.setGoalAchievement(extractInt(response, "goalAchievement", 50));
            result.setFrameworkValueAdd(extractInt(response, "frameworkValueAdd", 50));
            result.setOutputImprovementPotential(extractInt(response, "outputImprovementPotential", 50));
            result.setStrengths(List.of());
            result.setWeaknesses(List.of());
            result.setFrameworkIssues(List.of());
            result.setExampleIssues(List.of());
            result.setImprovements(List.of());
            result.setBaselineComparison("");
        }

        return result;
    }

    private List<String> jsonArrayToList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private int extractInt(String text, String key, int fallback) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private void printSummary(JudgeResult result) {
        logger.info("\n" + "=".repeat(70));
        logger.info("JUDGE EVALUATION: {}", result.getWorkflowName());
        logger.info("=".repeat(70));
        logger.info("  Judge Model:           {}", result.getJudgeModel());
        logger.info("  Workflow Model:        {}", result.getWorkflowModel());
        logger.info("  Overall Score:         {}/100", result.getOverallScore());
        logger.info("  --- Framework Assessment ---");
        logger.info("  Orchestration:         {}/100", result.getOrchestration());
        logger.info("  Task Decomposition:    {}/100", result.getTaskDecomposition());
        logger.info("  Tool Integration:      {}/100", result.getToolIntegration());
        logger.info("  Error Handling:        {}/100", result.getErrorHandling());
        logger.info("  Observability:         {}/100", result.getObservability());
        logger.info("  Architecture Pattern:  {}/100", result.getArchitecturePattern());
        logger.info("  --- Output Assessment ---");
        logger.info("  Output Quality:        {}/100", result.getOutputQuality());
        logger.info("  Goal Achievement:      {}/100", result.getGoalAchievement());
        logger.info("  Framework Value-Add:   {}/100", result.getFrameworkValueAdd());
        logger.info("  Improvement Potential: {}/100", result.getOutputImprovementPotential());
        logger.info("  Verdict: {}", result.getVerdict());
        if (result.getBaselineComparison() != null && !result.getBaselineComparison().isEmpty()) {
            logger.info("  Baseline: {}", result.getBaselineComparison());
        }
        if (result.getStrengths() != null && !result.getStrengths().isEmpty()) {
            logger.info("  Strengths:");
            result.getStrengths().forEach(s -> logger.info("    + {}", s));
        }
        if (result.getWeaknesses() != null && !result.getWeaknesses().isEmpty()) {
            logger.info("  Weaknesses:");
            result.getWeaknesses().forEach(w -> logger.info("    - {}", w));
        }
        logger.info("=".repeat(70));
    }
}
