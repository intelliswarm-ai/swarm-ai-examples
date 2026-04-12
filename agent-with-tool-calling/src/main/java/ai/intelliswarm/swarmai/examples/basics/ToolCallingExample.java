package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import ai.intelliswarm.swarmai.tool.common.HttpRequestTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import ai.intelliswarm.swarmai.judge.LLMJudge;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates genuine multi-agent tool utility.
 *
 * THREE agents, THREE tasks, TWO complementary tools:
 *   1. Calculator Agent   -- uses CalculatorTool for precise arithmetic the LLM
 *                            would otherwise approximate or hallucinate.
 *   2. Data Fetcher Agent -- uses HttpRequestTool to pull LIVE data from a
 *                            real public API (GitHub repo stats). This is data
 *                            the LLM cannot know without tool access.
 *   3. Synthesizer Agent  -- combines the calculator output and the fetched
 *                            live data into a single, grounded report.
 *
 * Task chain (SEQUENTIAL): calc -> fetch -> synthesize.
 * The synthesizer depends on BOTH the calc task and the fetch task, so it
 * receives both upstream outputs as context.
 */
@Component
public class ToolCallingExample {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallingExample.class);
    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final CalculatorTool calculatorTool;
    private final HttpRequestTool httpRequestTool;

    public ToolCallingExample(ChatClient.Builder chatClientBuilder,
                              ApplicationEventPublisher eventPublisher,
                              CalculatorTool calculatorTool,
                              HttpRequestTool httpRequestTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.calculatorTool = calculatorTool;
        this.httpRequestTool = httpRequestTool;
    }

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        ChatClient chatClient = chatClientBuilder.build();
        String problem = args.length > 0 ? String.join(" ", args)
                : "What is the compound interest on $10000 at 5% for 3 years?";
        String repoApiUrl = "https://api.github.com/repos/intelliswarm-ai/swarm-ai";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("tool-calling");
        metrics.start();

        // Agent 1: Calculator -- uses CalculatorTool for precise arithmetic
        Agent calculatorAgent = Agent.builder()
                .role("Calculator Agent")
                .goal("Solve numerical problems with exact precision using the calculator tool")
                .backstory("You are a meticulous quantitative analyst. You NEVER do mental math. "
                         + "You always invoke the calculator tool for every arithmetic step so "
                         + "results are exact and auditable.")
                .chatClient(chatClient)
                .tools(List.of(calculatorTool))
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        // Agent 2: Data Fetcher -- uses HttpRequestTool to pull live data from a public API
        Agent dataFetcherAgent = Agent.builder()
                .role("Data Fetcher Agent")
                .goal("Fetch real, live data from public REST APIs and extract the relevant fields")
                .backstory("You are a data integration specialist. You use the http_request tool "
                         + "to call public APIs and return structured facts (never invent numbers). "
                         + "When asked about a GitHub repository, you GET the repo API endpoint "
                         + "and extract stars, forks, open issues, and last-updated timestamp.")
                .chatClient(chatClient)
                .tools(List.of(httpRequestTool))
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        // Agent 3: Synthesizer -- no tools, just combines the two upstream outputs
        Agent synthesizerAgent = Agent.builder()
                .role("Synthesizer Agent")
                .goal("Combine the calculator's precise result and the fetched live data "
                    + "into a single cohesive report")
                .backstory("You are an executive-summary writer. You receive two upstream "
                         + "outputs -- a numeric computation and a block of live API data -- "
                         + "and you weave them into one short, decision-ready report that "
                         + "cites BOTH sources explicitly.")
                .chatClient(chatClient)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        // Task 1: Precise math via CalculatorTool
        Task calcTask = Task.builder()
                .description("Solve the following math problem using the calculator tool for "
                           + "every arithmetic operation: " + problem + ". Show each call you make.")
                .expectedOutput("A step-by-step solution with the final numeric answer, "
                              + "each intermediate value computed via the calculator tool.")
                .agent(calculatorAgent)
                .build();

        // Task 2: Live data via HttpRequestTool
        Task fetchTask = Task.builder()
                .description("Use the http_request tool to GET " + repoApiUrl + " and extract "
                           + "these fields from the JSON response: full_name, stargazers_count, "
                           + "forks_count, open_issues_count, and updated_at. Report the exact values.")
                .expectedOutput("A concise summary of the live GitHub repository stats "
                              + "(stars, forks, open issues, last-updated) pulled via http_request.")
                .agent(dataFetcherAgent)
                .build();

        // Task 3: Synthesize -- depends on BOTH upstream tasks
        Task synthesisTask = Task.builder()
                .description("Write a single short report that combines (a) the precise numeric "
                           + "result from the Calculator Agent and (b) the live GitHub repository "
                           + "stats from the Data Fetcher Agent. Explicitly label each section "
                           + "with its source tool (calculator / http_request).")
                .expectedOutput("A final report with two clearly-sourced sections: the exact "
                              + "calculation result and the live repo stats, plus a one-line "
                              + "conclusion tying them together.")
                .agent(synthesizerAgent)
                .dependsOn(calcTask)
                .dependsOn(fetchTask)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(calculatorAgent)
                .agent(dataFetcherAgent)
                .agent(synthesizerAgent)
                .task(calcTask)
                .task(fetchTask)
                .task(synthesisTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput result = swarm.kickoff(Map.of("problem", problem, "repo_api_url", repoApiUrl));

        logger.info("\n=== Result ===");
        logger.info("{}", result.getFinalOutput());

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("tool-calling", "Multi-agent workflow: precise math (CalculatorTool) + live data fetching (HttpRequestTool) + synthesis",
                result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startMs,
                3, 3, "SEQUENTIAL", "agent-with-tool-calling");
        }

        metrics.stop();
        metrics.report();
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"tool-calling"});
    }
}
