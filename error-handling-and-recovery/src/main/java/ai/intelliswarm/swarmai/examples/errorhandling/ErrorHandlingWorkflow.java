package ai.intelliswarm.swarmai.examples.errorhandling;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetExceededException;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Error Handling & Resilience -- demonstrates production-grade failure handling.
 *
 * Runs three independent scenarios:
 *
 *   Scenario 1 - TOOL FAILURE RECOVERY
 *     A ToolHook denies the first tool call (simulating a transient failure),
 *     then allows retries. Shows graceful degradation when a tool is denied.
 *
 *   Scenario 2 - BUDGET ENFORCEMENT
 *     A tight BudgetPolicy (HARD_STOP, 50k tokens, $0.10) throws
 *     BudgetExceededException. Shows catching the exception for partial results.
 *
 *   Scenario 3 - TIMEOUT HANDLING
 *     A short maxExecutionTime (10s) on the Task interrupts the agent.
 *     Shows that timed-out tasks still produce partial output.
 *
 * Usage: java -jar swarmai-framework.jar error-handling
 */
@Component
public class ErrorHandlingWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingWorkflow.class);
    @Autowired private LLMJudge judge;

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;
    private final CalculatorTool calculatorTool;

    public ErrorHandlingWorkflow(ChatClient.Builder chatClientBuilder,
                                 ApplicationEventPublisher eventPublisher,
                                 CalculatorTool calculatorTool) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
        this.calculatorTool = calculatorTool;
    }

    // =========================================================================
    // PUBLIC ENTRY POINT
    // =========================================================================

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        logger.info("\n" + "=".repeat(80));
        logger.info("ERROR HANDLING & RESILIENCE DEMO");
        logger.info("=".repeat(80));
        logger.info("Scenarios: Tool Failure | Budget Enforcement | Timeout Handling");
        logger.info("=".repeat(80));

        String[] names       = {"Tool Failure Recovery", "Budget Enforcement", "Timeout Handling"};
        String[] outcomes    = new String[3];
        String[] recoveries  = new String[3];
        String[] validations = new String[3];

        // --- Scenario 1: Tool Failure ---
        try {
            String toolOutput = runToolFailureScenario();
            outcomes[0]    = "PASSED";
            recoveries[0]  = "Agent completed despite first tool call being denied";
            validations[0] = validateRecovery("Tool Failure Recovery", toolOutput,
                    "The output should contain a compound-interest calculation even though the " +
                    "first tool call was denied.");
        } catch (Exception e) {
            outcomes[0]    = "FAILED";
            recoveries[0]  = "Unhandled: " + e.getMessage();
            validations[0] = "NOT RUN (scenario threw)";
            logger.error("Scenario 1 unexpected error", e);
        }

        // --- Scenario 2: Budget ---
        String budgetOutput = null;
        try {
            budgetOutput  = runBudgetEnforcementScenario();
            outcomes[1]   = "PASSED (within budget)";
            recoveries[1] = "Completed within tight budget";
        } catch (BudgetExceededException e) {
            outcomes[1]   = "CAUGHT BudgetExceededException";
            recoveries[1] = "Graceful degradation -- " + e.getMessage();
            budgetOutput  = "(partial) Budget exceeded before full output: " + e.getMessage();
            logger.info("Scenario 2 budget exception: {}", e.getMessage());
        } catch (Exception e) {
            outcomes[1]   = "FAILED";
            recoveries[1] = "Unhandled: " + e.getMessage();
            logger.error("Scenario 2 unexpected error", e);
        }
        validations[1] = budgetOutput != null
                ? validateRecovery("Budget Enforcement", budgetOutput,
                        "The output should either be a usable partial analysis or a clear " +
                        "indication that the budget guard stopped execution safely.")
                : "NOT RUN (scenario threw)";

        // --- Scenario 3: Timeout ---
        String timeoutOutput = null;
        try {
            timeoutOutput = runTimeoutScenario();
            outcomes[2]   = "PASSED (before timeout)";
            recoveries[2] = "Task finished within the time limit";
        } catch (Exception e) {
            outcomes[2]    = "CAUGHT " + e.getClass().getSimpleName();
            recoveries[2]  = "Partial output preserved";
            timeoutOutput  = "(partial) Timed out: " + e.getMessage();
            logger.info("Scenario 3 timeout: {}", e.getMessage());
        }
        validations[2] = timeoutOutput != null
                ? validateRecovery("Timeout Handling", timeoutOutput,
                        "The output should be partial but still contain some coherent content " +
                        "about quantum computing, proving partial results are preserved.")
                : "NOT RUN (scenario threw)";

        // --- Summary ---
        logger.info("\n" + "=".repeat(80));
        logger.info("RESILIENCE SUMMARY");
        logger.info("=".repeat(80));
        logger.info(String.format("  %-4s | %-25s | %-30s | %-40s | %s",
                "#", "Scenario", "Outcome", "Recovery", "Validator Verdict"));
        logger.info("  " + "-".repeat(130));
        for (int i = 0; i < 3; i++) {
            logger.info(String.format("  %-4d | %-25s | %-30s | %-40s | %s",
                    i + 1, names[i], outcomes[i], recoveries[i],
                    truncate(validations[i], 80)));
        }
        logger.info("=".repeat(80));
        logger.info("KEY TAKEAWAYS:");
        logger.info("  1. ToolHook.deny() rejects tool calls without crashing the agent");
        logger.info("  2. BudgetPolicy(HARD_STOP) throws BudgetExceededException -- catch to degrade gracefully");
        logger.info("  3. Task.maxExecutionTime() prevents runaway tasks; partial results are preserved");
        logger.info("  4. A Recovery Validator agent confirms each scenario's output is actually usable");
        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("error-handling",
                "Tool failure recovery, budget enforcement, timeouts, and per-scenario recovery validation",
                String.join("; ", outcomes) + " | validators: " + String.join("; ", validations),
                true, System.currentTimeMillis() - startMs,
                7, 6, "SEQUENTIAL", "error-handling-and-recovery");
        }
    }

    // =========================================================================
    // SCENARIO 1 -- TOOL FAILURE RECOVERY
    // =========================================================================

    /**
     * A ToolHook denies the FIRST call to any tool, simulating a transient failure
     * (rate limit, network blip). Subsequent calls succeed. The agent must adapt.
     */
    private String runToolFailureScenario() {
        logger.info("\n" + "-".repeat(60));
        logger.info("SCENARIO 1: Tool Failure Recovery");
        logger.info("-".repeat(60));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("error-handling-tool-failure");
        metrics.start();

        AtomicInteger callCount = new AtomicInteger(0);

        ToolHook transientFailureHook = new ToolHook() {
            @Override
            public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                int attempt = callCount.incrementAndGet();
                if (attempt == 1) {
                    logger.warn("[FailureHook] DENIED call #{} to {} -- transient failure",
                            attempt, ctx.toolName());
                    return ToolHookResult.deny(
                            "Service temporarily unavailable (attempt " + attempt + "). Please retry.");
                }
                logger.info("[FailureHook] ALLOWED call #{} to {}", attempt, ctx.toolName());
                return ToolHookResult.allow();
            }

            @Override
            public ToolHookResult afterToolUse(ToolHookContext ctx) {
                if (ctx.hasError()) {
                    logger.warn("[FailureHook] Tool {} error: {}", ctx.toolName(), ctx.error());
                    return ToolHookResult.warn("Tool error -- result may be incomplete");
                }
                return ToolHookResult.allow();
            }
        };

        Agent analyst = Agent.builder()
                .role("Research Analyst")
                .goal("Calculate compound interest using the calculator tool. " +
                      "If a tool call fails, retry or provide your best estimate.")
                .backstory("You handle tool failures gracefully -- acknowledge and adapt.")
                .chatClient(chatClient)
                .tools(List.of(calculatorTool))
                .maxTurns(3)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .toolHook(transientFailureHook)
                .verbose(true)
                .build();

        Task calcTask = Task.builder()
                .description("Calculate compound interest on $10,000 at 7% for 5 years: P*(1+r)^t. " +
                             "Use the calculator tool. If it fails, retry or estimate.")
                .expectedOutput("The calculated amount with step-by-step explanation")
                .agent(analyst)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(analyst)
                .task(calcTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput result = swarm.kickoff(Map.of("topic", "compound interest"));

        metrics.stop();
        logger.info("Result: {}", truncate(result.getFinalOutput(), 300));
        logger.info("Tool calls attempted: {} (first was denied)", callCount.get());
        metrics.report();
        return result.getFinalOutput();
    }

    // =========================================================================
    // SCENARIO 2 -- BUDGET ENFORCEMENT
    // =========================================================================

    /**
     * Tight budget: 50k tokens / $0.10 with HARD_STOP. If exceeded, the framework
     * throws BudgetExceededException. The caller catches it for graceful degradation.
     */
    private String runBudgetEnforcementScenario() {
        logger.info("\n" + "-".repeat(60));
        logger.info("SCENARIO 2: Budget Enforcement (HARD_STOP)");
        logger.info("-".repeat(60));

        BudgetPolicy tightBudget = BudgetPolicy.builder()
                .maxTotalTokens(50_000)
                .maxCostUsd(0.10)
                .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                .warningThresholdPercent(50.0)
                .build();

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("error-handling-budget", tightBudget);
        metrics.start();
        logger.info("Budget: maxTokens=50,000 | maxCost=$0.10 | action=HARD_STOP | warn@50%");

        Agent analyst = Agent.builder()
                .role("Research Analyst")
                .goal("Write a detailed 500-word analysis of AI market trends 2026.")
                .backstory("You are a thorough analyst who writes data-rich, comprehensive reports.")
                .chatClient(chatClient)
                .tools(List.of(calculatorTool))
                .maxTurns(3)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        Task researchTask = Task.builder()
                .description("Write a 500-word AI market analysis for 2026. Cover revenue estimates, " +
                             "key players (OpenAI, Google, Anthropic, Meta), growth rates, and predictions. " +
                             "Use the calculator for numerical projections.")
                .expectedOutput("A 500-word market analysis with numbers and projections")
                .agent(analyst)
                .build();

        Swarm swarm = Swarm.builder()
                .id("budget-test")
                .agent(analyst)
                .task(researchTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(tightBudget)
                .build();

        // May throw BudgetExceededException -- caller handles it
        try {
            SwarmOutput result = swarm.kickoff(Map.of("topic", "AI market 2026"));
            logger.info("Completed within budget. Result: {}", truncate(result.getFinalOutput(), 300));
            return result.getFinalOutput();
        } finally {
            metrics.stop();
            metrics.report();
        }
    }

    // =========================================================================
    // SCENARIO 3 -- TIMEOUT HANDLING
    // =========================================================================

    /**
     * Very short maxExecutionTime (10s) on the Task. If the LLM takes longer, the
     * framework interrupts the agent. Partial output is still available.
     */
    private String runTimeoutScenario() {
        logger.info("\n" + "-".repeat(60));
        logger.info("SCENARIO 3: Timeout Handling (10s limit)");
        logger.info("-".repeat(60));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("error-handling-timeout");
        metrics.start();

        Agent analyst = Agent.builder()
                .role("Research Analyst")
                .goal("Write an extremely thorough, encyclopedic analysis of quantum computing.")
                .backstory("You are known for exhaustive analyses. Always write at maximum detail.")
                .chatClient(chatClient)
                .tools(List.of(calculatorTool))
                .maxTurns(5)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        Task longTask = Task.builder()
                .description("Write an exhaustive 2000-word quantum computing analysis: " +
                             "history, hardware (superconducting/trapped-ion/photonic), " +
                             "algorithms (Shor's, Grover's, VQE), industry players, " +
                             "applications, timeline predictions, and error correction.")
                .expectedOutput("A 2000-word quantum computing analysis")
                .agent(analyst)
                .maxExecutionTime(10_000)  // 10 seconds -- intentionally tight
                .build();

        Swarm swarm = Swarm.builder()
                .agent(analyst)
                .task(longTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        try {
            SwarmOutput result = swarm.kickoff(Map.of("topic", "quantum computing"));
            String output = result.getFinalOutput();
            if (output != null && !output.isEmpty()) {
                logger.info("Produced {} chars (may be partial). Preview: {}",
                        output.length(), truncate(output, 300));
            } else {
                logger.info("No output produced (timed out before content was generated)");
            }
            return output;
        } finally {
            metrics.stop();
            metrics.report();
        }
    }

    // =========================================================================
    // RECOVERY VALIDATOR -- dedicated per-scenario validator agent
    // =========================================================================

    /**
     * Runs a "Recovery Validator" agent task that inspects the scenario output and
     * returns a short verdict on whether recovery actually produced usable output.
     * This is a distinct role from the scenario's own research agent: the validator
     * judges downstream usability, not the work itself.
     */
    private String validateRecovery(String scenarioName, String scenarioOutput, String criteria) {
        logger.info("\n[RecoveryValidator] Scenario: {}", scenarioName);
        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector(
                "error-handling-validator-" + scenarioName.toLowerCase().replace(' ', '-'));
        metrics.start();

        Agent validator = Agent.builder()
                .role("Recovery Validator")
                .goal("Confirm that the recovery path for scenario '" + scenarioName +
                      "' produced a usable, non-trivial output that downstream consumers can " +
                      "rely on, even if partial or degraded.")
                .backstory("You are an SRE specializing in resilience testing. You verify that " +
                           "error-handling paths deliver real value -- not just suppressed " +
                           "exceptions. You issue short, direct verdicts.")
                .chatClient(chatClient)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(false)
                .build();

        Task validationTask = Task.builder()
                .description("Evaluate the following scenario output for usability:\n\n" +
                             "CRITERIA: " + criteria + "\n\n" +
                             "SCENARIO OUTPUT:\n" + truncate(scenarioOutput, 1500) + "\n\n" +
                             "Respond with exactly one of:\n" +
                             "  VALID: <one-sentence reason>\n" +
                             "  DEGRADED-BUT-USABLE: <one-sentence reason>\n" +
                             "  UNUSABLE: <one-sentence reason>")
                .expectedOutput("A single labeled verdict line")
                .agent(validator)
                .maxExecutionTime(30_000)
                .build();

        Swarm swarm = Swarm.builder()
                .id("recovery-validator-" + scenarioName.toLowerCase().replace(' ', '-'))
                .agent(validator)
                .task(validationTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(false)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        String verdict;
        try {
            SwarmOutput out = swarm.kickoff(Map.of("scenario", scenarioName));
            verdict = out.getFinalOutput() != null ? out.getFinalOutput().trim() : "NO VERDICT";
        } catch (Exception e) {
            verdict = "VALIDATOR ERROR: " + e.getMessage();
        } finally {
            metrics.stop();
            metrics.report();
        }
        logger.info("[RecoveryValidator] Verdict: {}", truncate(verdict, 200));
        return verdict;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static String truncate(String text, int maxLen) {
        if (text == null) return "(null)";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"error-handling"});
    }
}
