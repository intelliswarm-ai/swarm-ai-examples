package ai.intelliswarm.swarmai.examples.multiprovider;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Multi-Provider Workflow -- model-agnostic design with side-by-side comparison.
 *
 * Runs the same analysis task across different configurations and compares results:
 *   Phase 1: Temperature sweep (0.1, 0.5, 0.9) on the configured model
 *   Phase 2: Model variants via Agent.modelName() (e.g., Ollama models)
 *
 * A comparison table is printed at the end showing output length, token usage,
 * and key themes identified per run.
 *
 * Usage: java -jar swarmai-framework.jar multi-provider "AI agent frameworks"
 */
@Component
public class MultiProviderWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MultiProviderWorkflow.class);
    @Autowired private LLMJudge judge;
    private static final String[] MODEL_VARIANTS = {"mistral:7b", "llama3:8b", "gemma:7b"};
    private static final double[] TEMPERATURES = {0.1, 0.5, 0.9};
    private static final String[] TEMP_LABELS = {"Deterministic (0.1)", "Balanced (0.5)", "Creative (0.9)"};

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public MultiProviderWorkflow(ChatClient.Builder chatClientBuilder,
                                 ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args) : "AI agent frameworks in 2026";

        logger.info("\n" + "=".repeat(80));
        logger.info("MULTI-PROVIDER COMPARISON WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:         {}", topic);
        logger.info("Comparison 1:  Temperature sweep on configured model");
        logger.info("Comparison 2:  Model variants ({}) -- must be available in provider",
                String.join(", ", MODEL_VARIANTS));
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("multi-provider");
        metrics.start();

        // Phase 1: Temperature Sweep
        logger.info("\nPHASE 1: Temperature Sweep (same model, different temperatures)");
        List<RunResult> tempResults = new ArrayList<>();
        for (int i = 0; i < TEMPERATURES.length; i++) {
            logger.info("  >>> Run {}: {}", i + 1, TEMP_LABELS[i]);
            tempResults.add(runAnalysis(topic, null, TEMPERATURES[i], TEMP_LABELS[i], metrics));
        }

        // Phase 2: Model Variants
        logger.info("\nPHASE 2: Model Variants (different models, temperature=0.5)");
        List<RunResult> modelResults = new ArrayList<>();
        for (String model : MODEL_VARIANTS) {
            logger.info("  >>> Model: {}", model);
            modelResults.add(runAnalysis(topic, model, 0.5, model, metrics));
        }

        metrics.stop();
        printComparisonTable("TEMPERATURE SWEEP", tempResults);
        printComparisonTable("MODEL VARIANTS", modelResults);
        printThemeAnalysis(tempResults, modelResults);

        if (judge != null && judge.isAvailable()) {
            // Build combined output summary for judging
            StringBuilder combined = new StringBuilder();
            for (RunResult r : tempResults) {
                combined.append("Temperature ").append(r.temperature).append(": ")
                        .append(r.error != null ? "ERROR: " + r.error : r.wordCount + " words, " + r.sectionCount + " sections").append("\n");
            }
            for (RunResult r : modelResults) {
                combined.append("Model ").append(r.modelName).append(": ")
                        .append(r.error != null ? "ERROR: " + r.error : r.wordCount + " words, " + r.sectionCount + " sections").append("\n");
            }
            judge.evaluate("multi-provider", "Cross-model and temperature comparison workflow",
                    combined.toString(), true, System.currentTimeMillis(),
                    1, 1, "SEQUENTIAL", "multi-llm-provider-switching");
        }

        metrics.report();
    }

    private RunResult runAnalysis(String topic, String modelName, double temperature,
                                 String label, WorkflowMetricsCollector metrics) {
        RunResult r = new RunResult(label, modelName, temperature);
        try {
            long t0 = System.currentTimeMillis();
            ChatClient chatClient = chatClientBuilder.build();

            Agent.Builder ab = Agent.builder()
                    .role("Technology Analyst")
                    .goal("Produce a concise analysis: key players, trends, challenges, 12-month outlook.")
                    .backstory("Senior technology analyst. Data-driven, balanced, identifies risks and opportunities.")
                    .chatClient(chatClient).verbose(false).maxTurns(1).temperature(temperature)
                    .permissionMode(PermissionLevel.READ_ONLY).toolHook(metrics.metricsHook());
            if (modelName != null) ab.modelName(modelName);
            Agent analyst = ab.build();

            Task task = Task.builder()
                    .description("Analyze: " + topic + "\n\nSections: Overview, Key Players (3-5), "
                            + "Current Trends (3), Challenges (2-3), 12-Month Outlook. Under 500 words.")
                    .expectedOutput("Structured 5-section analysis under 500 words")
                    .agent(analyst).outputFormat(OutputFormat.MARKDOWN).maxExecutionTime(120000).build();

            Swarm swarm = Swarm.builder()
                    .id("mp-" + label.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase())
                    .agent(analyst).task(task).process(ProcessType.SEQUENTIAL).verbose(false)
                    .eventPublisher(eventPublisher).budgetTracker(metrics.getBudgetTracker())
                    .budgetPolicy(metrics.getBudgetPolicy()).build();

            SwarmOutput out = swarm.kickoff(Map.of("topic", topic));
            r.fill(out, System.currentTimeMillis() - t0);
            logger.info("    OK: {} words, {} sections, {} ms", r.wordCount, r.sectionCount, r.durationMs);
        } catch (Exception e) {
            r.error = e.getMessage();
            logger.warn("    FAILED: {}", e.getMessage());
        }
        return r;
    }

    private void printComparisonTable(String title, List<RunResult> results) {
        logger.info("\n" + "=".repeat(80));
        logger.info(title);
        logger.info(String.format("  %-22s | %-6s | %-6s | %-5s | %-9s | %-7s | %s",
                "Config", "Status", "Words", "Sects", "Duration", "Tokens", "Themes"));
        logger.info("  " + "-".repeat(78));
        for (RunResult r : results) {
            if (r.error == null) {
                String tok = r.totalTokens > 0 ? String.valueOf(r.totalTokens) : "n/a";
                String th = r.themes.size() > 3
                        ? String.join(", ", r.themes.subList(0, 3)) + "..."
                        : String.join(", ", r.themes);
                logger.info(String.format("  %-22s | %-6s | %-6d | %-5d | %-9s | %-7s | %s",
                        trunc(r.label, 22), "OK", r.wordCount, r.sectionCount,
                        r.durationMs + "ms", tok, trunc(th, 25)));
            } else {
                logger.info(String.format("  %-22s | %-6s | %s",
                        trunc(r.label, 22), "ERROR", trunc(r.error, 50)));
            }
        }
        logger.info("=".repeat(80));
    }

    private void printThemeAnalysis(List<RunResult> temp, List<RunResult> model) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        List<RunResult> all = new ArrayList<>(temp);
        all.addAll(model);
        int ok = 0;
        for (RunResult r : all) {
            if (r.error == null) { ok++; for (String t : r.themes) freq.merge(t, 1, Integer::sum); }
        }
        logger.info("\n" + "=".repeat(80));
        logger.info("THEME FREQUENCY ({} successful runs)", ok);
        int total = ok;
        freq.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(e -> logger.info("  %-20s %s (%d/%d)", e.getKey(),
                        "*".repeat(e.getValue()), e.getValue(), total));
        logger.info("\nTAKEAWAYS:");
        logger.info("  1. Low temperature -> consistent, factual; high -> creative, varied");
        logger.info("  2. Different models emphasize different aspects of the same topic");
        logger.info("  3. Use Agent.modelName() to target specific models within one provider");
        logger.info("=".repeat(80));
    }

    private static int countSections(String text) {
        if (text == null) return 0;
        int c = 0;
        for (String ln : text.split("\n")) { String t = ln.trim(); if (t.startsWith("##") || t.startsWith("**")) c++; }
        return c;
    }

    private static List<String> extractThemes(String text) {
        if (text == null) return List.of();
        String lc = text.toLowerCase();
        Map<String, String> kw = Map.ofEntries(
                Map.entry("autonomous", "Autonomy"), Map.entry("multi-agent", "Multi-Agent"),
                Map.entry("orchestration", "Orchestration"), Map.entry("tool use", "Tool Use"),
                Map.entry("reasoning", "Reasoning"), Map.entry("open source", "Open Source"),
                Map.entry("enterprise", "Enterprise"), Map.entry("safety", "Safety"),
                Map.entry("governance", "Governance"), Map.entry("memory", "Memory"),
                Map.entry("scalab", "Scalability"), Map.entry("hallucin", "Hallucination"));
        List<String> out = new ArrayList<>();
        kw.forEach((k, v) -> { if (lc.contains(k)) out.add(v); });
        return out;
    }

    private static String trunc(String s, int n) {
        return s == null ? "(null)" : s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static class RunResult {
        final String label, modelName; final double temperature;
        String error; int wordCount, sectionCount, totalTokens; long durationMs;
        List<String> themes = List.of();

        RunResult(String label, String modelName, double temperature) {
            this.label = label;
            this.modelName = modelName != null ? modelName : "(default)";
            this.temperature = temperature;
        }

        void fill(SwarmOutput out, long ms) {
            durationMs = ms;
            String text = out.getFinalOutput();
            wordCount = text != null ? text.split("\\s+").length : 0;
            sectionCount = countSections(text);
            themes = extractThemes(text);
            List<TaskOutput> to = out.getTaskOutputs();
            if (to != null && !to.isEmpty())
                totalTokens = (int) (to.get(0).getPromptTokens() + to.get(0).getCompletionTokens());
        }
    }

    /** IDE entry point. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"multi-provider"});
    }
}
