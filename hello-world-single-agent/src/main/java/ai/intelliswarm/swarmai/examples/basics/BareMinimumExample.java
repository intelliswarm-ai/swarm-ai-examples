package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The absolute simplest SwarmAI example.
 *
 * ONE agent, ONE task, SEQUENTIAL process.
 * No tools, no hooks, no special features -- just the bare essentials.
 */
@Component
public class BareMinimumExample {

    private static final Logger logger = LoggerFactory.getLogger(BareMinimumExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public BareMinimumExample(ChatClient.Builder chatClientBuilder,
                              ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        ChatClient chatClient = chatClientBuilder.build();
        String topic = args.length > 0 ? String.join(" ", args) : "artificial intelligence";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("bare-minimum");
        metrics.start();

        // Single agent -- just a role, goal, and backstory
        Agent summarizer = Agent.builder()
                .role("Summarizer")
                .goal("Provide a clear, concise summary of the given topic")
                .backstory("You are an expert at distilling complex topics into easy-to-understand summaries.")
                .chatClient(chatClient)
                .verbose(true)
                .build();

        // Single task -- describe what needs to be done
        Task summarize = Task.builder()
                .description("Summarize the latest trends in " + topic)
                .expectedOutput("A concise 2-3 paragraph summary of the topic")
                .agent(summarizer)
                .build();

        // Swarm -- wire agent, task, and process together
        Swarm swarm = Swarm.builder()
                .agent(summarizer)
                .task(summarize)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput result = swarm.kickoff(Map.of("topic", topic));

        logger.info("\n=== Result ===");
        logger.info("{}", result.getFinalOutput());

        metrics.stop();
        metrics.report();
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"bare-minimum"});
    }
}
