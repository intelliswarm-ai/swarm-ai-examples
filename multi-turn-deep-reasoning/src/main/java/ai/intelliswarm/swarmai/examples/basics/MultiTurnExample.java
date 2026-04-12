package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.CompactionConfig;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.common.FileWriteTool;
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
 * Demonstrates multi-turn reasoning with a single agent.
 *
 * The agent uses maxTurns=5 with CompactionConfig for auto-compaction,
 * allowing it to reason across multiple turns using CONTINUE/DONE markers.
 * Compaction automatically summarizes older context to stay within token limits.
 */
@Component
public class MultiTurnExample {

    private static final Logger logger = LoggerFactory.getLogger(MultiTurnExample.class);
    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final FileWriteTool fileWriteTool;

    public MultiTurnExample(ChatClient.Builder chatClientBuilder,
                            ApplicationEventPublisher eventPublisher,
                            FileWriteTool fileWriteTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.fileWriteTool = fileWriteTool;
    }

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        ChatClient chatClient = chatClientBuilder.build();
        String topic = args.length > 0 ? String.join(" ", args)
                : "the impact of large language models on software engineering";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("multi-turn");
        metrics.start();

        // Single agent with multi-turn + compaction enabled.
        // FileWriteTool is injected so the agent can persist its final analysis to disk.
        Agent deepResearcher = Agent.builder()
                .role("Deep Researcher")
                .goal("Conduct a thorough, multi-step analysis of the given topic and persist "
                    + "the final report to disk using the file_write tool.")
                .backstory("You are a methodical researcher who breaks complex topics into "
                         + "distinct analytical steps. You use multiple reasoning turns to "
                         + "build a comprehensive understanding before synthesizing findings. "
                         + "You always persist your final output to disk using the file_write tool.")
                .chatClient(chatClient)
                .tools(List.of(fileWriteTool))
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .verbose(true)
                .maxTurns(5)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .toolHook(metrics.metricsHook())
                .build();

        // The task instructs the agent to use multiple reasoning steps and save its output
        Task research = Task.builder()
                .description("Research and provide a comprehensive analysis of " + topic + ". "
                           + "Use multiple reasoning steps: first identify key aspects, "
                           + "then analyze each, then synthesize findings.\n\n"
                           + "CRITICAL: After you reach <DONE>, you MUST call the `file_write` "
                           + "tool to persist the final markdown analysis to "
                           + "`output/multi-turn-report.md`. This demonstrates integrated "
                           + "file-saving as an agent capability. Use file_write with "
                           + "path=output/multi-turn-report.md and the full report content.")
                .expectedOutput("A comprehensive analysis with clear sections for each aspect "
                              + "and a synthesis of the overall findings, persisted to "
                              + "output/multi-turn-report.md via the file_write tool")
                .agent(deepResearcher)
                .outputFile("output/multi-turn-report.md")
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

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("multi-turn", "Single agent with maxTurns=5 and auto-compaction for deep reasoning", result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startMs,
                1, 1, "SEQUENTIAL", "multi-turn-deep-reasoning");
        }

        metrics.stop();
        metrics.report();
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"multi-turn"});
    }
}
