/* SwarmAI Framework - Copyright (c) 2025 IntelliSwarm.ai (MIT License) */
package ai.intelliswarm.swarmai.examples.streaming;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.CompactionConfig;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streaming Agent Output -- demonstrates reactive multi-turn execution where
 * output is produced incrementally across turns. Uses maxTurns(4) with a
 * CompactionConfig, a ToolHook as a streaming progress observer, and
 * turn-by-turn output inspection via SwarmOutput.getTaskOutputs().
 *
 * The agent builds a short story in four phases (scene, characters, plot,
 * conclusion), simulating a streaming content generation pipeline.
 */
@Component
public class StreamingWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(StreamingWorkflow.class);
    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public StreamingWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        String topic = args.length > 0 ? String.join(" ", args) : "a robot discovering emotions";

        logger.info("\n" + "=".repeat(70));
        logger.info("STREAMING AGENT OUTPUT EXAMPLE");
        logger.info("Topic: {}", topic);
        logger.info("Process: SEQUENTIAL | Turns: 4 (scene -> characters -> plot -> conclusion)");
        logger.info("=".repeat(70));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("streaming");
        metrics.start();

        ChatClient chatClient = chatClientBuilder.build();

        // Progress hook -- streaming observer that prints status as turns happen
        AtomicInteger turnCounter = new AtomicInteger(0);
        String[] phases = {"Setting the scene", "Introducing characters",
                           "Developing the plot", "Writing the conclusion"};

        ToolHook progressHook = new ToolHook() {
            @Override
            public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                int turn = turnCounter.incrementAndGet();
                String phase = turn <= phases.length ? phases[turn - 1] : "Continuing...";
                logger.info("[Stream] Turn {} | Phase: {} | Tool: {}",
                        turn, phase, ctx.toolName());
                return ToolHookResult.allow();
            }

            @Override
            public ToolHookResult afterToolUse(ToolHookContext ctx) {
                logger.info("[Stream] Tool {} completed in {} ms (output: {} chars)",
                        ctx.toolName(), ctx.executionTimeMs(),
                        ctx.output() != null ? ctx.output().length() : 0);
                return ToolHookResult.allow();
            }
        };

        // Story Writer -- maxTurns=4 for reactive multi-turn execution
        Agent storyWriter = Agent.builder()
                .role("Creative Story Writer")
                .goal("Write a compelling short story by building it incrementally. " +
                      "Each turn should add a distinct section: scene, characters, " +
                      "plot development, and conclusion. Signal <CONTINUE> after each " +
                      "section until the story is complete, then signal <DONE>.")
                .backstory("You are an acclaimed short fiction author known for vivid " +
                           "imagery and emotional depth. You craft stories in stages, " +
                           "carefully layering setting, character, conflict, and resolution.")
                .chatClient(chatClient)
                .maxTurns(4)
                .compactionConfig(CompactionConfig.of(2, 3000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .toolHook(progressHook)
                .verbose(true)
                .temperature(0.7)
                .build();

        // Task: build the story in four phases with visible output per turn
        Task storyTask = Task.builder()
                .description(String.format(
                        "Write a short story about: %s\n\n" +
                        "Build the story incrementally across your turns:\n" +
                        "  Turn 1: SET THE SCENE - Describe the world, time, and atmosphere.\n" +
                        "  Turn 2: INTRODUCE CHARACTERS - Bring in the protagonist and key figures.\n" +
                        "  Turn 3: DEVELOP THE PLOT - Create conflict, tension, and turning points.\n" +
                        "  Turn 4: CONCLUDE - Resolve the story with emotional resonance.\n\n" +
                        "Each turn should produce a clearly labeled section. " +
                        "Use <CONTINUE> after turns 1-3 and <DONE> after turn 4.", topic))
                .expectedOutput("A complete short story in four labeled sections")
                .agent(storyWriter)
                .maxExecutionTime(120000)
                .build();

        // Build and execute the swarm with SEQUENTIAL process
        Swarm swarm = Swarm.builder()
                .id("streaming-story")
                .agent(storyWriter)
                .task(storyTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .maxRpm(10)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        long startTime = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(Map.of("topic", topic));
        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // Turn-by-turn output -- the core of the streaming pattern.
        logger.info("\n" + "=".repeat(70));
        logger.info("STREAMING OUTPUT - TURN BY TURN");
        logger.info("=".repeat(70));

        List<TaskOutput> outputs = result.getTaskOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TaskOutput output = outputs.get(i);
            String phase = i < phases.length ? phases[i] : "Extra turn";

            logger.info("\n--- Turn {} / {} [{}] ---", i + 1, outputs.size(), phase);
            logger.info("Prompt tokens:     {}", output.getPromptTokens());
            logger.info("Completion tokens: {}", output.getCompletionTokens());
            logger.info("Content:\n{}", output.getRawOutput());
        }

        metrics.recordTurns(outputs.size());

        // ===== POST-STREAM SUMMARIZER =====
        // Distinct second agent: aggregates the streamed chunks into a structured report.
        logger.info("\n" + "=".repeat(70));
        logger.info("POST-STREAM SUMMARIZATION");
        logger.info("=".repeat(70));

        StringBuilder streamBundle = new StringBuilder();
        for (int i = 0; i < outputs.size(); i++) {
            String phase = i < phases.length ? phases[i] : "Extra turn";
            streamBundle.append("### Chunk ").append(i + 1).append(" [").append(phase).append("]\n");
            streamBundle.append(outputs.get(i).getRawOutput()).append("\n\n");
        }

        Agent summarizer = Agent.builder()
                .role("Streaming Summarizer")
                .goal("Aggregate the streamed story chunks for '" + topic + "' into a " +
                      "structured editorial report. You do NOT write new fiction -- you " +
                      "analyze, label, and summarize what the streaming writer produced.")
                .backstory("You are an editor who receives multi-turn streamed content and " +
                           "turns it into a reader-friendly report: a structured summary, " +
                           "per-chunk highlights, and continuity notes. You never invent " +
                           "content that the writer did not produce.")
                .chatClient(chatClient)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .temperature(0.2)
                .build();

        Task summaryTask = Task.builder()
                .description("Aggregate the following streamed chunks into a structured report.\n\n" +
                             "STREAMED CHUNKS:\n" + streamBundle + "\n" +
                             "PRODUCE A MARKDOWN REPORT WITH:\n" +
                             "1. **Executive Summary** -- 2-3 sentences capturing the whole story\n" +
                             "2. **Per-Chunk Highlights** -- One bullet per streamed chunk " +
                             "(label + key image or event)\n" +
                             "3. **Continuity Notes** -- How each chunk builds on the previous one\n" +
                             "4. **Themes & Motifs** -- 2-4 recurring ideas across the stream\n" +
                             "5. **Final Assembled Story** -- The chunks stitched together cleanly\n\n" +
                             "Do not add new fiction; only summarize and structure what is above.")
                .expectedOutput("A structured markdown report aggregating all streamed chunks")
                .agent(summarizer)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(60000)
                .build();

        Swarm summarySwarm = Swarm.builder()
                .id("streaming-summarizer")
                .agent(summarizer)
                .task(summaryTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .maxRpm(10)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput summaryResult = summarySwarm.kickoff(Map.of("topic", topic));
        String structuredReport = summaryResult.getFinalOutput();

        metrics.stop();

        logger.info("\n" + "=".repeat(70));
        logger.info("STREAMING WORKFLOW COMPLETE");
        logger.info("=".repeat(70));
        logger.info("Topic: {} | Duration: {}s | Turns: {}", topic, duration, outputs.size());
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("\nFinal assembled story (raw stream):\n{}", result.getFinalOutput());
        logger.info("\nStructured post-stream report:\n{}", structuredReport);
        logger.info("=".repeat(70));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("streaming",
                "Reactive multi-turn streaming with progress hooks plus post-stream summarizer agent",
                structuredReport != null ? structuredReport : result.getFinalOutput(),
                result.isSuccessful() && summaryResult.isSuccessful(),
                System.currentTimeMillis() - startMs,
                2, 2, "SEQUENTIAL", "streaming-real-time-responses");
        }

        metrics.report();
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"streaming"});
    }
}
