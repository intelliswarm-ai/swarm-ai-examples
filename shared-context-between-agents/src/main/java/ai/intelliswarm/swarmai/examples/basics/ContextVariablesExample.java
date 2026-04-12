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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import ai.intelliswarm.swarmai.judge.LLMJudge;

import java.util.HashMap;
import java.util.Map;

/**
 * Shows how to pass context/state between agents via the inputs map.
 *
 * THREE agents in a SEQUENTIAL pipeline: Outliner, Drafter, Polisher.
 * The inputs map carries shared context (topic, audience, tone, wordCount)
 * that each task description interpolates using String.format.
 */
@Component
public class ContextVariablesExample {

    private static final Logger logger = LoggerFactory.getLogger(ContextVariablesExample.class);
    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public ContextVariablesExample(ChatClient.Builder chatClientBuilder,
                                   ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        ChatClient chatClient = chatClientBuilder.build();
        String topic = args.length > 0 ? String.join(" ", args) : "microservices architecture";

        // Context variables -- shared state for the entire pipeline
        String audience = "senior software engineers";
        String tone = "professional yet approachable";
        String wordCount = "500";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("context-variables");
        metrics.start();

        // Agent 1: Outliner
        Agent outliner = Agent.builder()
                .role("Content Outliner")
                .goal("Create a structured outline for an article")
                .backstory("You specialize in organizing complex technical topics into "
                         + "clear, logical outlines that guide writers.")
                .chatClient(chatClient)
                .verbose(true)
                .build();

        // Agent 2: Drafter
        Agent drafter = Agent.builder()
                .role("Content Drafter")
                .goal("Write a first draft based on an outline")
                .backstory("You are a skilled technical writer who turns outlines into "
                         + "complete, well-written drafts.")
                .chatClient(chatClient)
                .verbose(true)
                .build();

        // Agent 3: Polisher
        Agent polisher = Agent.builder()
                .role("Content Polisher")
                .goal("Polish and refine drafts to publication quality")
                .backstory("You are a meticulous editor who ensures every article is "
                         + "clear, engaging, and perfectly tailored to its audience.")
                .chatClient(chatClient)
                .verbose(true)
                .build();

        // Each task interpolates the shared context variables
        Task outlineTask = Task.builder()
                .description(String.format(
                        "Create an outline for an article about '%s'. "
                      + "Target audience: %s. Tone: %s. Target length: ~%s words. "
                      + "Include 3-5 main sections with 2-3 bullet points each.",
                        topic, audience, tone, wordCount))
                .expectedOutput("A structured outline with sections and bullet points")
                .agent(outliner)
                .build();

        Task draftTask = Task.builder()
                .description(String.format(
                        "Write a first draft about '%s' based on the provided outline. "
                      + "Write for %s in a %s tone. Target ~%s words.",
                        topic, audience, tone, wordCount))
                .expectedOutput("A complete first draft of the article")
                .agent(drafter)
                .dependsOn(outlineTask)
                .build();

        Task polishTask = Task.builder()
                .description(String.format(
                        "Polish the draft about '%s'. Ensure it is appropriate for %s, "
                      + "maintains a %s tone, and is approximately %s words. "
                      + "Fix grammar, improve flow, and add a compelling introduction and conclusion.",
                        topic, audience, tone, wordCount))
                .expectedOutput("A polished, publication-ready article")
                .agent(polisher)
                .dependsOn(draftTask)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(outliner)
                .agent(drafter)
                .agent(polisher)
                .task(outlineTask)
                .task(draftTask)
                .task(polishTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        // The inputs map passes all context variables into the swarm
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", topic);
        inputs.put("audience", audience);
        inputs.put("tone", tone);
        inputs.put("wordCount", wordCount);

        SwarmOutput result = swarm.kickoff(inputs);

        logger.info("\n=== Result ===");
        logger.info("{}", result.getFinalOutput());
        logger.info("\n=== Pipeline Stats ===");
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("Success rate: {}%", (int) (result.getSuccessRate() * 100));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("context-variables", "Three agents sharing context via inputs map", result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startMs,
                3, 3, "SEQUENTIAL", "shared-context-between-agents");
        }

        metrics.stop();
        metrics.report();
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"context-variables"});
    }
}
