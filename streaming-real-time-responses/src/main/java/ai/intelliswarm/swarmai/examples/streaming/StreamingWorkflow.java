/* SwarmAI Framework - Copyright (c) 2025 IntelliSwarm.ai (Apache License 2.0) */
package ai.intelliswarm.swarmai.examples.streaming;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token-level streaming demo (Phase 1).
 *
 * <p>Subscribes to {@link Agent#executeTaskStreaming(Task, java.util.List)} and
 * prints each {@link AgentEvent.TextDelta} to {@code stdout} the moment it
 * arrives. With {@code SPRING_PROFILES_ACTIVE=ollama} and a streaming-capable
 * model (qwen2.5, mistral, llama3) you'll see the response materialise word
 * by word — the same UX you get with ChatGPT's UI.
 *
 * <p>Run via the parent runner:
 * <pre>{@code
 *   ./run.sh streaming "a robot discovering emotions"
 * }</pre>
 *
 * <p>Compared with the old version of this example, which simulated streaming
 * by running 4 sequential turns and emitting per-turn progress hooks, this
 * version uses Spring AI's {@code ChatModel.stream()} under the hood and
 * delivers per-token deltas. Whether the wire-level granularity is tokens or
 * small chunks depends on the LLM provider — Ollama emits true per-token
 * deltas; some OpenAI completions emit larger chunks.
 */
@Component
public class StreamingWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(StreamingWorkflow.class);

    @Autowired(required = false)
    private WorkflowMetricsCollector metricsBean;

    private final ChatClient.Builder chatClientBuilder;

    public StreamingWorkflow(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args) : "a robot discovering emotions";

        logger.info("");
        logger.info("=".repeat(72));
        logger.info("  TOKEN-LEVEL STREAMING — {}", topic);
        logger.info("  Using Agent.executeTaskStreaming() · per-token deltas via Spring AI");
        logger.info("=".repeat(72));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("streaming");
        metrics.start();

        ChatClient chatClient = chatClientBuilder.build();

        Agent storyWriter = Agent.builder()
                .role("Story Writer")
                .goal("Write a vivid one-page short story (~400 words) on the requested topic.")
                .backstory("You are an acclaimed short-fiction author known for tight prose, "
                        + "concrete sensory detail, and emotional truth. You write the story "
                        + "in a single pass — no preamble, no commentary, just the story.")
                .chatClient(chatClient)
                .verbose(false)
                .temperature(0.7)
                // Generous timeout so cold-start local Ollama isn't killed at 2 min.
                .maxExecutionTime(300_000)
                .build();

        Task storyTask = Task.builder()
                .description("Write a short story (about 400 words) on this topic: " + topic
                        + "\n\nNo title or preamble — start the story with the first line of prose.")
                .expectedOutput("A short story in prose, ~400 words.")
                .agent(storyWriter)
                .build();

        // ---- subscribe and render in real time ---------------------------
        // Block the calling thread until the stream completes (CLI workflow).
        // For a real chat UI you'd return the Flux and let the controller
        // pipe it into an SseEmitter or a Flux<ServerSentEvent<AgentEvent>>.
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger deltas = new AtomicInteger();
        AtomicLong firstTokenMs = new AtomicLong(-1L);
        AtomicLong startNs = new AtomicLong(System.nanoTime());
        AtomicReference<TaskOutput> finalOutput = new AtomicReference<>();
        AtomicReference<String> errorText = new AtomicReference<>();

        // Use a dedicated PrintStream so we control flushing per-delta — System.out
        // is line-buffered in some shells which would defeat the live-rendering point.
        PrintStream out = new PrintStream(System.out, true);

        storyWriter.executeTaskStreaming(storyTask, Collections.emptyList())
                .subscribe(
                        evt -> handle(evt, out, deltas, firstTokenMs, startNs, finalOutput),
                        err -> {
                            // Shouldn't happen — executeTaskStreaming converts errors to
                            // AgentError events on the success channel. Defensive only.
                            errorText.set(err.getClass().getSimpleName() + ": " + err.getMessage());
                            done.countDown();
                        },
                        done::countDown);

        // 5-minute hard ceiling for the whole stream; covers cold-start Ollama on CPU.
        if (!done.await(300, TimeUnit.SECONDS)) {
            logger.warn("Streaming workflow did not complete within 5 minutes — aborting wait.");
        }

        out.println(); // newline after the streamed prose
        logger.info("=".repeat(72));

        if (errorText.get() != null) {
            logger.error("Streaming failed: {}", errorText.get());
        } else {
            long firstToken = firstTokenMs.get();
            long total = (System.nanoTime() - startNs.get()) / 1_000_000;
            logger.info("  Deltas received:     {}", deltas.get());
            logger.info("  Time to first token: {} ms", firstToken < 0 ? "n/a" : firstToken);
            logger.info("  Total stream time:   {} ms", total);
            TaskOutput last = finalOutput.get();
            if (last != null && last.getRawOutput() != null) {
                logger.info("  Final output chars:  {}", last.getRawOutput().length());
            }
        }
        logger.info("=".repeat(72));

        metrics.recordTurns(1);
    }

    /** Per-event handler — keeps the subscribe lambda small and testable. */
    private static void handle(AgentEvent evt,
                               PrintStream out,
                               AtomicInteger deltas,
                               AtomicLong firstTokenMs,
                               AtomicLong startNs,
                               AtomicReference<TaskOutput> finalOutput) {
        switch (evt) {
            case AgentEvent.AgentStarted s -> {
                out.println();
                out.printf("──[ %s · %s ]──────────────────────────────────────────%n",
                        s.role(), truncate(s.taskDescription(), 50));
                out.flush();
                startNs.set(System.nanoTime());
            }
            case AgentEvent.TextDelta d -> {
                int n = deltas.incrementAndGet();
                if (n == 1) {
                    firstTokenMs.set((System.nanoTime() - startNs.get()) / 1_000_000);
                }
                // The crux of the demo: print AND flush per delta so the user sees
                // the prose appear word-by-word, not in one burst at the end.
                out.print(d.text());
                out.flush();
            }
            case AgentEvent.ToolCallStart t -> {
                // Phase 2 will emit these. Logged here so consumers can copy this
                // handler shape into their own UI code.
                out.printf("%n  [→ tool %s args=%s]%n", t.toolName(), truncate(t.argumentsJson(), 60));
            }
            case AgentEvent.ToolCallEnd t -> {
                out.printf("  [← tool %s %s]%n", t.toolName(),
                        t.errorMessage() != null ? "ERROR: " + t.errorMessage()
                                                 : "ok (" + truncate(t.resultJson(), 50) + ")");
            }
            case AgentEvent.AgentFinished f -> {
                finalOutput.set(f.taskOutput());
            }
            case AgentEvent.AgentError e -> {
                out.printf("%n  [✗ %s: %s]%n", e.exceptionType(), e.message());
            }
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
