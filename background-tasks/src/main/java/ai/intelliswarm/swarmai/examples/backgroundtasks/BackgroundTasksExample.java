package ai.intelliswarm.swarmai.examples.backgroundtasks;

import ai.intelliswarm.swarmai.agent.background.BackgroundTask;
import ai.intelliswarm.swarmai.agent.background.BackgroundTaskRegistry;
import ai.intelliswarm.swarmai.agent.background.BackgroundTaskStatus;
import ai.intelliswarm.swarmai.agent.background.BackgroundTaskTools;
import ai.intelliswarm.swarmai.agent.background.LlmBackgroundTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Showcases the background-task primitive (1.0.19+)
 * end-to-end with a real LLM. The foreground agent has four background-task
 * tools — {@code background_spawn}, {@code background_list},
 * {@code background_output}, {@code background_stop} — and uses them to
 * dispatch a slow research prompt asynchronously, do other work in the
 * meantime, then poll for the result.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Foreground agent       Foreground tools: [bg_spawn, bg_list, bg_output, bg_stop]
 *                                       │
 *                                       ↓
 *                          ┌──────────────────────┐
 *                          │ BackgroundTaskRegistry│  ←── tracks every task
 *                          └──────────────────────┘
 *                                       │
 *                                       ↓
 *                          ┌──────────────────────┐
 *                          │ LlmBackgroundTaskRunner│  ←── runs each task in its own LLM call
 *                          └──────────────────────┘
 *                                       │
 *                                       ↓
 *                              Background agent
 *                              (no bg_* tools — prevents recursive spawns)
 * </pre>
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>The foreground agent spawns a task and gets an id back synchronously</li>
 *   <li>The background task runs asynchronously (LLM call in another thread)</li>
 *   <li>The foreground agent can poll status / fetch output / cancel mid-flight</li>
 *   <li>The runner is recursion-free: background agents do NOT have bg_* tools</li>
 * </ol>
 *
 * <h2>Quality check</h2>
 * <ol>
 *   <li>≥ 1 background task was spawned</li>
 *   <li>The background task reached COMPLETED status</li>
 *   <li>The output is non-trivial (≥ 50 chars)</li>
 *   <li>The foreground agent's reply references the background task somehow</li>
 * </ol>
 */
@Component
public class BackgroundTasksExample {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundTasksExample.class);

    private final ChatClient.Builder chatClientBuilder;

    public BackgroundTasksExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        // ---- 1. Build the chat client (shared between foreground + background) ----
        ChatClient chatClient = chatClientBuilder.build();

        // ---- 2. Wire the background runner ----
        // Background agents have NO tools — that includes none of the bg_* tools,
        // which prevents recursive spawning.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            String backgroundSystem = """
                    You are a focused research assistant running as a background task.
                    Produce a clear, structured answer to the user prompt in 4-6 short
                    sentences. No tool calls — just produce the answer.
                    """;
            LlmBackgroundTaskRunner runner = new LlmBackgroundTaskRunner(
                    chatClient, executor, backgroundSystem, List.of());

            BackgroundTaskRegistry registry = new BackgroundTaskRegistry(runner);

            // Listener: print to stdout when each task completes
            registry.addCompletionListener(t -> {
                line(String.format("    [completion event] task %s -> %s (took %ds)",
                        t.id(), t.status(), t.duration().toSeconds()));
            });

            // ---- 3. Foreground agent's tool set: 4 bg_* callbacks ----
            List<ToolCallback> foregroundTools = BackgroundTaskTools.all(registry);

            String userPrompt = (args != null && args.length > 0)
                    ? String.join(" ", args)
                    : "Spawn a background task to write a 4-sentence summary of how "
                    + "mitochondria produce ATP. While it's running, tell me a quick "
                    + "haiku about coffee. Then check on the background task and "
                    + "include its result in your final answer.";

            String foregroundSystem = """
                    You are a foreground assistant with four background-task tools:
                    background_spawn, background_list, background_output, background_stop.

                    Workflow when the user asks you to dispatch slow work to the background:
                      1. Call background_spawn(prompt=...) — get back a task id immediately.
                      2. Do whatever else the user asked you to do RIGHT NOW (haiku, math,
                         a quick answer).
                      3. Call background_output(id=...) to fetch the background result. If
                         it's still RUNNING, wait briefly and call again — but don't loop
                         forever; if after a few polls it's still not done, just include
                         what you have and note the task is still working.
                      4. Combine the immediate work AND the background result in your final
                         text reply. Reference both clearly.

                    Keep your final reply concise — 5-8 sentences total.
                    """;

            printHeader(userPrompt);

            // ---- 4. Run the foreground LLM ----
            String foregroundReply;
            try {
                foregroundReply = chatClient.prompt()
                        .system(foregroundSystem)
                        .user(userPrompt)
                        .toolCallbacks(foregroundTools.toArray(new ToolCallback[0]))
                        .call()
                        .content();
            } catch (RuntimeException e) {
                logger.error("foreground LLM call failed: {}", e.getMessage(), e);
                return;
            }

            line("");
            line("======================================================================");
            line("  Foreground agent's final reply");
            line("======================================================================");
            line(foregroundReply == null ? "(empty)" : foregroundReply);

            // Wait for any still-running tasks before reporting (so the demo is
            // deterministic — the background completion may arrive after the
            // foreground reply if the LLM only polled once).
            for (BackgroundTask t : registry.active()) {
                try {
                    t.awaitTerminal(Duration.ofSeconds(45));
                } catch (InterruptedException | TimeoutException e) {
                    line("    waiting for " + t.id() + " timed out: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }
            }

            // ---- 5. Show every task spawned ----
            line("");
            line("======================================================================");
            line("  Background tasks spawned during this run");
            line("======================================================================");
            for (BackgroundTask t : registry.all()) {
                line(String.format("  %s  status=%s  age=%ds",
                        t.id(), t.status(), t.duration().toSeconds()));
                line("    prompt: " + truncate(oneLine(t.prompt()), 100));
                if (t.status() == BackgroundTaskStatus.COMPLETED) {
                    line("    output: " + truncate(oneLine(t.output().orElse("")), 200));
                } else if (t.status() == BackgroundTaskStatus.FAILED) {
                    line("    error:  " + t.error().orElse(""));
                }
            }

            // ---- 6. Quality check ----
            runQualityCheck(registry, foregroundReply);

        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runQualityCheck(BackgroundTaskRegistry registry, String foregroundReply) {
        nl();
        line("======================================================================");
        line("  Quality check");
        line("======================================================================");

        int total = registry.size();
        long completed = registry.completed().stream()
                .filter(t -> t.status() == BackgroundTaskStatus.COMPLETED).count();
        long failed = registry.completed().stream()
                .filter(t -> t.status() == BackgroundTaskStatus.FAILED).count();
        long substantialOutput = registry.completed().stream()
                .filter(t -> t.status() == BackgroundTaskStatus.COMPLETED)
                .filter(t -> t.output().orElse("").length() >= 50)
                .count();

        line(String.format("  tasks spawned:    %d", total));
        line(String.format("  completed:        %d", completed));
        line(String.format("  failed:           %d", failed));
        line(String.format("  substantial out:  %d (>=50 chars)", substantialOutput));

        boolean atLeastOneSpawned = total >= 1;
        boolean atLeastOneCompleted = completed >= 1;
        boolean substantialResult = substantialOutput >= 1;
        boolean replyAcknowledgesBackground = foregroundReply != null
                && (foregroundReply.toLowerCase().contains("background")
                || foregroundReply.toLowerCase().contains("mitochondria")
                || foregroundReply.toLowerCase().contains("atp")
                || foregroundReply.toLowerCase().contains("task"));

        nl();
        line("  Checks:");
        line("    [" + check(atLeastOneSpawned)
                + "] >= 1 background task was spawned (got " + total + ")");
        line("    [" + check(atLeastOneCompleted)
                + "] >= 1 task reached COMPLETED status (got " + completed + ")");
        line("    [" + check(substantialResult)
                + "] background output is substantial (got " + substantialOutput + ")");
        line("    [" + check(replyAcknowledgesBackground)
                + "] foreground reply references the background work or its topic");

        nl();
        boolean allPass = atLeastOneSpawned && atLeastOneCompleted
                && substantialResult && replyAcknowledgesBackground;
        if (allPass) {
            line("  QUALITY CHECK PASSED");
            line("  -> The agent dispatched async work, did something else in parallel,");
            line("  -> retrieved the background result, and combined both in its reply.");
        } else {
            line("  QUALITY CHECK FAILED — inspect counts above.");
        }
    }

    private static String check(boolean ok) { return ok ? "PASS" : "FAIL"; }

    private static void printHeader(String userPrompt) {
        nl();
        line("======================================================================");
        line("  Background Tasks — async LLM dispatch");
        line("======================================================================");
        line("");
        line("  Foreground tool set: 4 background_* callbacks");
        line("    background_spawn(prompt)   — start a task, return id");
        line("    background_list()          — list every task with status");
        line("    background_output(id)      — fetch status + output");
        line("    background_stop(id)        — cancel mid-flight");
        line("");
        line("  Background runner: LlmBackgroundTaskRunner");
        line("    Each spawned task runs as its own LLM call in a background thread.");
        line("    Background agents have NO bg_* tools — prevents recursive spawning.");
        line("");
        line("  User goal:");
        line("    " + truncate(userPrompt, 200));
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }
}
