/* SwarmAI Framework - Copyright (c) 2025 IntelliSwarm.ai (Apache License 2.0) */
package ai.intelliswarm.swarmai.examples.humanloop;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.Channels;
import ai.intelliswarm.swarmai.state.CheckpointSaver;
import ai.intelliswarm.swarmai.state.CompiledSwarm;
import ai.intelliswarm.swarmai.state.HookPoint;
import ai.intelliswarm.swarmai.state.InMemoryCheckpointSaver;
import ai.intelliswarm.swarmai.state.StateSchema;
import ai.intelliswarm.swarmai.state.SwarmGraph;
import ai.intelliswarm.swarmai.state.SwarmHook;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-in-the-Loop -- approval gates and checkpoint-based pause/resume
 * in a content publishing pipeline.
 *
 *   [START] -> [draft] -> [review] -> [approval_gate]
 *                                        |          |
 *                                    (approved)  (rejected)
 *                                        |          |
 *                                    [publish]   [revise] -> [draft] (loop)
 *                                        |
 *                                      [END]
 *
 * Features: Approval Gate | BEFORE/AFTER_TASK Hooks | Checkpoints |
 *           Conditional Routing | State Channels (appender, counter, lastWriteWins)
 *
 * Usage: java -jar swarmai-framework.jar human-loop "AI trends in enterprise 2026"
 */
@Component
public class HumanInTheLoopWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(HumanInTheLoopWorkflow.class);
    private static final int APPROVAL_THRESHOLD = 70;

    @Autowired private LLMJudge judge;
    private static final int MAX_REVISIONS = 2;

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;

    public HumanInTheLoopWorkflow(ChatClient.Builder chatClientBuilder,
                                  ApplicationEventPublisher eventPublisher) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args)
                : "AI trends in enterprise 2026";

        logger.info("\n" + "=".repeat(80));
        logger.info("HUMAN-IN-THE-LOOP WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:     {}", topic);
        logger.info("Pattern:   Draft -> Review -> Approval Gate -> Publish / Revise loop");
        logger.info("Features:  Approval Gate | Lifecycle Hooks | Checkpoints | Conditional Routing");
        logger.info("Threshold: Quality score >= {} to approve", APPROVAL_THRESHOLD);
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("human-loop");
        metrics.start();

        // -- State Schema: typed channels for the editorial pipeline --

        StateSchema schema = StateSchema.builder()
                .channel("draft",         Channels.<String>lastWriteWins())
                .channel("feedback",      Channels.<String>appender())
                .channel("quality_score", Channels.<Integer>lastWriteWins())
                .channel("approved",      Channels.<Boolean>lastWriteWins())
                .channel("iteration",     Channels.counter())
                .allowUndeclaredKeys(true)
                .build();

        // -- Checkpoint saver for state persistence between stages --

        CheckpointSaver checkpointSaver = new InMemoryCheckpointSaver();

        // -- Build the approval-gated graph --

        // Placeholder agent and task required to pass SwarmGraph compile-time validation.
        // Actual work is done inside addNode lambdas using inline agents.
        Agent placeholderAgent = Agent.builder()
                .role("Editorial Pipeline Router")
                .goal("Route content through the approval pipeline")
                .backstory("You route content through a human-in-the-loop publishing pipeline.")
                .chatClient(chatClient).build();
        Task placeholderTask = Task.builder()
                .description("Process content through the editorial approval pipeline")
                .expectedOutput("A published article")
                .agent(placeholderAgent).build();

        CompiledSwarm compiled = SwarmGraph.create()
                .addAgent(placeholderAgent)
                .addTask(placeholderTask)
                .stateSchema(schema)
                .checkpointSaver(checkpointSaver)
                .addEdge(SwarmGraph.START, "draft")
                .addEdge("draft", "review")
                .addEdge("review", "approval_gate")

                // Node: draft -- Content Writer agent drafts or revises an article
                .addNode("draft", state -> {
                    int iter = state.valueOrDefault("iteration", 0L).intValue();
                    String feedback = state.valueOrDefault("feedback", "");
                    String instructions = iter == 0
                            ? "Write a 3-paragraph article about: " + topic + "\n\n"
                                + "Requirements: clear thesis, supporting evidence, actionable conclusion."
                            : "Revise your article about '" + topic + "' based on feedback.\n\n"
                                + "FEEDBACK:\n" + feedback + "\n\nRevision #" + iter
                                + ". Address every point raised.";

                    Agent writer = Agent.builder()
                            .role("Content Writer")
                            .goal("Draft compelling, well-structured articles on technology topics")
                            .backstory("Senior tech writer. Clear, evidence-backed articles.")
                            .chatClient(chatClient).build();
                    TaskOutput output = writer.executeTask(Task.builder()
                            .description(instructions)
                            .expectedOutput("A 3-paragraph article in markdown")
                            .agent(writer).build(), List.of());
                    logger.info("  [draft] {} article ({} chars)",
                            iter == 0 ? "Initial" : "Revised", output.getRawOutput().length());
                    return Map.of("draft", output.getRawOutput(), "iteration", 1L);
                })

                // Node: review -- Quality Reviewer scores the draft
                .addNode("review", state -> {
                    Agent reviewer = Agent.builder()
                            .role("Quality Reviewer")
                            .goal("Score articles 0-100 and give actionable feedback")
                            .backstory("Editorial director, 15 years experience. Always provides "
                                    + "a numeric SCORE: N/100 and detailed feedback.")
                            .chatClient(chatClient).build();
                    TaskOutput output = reviewer.executeTask(Task.builder()
                            .description("Review this draft and score it.\n\nDRAFT:\n"
                                    + state.valueOrDefault("draft", "") + "\n\n"
                                    + "Score on: Clarity (25), Accuracy (25), Impact (25), "
                                    + "Completeness (25). Format: SCORE: N/100 then FEEDBACK.")
                            .expectedOutput("Quality score and feedback")
                            .agent(reviewer).build(), List.of());
                    int score = extractScore(output.getRawOutput());
                    logger.info("  [review] Quality score: {}/100", score);
                    return Map.of("quality_score", score, "feedback", List.of(output.getRawOutput()));
                })

                // Node: approval_gate -- simulated human approval decision
                .addNode("approval_gate", state -> {
                    int score = state.valueOrDefault("quality_score", 0);
                    int iter = state.valueOrDefault("iteration", 0L).intValue();
                    boolean approved = score >= APPROVAL_THRESHOLD || iter >= MAX_REVISIONS;

                    if (score >= APPROVAL_THRESHOLD) {
                        logger.info("  [approval_gate] APPROVED -- score {} meets threshold {}",
                                score, APPROVAL_THRESHOLD);
                    } else if (iter >= MAX_REVISIONS) {
                        logger.info("  [approval_gate] APPROVED (max revisions) -- score {} after {} revisions",
                                score, iter);
                    } else {
                        logger.info("  [approval_gate] REJECTED -- score {} below {}, revision {}/{}",
                                score, APPROVAL_THRESHOLD, iter, MAX_REVISIONS);
                    }
                    logger.info("  [approval_gate] -- In production, this pauses for human review --");
                    return Map.of("approved", approved);
                })

                // Conditional edge: approved -> publish, rejected -> revise
                .addConditionalEdge("approval_gate", state -> {
                    boolean approved = state.valueOrDefault("approved", false);
                    String next = approved ? "publish" : "revise";
                    logger.info("  [route] approval_gate -> {}", next);
                    return next;
                })

                // Node: revise -- passthrough that routes back to draft
                .addNode("revise", state -> {
                    logger.info("  [revise] Routing back to draft for revision...");
                    return Map.of();
                })
                .addEdge("revise", "draft")

                // Node: publish -- Publisher agent formats the final article
                .addNode("publish", state -> {
                    Agent publisher = Agent.builder()
                            .role("Content Publisher")
                            .goal("Format approved articles for web publication with metadata")
                            .backstory("Publishing editor. Adds title, date, summary, attribution.")
                            .chatClient(chatClient).build();
                    TaskOutput output = publisher.executeTask(Task.builder()
                            .description("Format this article for publication.\n\n"
                                    + "ARTICLE:\n" + state.valueOrDefault("draft", "") + "\n\n"
                                    + "Score: " + state.valueOrDefault("quality_score", 0) + "/100\n\n"
                                    + "Add: title, 2-sentence summary, metadata (date, category, "
                                    + "reading time), formatted body, author attribution.")
                            .expectedOutput("Publication-ready article with metadata")
                            .agent(publisher).build(), List.of());
                    logger.info("  [publish] Formatted for publication ({} chars)",
                            output.getRawOutput().length());
                    return Map.of("draft", output.getRawOutput());
                })
                .addEdge("publish", SwarmGraph.END)

                // Lifecycle hooks -- simulate notifications and checkpoint logging
                .addHook(HookPoint.BEFORE_TASK, (SwarmHook<AgentState>) ctx -> {
                    logger.info("[HOOK] BEFORE_TASK '{}' -- notifying reviewer queue at {}",
                            ctx.getTaskId().orElse("unknown"), Instant.now());
                    return ctx.state();
                })
                .addHook(HookPoint.AFTER_TASK, (SwarmHook<AgentState>) ctx -> {
                    logger.info("[HOOK] AFTER_TASK '{}' -- checkpoint saved at {}",
                            ctx.getTaskId().orElse("unknown"), Instant.now());
                    return ctx.state();
                })
                .compileOrThrow();

        // -- Execute the pipeline --

        AgentState initialState = AgentState.of(schema, Map.of("topic", topic));

        logger.info("\n" + "-".repeat(60));
        logger.info("Executing content publishing pipeline...");
        logger.info("-".repeat(60));

        long startTime = System.currentTimeMillis();
        SwarmOutput result = compiled.kickoff(initialState);
        long durationMs = System.currentTimeMillis() - startTime;

        // -- Results --

        metrics.stop();

        logger.info("\n" + "=".repeat(80));
        logger.info("HUMAN-IN-THE-LOOP WORKFLOW COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Topic:       {}", topic);
        logger.info("Duration:    {} ms", durationMs);
        logger.info("Successful:  {}", result.isSuccessful());
        logger.info("Tasks:       {}", result.getTaskOutputs().size());
        logger.info("-".repeat(80));
        logger.info("Published Article:\n\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("human-loop", "Approval gates with checkpoints and revision loops via SwarmGraph", result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startTime,
                5, 5, "SWARM_GRAPH", "human-approval-gate");
        }

        metrics.report();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Extracts a numeric quality score from reviewer output.
     * Matches "SCORE: 85/100", "85/100", or falls back to first 0-100 integer.
     */
    private static int extractScore(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return 50;
        Matcher m = Pattern.compile("(?:SCORE:\\s*|\\b)(\\d{1,3})\\s*/\\s*100")
                .matcher(rawOutput.toUpperCase());
        if (m.find()) return Math.min(Integer.parseInt(m.group(1)), 100);
        m = Pattern.compile("\\b(\\d{1,3})\\b").matcher(rawOutput);
        while (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 0 && val <= 100) return val;
        }
        return 50;
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"human-loop"});
    }
}
