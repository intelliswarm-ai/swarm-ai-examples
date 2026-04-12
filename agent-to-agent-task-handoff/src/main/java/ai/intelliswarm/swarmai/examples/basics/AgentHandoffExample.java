package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import ai.intelliswarm.swarmai.judge.LLMJudge;

import java.util.Map;

/**
 * Shows how one agent's output feeds into another agent's task.
 *
 * THREE agents, THREE tasks with dependencies (task2 dependsOn task1, task3 dependsOn task2).
 * The Researcher gathers information, the Editor refines it, and the FactChecker
 * verifies and annotates claims in the edited draft.
 */
@Component
public class AgentHandoffExample {

    private static final Logger logger = LoggerFactory.getLogger(AgentHandoffExample.class);
    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public AgentHandoffExample(ChatClient.Builder chatClientBuilder,
                               ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        ChatClient chatClient = chatClientBuilder.build();
        String topic = args.length > 0 ? String.join(" ", args) : "quantum computing applications";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("agent-handoff");
        metrics.start();

        // Agent 1: Researcher -- READ_ONLY, gathers raw information
        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research the given topic and produce detailed, factual notes")
                .backstory("You are a thorough researcher who gathers comprehensive information "
                         + "on any topic. You focus on accuracy and breadth of coverage.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(2)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        // Agent 2: Editor -- WORKSPACE_WRITE, improves the research output
        Agent editor = Agent.builder()
                .role("Editor")
                .goal("Edit and improve raw research into polished, publication-ready content")
                .backstory("You are a senior editor who transforms rough notes into clear, "
                         + "well-structured, and engaging content.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .toolHook(metrics.metricsHook())
                .build();

        // Agent 3: FactChecker -- READ_ONLY, annotates claims in the edited draft
        Agent factChecker = Agent.builder()
                .role("Fact Checker")
                .goal("Verify and annotate claims in the edited draft")
                .backstory("You are a rigorous fact checker who reviews edited content and "
                         + "annotates each factual claim with [VERIFIED], [NEEDS-CITATION], "
                         + "or [SPECULATIVE] tags based on its verifiability and support.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        // Task 1: Research
        Task researchTask = Task.builder()
                .description("Research the topic: " + topic + ". "
                           + "Identify key concepts, recent developments, and practical applications.")
                .expectedOutput("Detailed research notes covering the topic's key aspects")
                .agent(researcher)
                .build();

        // Task 2: Edit -- depends on task 1, so it receives the research output
        Task editTask = Task.builder()
                .description("Edit and improve the research into a polished summary. "
                           + "Fix any issues, improve clarity, and add a brief conclusion.")
                .expectedOutput("A polished, well-structured summary ready for publication")
                .agent(editor)
                .dependsOn(researchTask)
                .build();

        // Task 3: Fact-check -- depends on task 2, annotates claims in the edited draft
        Task factCheckTask = Task.builder()
                .description("Review the edited draft and annotate every factual claim with "
                           + "one of [VERIFIED], [NEEDS-CITATION], or [SPECULATIVE]. "
                           + "Preserve the original text and append the tag inline after each claim.")
                .expectedOutput("The edited draft with inline [VERIFIED], [NEEDS-CITATION], "
                              + "or [SPECULATIVE] annotations on each factual claim")
                .agent(factChecker)
                .dependsOn(editTask)
                .build();

        // Sequential swarm -- task1 runs first, then task2, then task3 fact-checks the edited draft
        Swarm swarm = Swarm.builder()
                .agent(researcher)
                .agent(editor)
                .agent(factChecker)
                .task(researchTask)
                .task(editTask)
                .task(factCheckTask)
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
            judge.evaluate("agent-handoff",
                "Three agents with task dependencies: researcher -> editor -> fact-checker",
                result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startMs,
                3, 3, "SEQUENTIAL", "agent-to-agent-task-handoff");
        }

        metrics.stop();
        metrics.report();
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"agent-handoff"});
    }
}
