package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import ai.intelliswarm.swarmai.judge.LLMJudge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Two-agent handoff with token-level streaming.
 *
 * <p>{@code Researcher} drafts the raw notes, then {@code Editor} polishes
 * them into a publish-ready blog post. The original (non-streaming) version
 * called {@code Swarm.kickoff} and only saw the final output at the end of
 * the run. This streaming variant uses {@link Swarm#runStreaming} on the
 * SEQUENTIAL path so each agent's tokens appear live in the console as the
 * model produces them, with the researcher's full output threaded as
 * context into the editor's prompt automatically.
 *
 * <p>Why this example fits Phase-1 streaming cleanly:
 * <ul>
 *   <li>Both agents are tool-less (Phase-1 streaming has no tool support).</li>
 *   <li>Both run single-turn under streaming (no CONTINUE/DONE multi-turn
 *       loop), so the researcher's first-turn answer is the answer.</li>
 *   <li>The {@code dependsOn} chain reduces to the natural SEQUENTIAL ordering
 *       of {@code Swarm.runStreaming}, which threads each agent's
 *       {@link TaskOutput} as context to the next.</li>
 * </ul>
 */
@Component
public class AgentHandoffExample {

    private static final Logger logger = LoggerFactory.getLogger(AgentHandoffExample.class);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    private static final String C_RESET      = "[0m";
    private static final String C_RESEARCHER = "[36m"; // cyan
    private static final String C_EDITOR     = "[35m"; // magenta

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
        String topic = args.length > 0 ? String.join(" ", args)
                : "WebAssembly outside the browser: where it actually makes sense in 2026";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("agent-handoff");
        metrics.start();

        // Agent 1: Researcher -- READ_ONLY, gathers raw information
        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research the given topic and produce detailed, factual notes")
                .backstory("You are a thorough researcher who gathers comprehensive information "
                         + "on any topic. You focus on accuracy and breadth of coverage.")
                .chatClient(chatClient)
                .verbose(false)
                .maxTurns(1)
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
                .verbose(false)
                .maxTurns(1)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .toolHook(metrics.metricsHook())
                .build();

        Task researchTask = Task.builder()
                .description("Research the topic: " + topic + ". "
                           + "Identify key concepts, recent developments, and practical applications.")
                .expectedOutput("Detailed research notes covering the topic's key aspects")
                .agent(researcher)
                .build();

        Task editTask = Task.builder()
                .description("Take the research notes (provided as prior context) and shape them "
                           + "into a publication-ready blog post. Use Markdown headings, an "
                           + "introduction, 3-5 main sections, and a brief conclusion. Cite specific "
                           + "facts the researcher found (do not invent new ones). "
                           + "Write in clear, engaging prose.")
                .expectedOutput("A complete Markdown blog post, ready to publish, with title, "
                              + "intro, sections, and conclusion")
                .agent(editor)
                .dependsOn(researchTask)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(researcher)
                .agent(editor)
                .task(researchTask)
                .task(editTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(false)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        // =====================================================================
        // STREAM THE WHOLE SEQUENTIAL FLOW
        // =====================================================================
        // Swarm.runStreaming(SEQUENTIAL) emits a single Flux<AgentEvent> with
        // events from agent N concat'd after agent N-1's terminal AgentFinished.
        // The agentId on each event lets us colorize per-agent output.

        logger.info("\n=== Streaming agent handoff ===\n");

        // Map agentId -> { color, label, accumulator } so we can demux on the
        // merged stream cleanly, even though SEQUENTIAL doesn't actually
        // interleave (this future-proofs the same pattern for PARALLEL).
        Map<String, AgentRender> renders = new LinkedHashMap<>();
        renders.put(researcher.getId(), new AgentRender("RESEARCHER", C_RESEARCHER));
        renders.put(editor.getId(),     new AgentRender("EDITOR",     C_EDITOR));

        swarm.runStreaming(Map.of("topic", topic))
                .doOnNext(evt -> {
                    AgentRender r = renders.get(evt.agentId());
                    if (r == null) return;
                    if (evt instanceof AgentEvent.AgentStarted s) {
                        System.out.printf("%n%s>>> %s — %s <<<%s%n%s",
                                r.color, r.label, s.role(), C_RESET, r.color);
                        System.out.flush();
                    } else if (evt instanceof AgentEvent.TextDelta d) {
                        System.out.print(d.text());
                        System.out.flush();
                        r.accum.append(d.text());
                    } else if (evt instanceof AgentEvent.AgentFinished f) {
                        System.out.print(C_RESET);
                        System.out.println();
                        System.out.flush();
                        if (f.taskOutput() != null) {
                            r.taskOutput = f.taskOutput();
                        }
                    } else if (evt instanceof AgentEvent.AgentError e) {
                        System.out.print(C_RESET);
                        System.out.println();
                        logger.error("[{}] error: {} - {}",
                                r.label, e.exceptionType(), e.message());
                    }
                })
                .blockLast(STREAM_TIMEOUT);

        // Editor's output is the final blog post.
        AgentRender editorRender = renders.get(editor.getId());
        String finalOutput = editorRender.taskOutput != null
                ? editorRender.taskOutput.getRawOutput()
                : editorRender.accum.toString();

        logger.info("\n=== Result ===");
        logger.info("{}", finalOutput);

        if (finalOutput != null && !finalOutput.isBlank()) {
            Path outDir = Paths.get("output", "agent-handoff");
            Files.createDirectories(outDir);
            String slug = topic.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-|-$)", "");
            if (slug.length() > 60) slug = slug.substring(0, 60);
            Path postFile = outDir.resolve(LocalDate.now() + "-" + slug + ".md");
            Files.writeString(postFile, finalOutput);
            logger.info("Polished post written to: {}", postFile.toAbsolutePath());
        }

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("agent-handoff",
                "Two agents with task dependencies: researcher -> editor (streaming)",
                finalOutput,
                finalOutput != null && !finalOutput.isBlank(), System.currentTimeMillis() - startMs,
                2, 2, "SEQUENTIAL", "agent-to-agent-task-handoff");
        }

        metrics.stop();
        metrics.report();
    }

    /** Per-agent rendering state for the merged stream. */
    private static final class AgentRender {
        final String label;
        final String color;
        final StringBuilder accum = new StringBuilder();
        TaskOutput taskOutput;

        AgentRender(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"agent-handoff"});
    }
}
