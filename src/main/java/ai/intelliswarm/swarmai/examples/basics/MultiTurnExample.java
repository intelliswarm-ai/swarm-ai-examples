package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.CompactionConfig;
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
 * Demonstrates multi-turn reasoning with a single agent.
 *
 * The agent uses maxTurns=5 with CompactionConfig for auto-compaction,
 * allowing it to reason across multiple turns using CONTINUE/DONE markers.
 * Compaction automatically summarizes older context to stay within token limits.
 */
@Component
public class MultiTurnExample {

    private static final Logger logger = LoggerFactory.getLogger(MultiTurnExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public MultiTurnExample(ChatClient.Builder chatClientBuilder,
                            ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        ChatClient chatClient = chatClientBuilder.build();
        String topic = args.length > 0 ? String.join(" ", args)
                : "the impact of large language models on software engineering";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("multi-turn");
        metrics.start();

        // Single agent with multi-turn + compaction enabled
        Agent deepResearcher = Agent.builder()
                .role("Deep Researcher")
                .goal("Conduct a thorough, multi-step analysis of the given topic")
                .backstory("You are a methodical researcher who breaks complex topics into "
                         + "distinct analytical steps. You use multiple reasoning turns to "
                         + "build a comprehensive understanding before synthesizing findings.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(5)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .toolHook(metrics.metricsHook())
                .build();

        // The task instructs the agent to use multiple reasoning steps
        Task research = Task.builder()
                .description("Research and provide a comprehensive analysis of " + topic + ". "
                           + "Use multiple reasoning steps: first identify key aspects, "
                           + "then analyze each, then synthesize findings.")
                .expectedOutput("A comprehensive analysis with clear sections for each aspect "
                              + "and a synthesis of the overall findings")
                .agent(deepResearcher)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(deepResearcher)
                .task(research)
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
                args.length > 0 ? args : new String[]{"multi-turn"});
    }
}
