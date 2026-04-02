package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates a single agent using a tool.
 *
 * ONE agent, ONE task, ONE tool (CalculatorTool).
 * The agent is given a math problem and uses the calculator to solve it.
 */
@Component
public class ToolCallingExample {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallingExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final CalculatorTool calculatorTool;

    public ToolCallingExample(ChatClient.Builder chatClientBuilder,
                              ApplicationEventPublisher eventPublisher,
                              CalculatorTool calculatorTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.calculatorTool = calculatorTool;
    }

    public void run(String... args) throws Exception {
        ChatClient chatClient = chatClientBuilder.build();
        String problem = args.length > 0 ? String.join(" ", args)
                : "What is the compound interest on $10000 at 5% for 3 years?";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("tool-calling");
        metrics.start();

        // Agent with one tool -- the CalculatorTool is injected by Spring
        Agent mathTutor = Agent.builder()
                .role("Math Tutor")
                .goal("Solve math problems step by step, using the calculator tool for precise arithmetic")
                .backstory("You are a patient math tutor who explains every step clearly. "
                         + "You always use the calculator tool rather than doing mental math.")
                .chatClient(chatClient)
                .tools(List.of(calculatorTool))
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        Task solve = Task.builder()
                .description("Solve the following math problem step by step: " + problem)
                .expectedOutput("A step-by-step solution with the final answer clearly stated")
                .agent(mathTutor)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(mathTutor)
                .task(solve)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput result = swarm.kickoff(Map.of("problem", problem));

        logger.info("\n=== Result ===");
        logger.info("{}", result.getFinalOutput());

        metrics.stop();
        metrics.report();
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"tool-calling"});
    }
}
