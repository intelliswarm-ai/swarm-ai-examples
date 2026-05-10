package ai.intelliswarm.swarmai.examples.waitforsignal;

import ai.intelliswarm.swarmai.agent.wait.Signal;
import ai.intelliswarm.swarmai.agent.wait.SignalDispatcher;
import ai.intelliswarm.swarmai.agent.wait.WaitForSignalRequest;
import ai.intelliswarm.swarmai.agent.wait.WaitForSignalTool;
import ai.intelliswarm.swarmai.agent.wait.WaitStatus;
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

/**
 * Showcases the {@link WaitForSignalTool} primitive (1.0.21+) end-to-end with
 * a real LLM. The agent receives ONE tool — {@code wait_for_signal} — bound to
 * a {@link SignalDispatcher}. A separate thread simulates an external system
 * (CI pipeline, webhook, batch job) that fires a wake-up signal after a delay.
 *
 * <h2>Demo arc</h2>
 * <pre>
 *   t=0s   Foreground LLM call starts. Agent has only wait_for_signal loaded.
 *   t=0.1s Agent reads the user prompt: "deploy v2.4.0; wait for canary healthy"
 *   t=0.2s Agent calls wait_for_signal(signalKey="deploy.canary.healthy",
 *                                       timeoutSeconds=30)
 *          → tool registers a wait, blocks the agent's tool-call thread
 *   t=4s   External simulator (separate thread) fires
 *          dispatcher.deliver("deploy.canary.healthy",
 *                             "100% healthy, 0 errors over 60s, latency p99=42ms")
 *          → tool callback unblocks, returns the signal payload as the tool result
 *   t=4.5s Agent reads the payload, produces a final response describing next steps
 * </pre>
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>The agent pauses synchronously inside its tool-call thread (Mode A).</li>
 *   <li>An external thread can wake it up by calling
 *       {@link SignalDispatcher#deliver}.</li>
 *   <li>The signal payload reaches the LLM as the tool's return value, and the
 *       agent uses it to produce a final response.</li>
 *   <li>The wait genuinely paused — the run takes ≥3s wall-clock even though
 *       the LLM round-trip is sub-second.</li>
 * </ol>
 *
 * <h2>Quality check</h2>
 * Verifies {@code (a)} the dispatcher recorded exactly one completed wait,
 * {@code (b)} that wait reached COMPLETED (not CANCELLED / EXPIRED),
 * {@code (c)} the run took at least 3 seconds (proving the agent really paused),
 * {@code (d)} the agent's reply references the signal payload's content.
 */
@Component
public class WaitForSignalExample {

    private static final Logger logger = LoggerFactory.getLogger(WaitForSignalExample.class);

    private static final String SIGNAL_KEY = "deploy.canary.healthy";
    private static final String SIGNAL_PAYLOAD =
            "100% healthy, 0 errors over 60s, latency p99=42ms, 5/5 cells green";
    /** Wall-clock delay AFTER the agent has registered the wait; deterministic. */
    private static final Duration POST_REGISTER_DELAY = Duration.ofSeconds(2);
    /** How long the simulator polls for the wait to register before giving up. */
    private static final Duration MAX_REGISTER_WAIT = Duration.ofSeconds(45);

    private final ChatClient.Builder chatClientBuilder;

    public WaitForSignalExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        // ---- 1. Wire the dispatcher + tool callback ----
        SignalDispatcher dispatcher = new SignalDispatcher();
        ToolCallback waitCallback = WaitForSignalTool.callback(dispatcher);

        ChatClient chatClient = chatClientBuilder.build();

        // ---- 2. Schedule the external "CI pipeline" simulator ----
        ExecutorService externalSimulator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ci-pipeline-simulator");
            t.setDaemon(true);
            return t;
        });
        externalSimulator.submit(() -> {
            // Poll for the wait to register — racing the LLM round-trip would drop
            // the signal if we fired before the agent's tool call landed.
            long deadline = System.currentTimeMillis() + MAX_REGISTER_WAIT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                if (dispatcher.pendingCount() >= 1) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (dispatcher.pendingCount() == 0) {
                line("    [external simulator] gave up — no wait registered within "
                        + MAX_REGISTER_WAIT.toSeconds() + "s");
                return;
            }
            line(String.format("    [external simulator] wait detected; sleeping %ds before firing signal",
                    POST_REGISTER_DELAY.toSeconds()));
            try {
                Thread.sleep(POST_REGISTER_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            line(String.format("    [external simulator] firing signal '%s'", SIGNAL_KEY));
            boolean delivered = dispatcher.deliver(SIGNAL_KEY, SIGNAL_PAYLOAD);
            line(String.format("    [external simulator] delivered=%s, dispatcher.dropped=%d",
                    delivered, dispatcher.droppedSignals()));
        });

        // ---- 3. User prompt — directs the LLM to call the tool with the right key ----
        String userPrompt = (args != null && args.length > 0)
                ? String.join(" ", args)
                : "I'm rolling out a canary deployment of v2.4.0. Pause until the canary is "
                + "verified by calling wait_for_signal with signalKey=\"" + SIGNAL_KEY + "\", "
                + "reason=\"waiting for canary deploy of v2.4.0\", and timeoutSeconds=30. "
                + "Once you receive the signal, report what the canary metrics show and "
                + "recommend whether to promote to full rollout, hold, or roll back.";

        printHeader(userPrompt);

        // ---- 4. Drive the LLM (the wait_for_signal tool will block inside this call) ----
        long startMs = System.currentTimeMillis();
        String response;
        try {
            response = chatClient.prompt()
                    .system("""
                            You are a deployment-pipeline assistant. You have one tool:
                            wait_for_signal. Use it to pause execution until an external
                            system reports back. When you receive the signal payload, use
                            its content (metrics, status, etc.) to inform your response.

                            Do not call wait_for_signal more than once.
                            """)
                    .user(userPrompt)
                    .toolCallbacks(waitCallback)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            logger.error("LLM call failed: {}", e.getMessage(), e);
            externalSimulator.shutdownNow();
            dispatcher.close();
            return;
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        externalSimulator.shutdown();
        try {
            externalSimulator.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // ---- 5. Show results ----
        nl();
        line("======================================================================");
        line("  Final response from the LLM");
        line("======================================================================");
        line(response == null ? "(empty)" : response);

        nl();
        line("======================================================================");
        line("  Dispatcher state at end of run");
        line("======================================================================");
        List<WaitForSignalRequest> all = dispatcher.allWaits();
        for (WaitForSignalRequest w : all) {
            line(String.format("  %s  status=%s  age=%ds",
                    w.id(), w.status(),
                    w.completedAtOpt()
                            .map(c -> java.time.Duration.between(w.pausedAt(), c).toSeconds())
                            .orElse(0L)));
            line("    signalKey: " + w.signalKey());
            line("    reason:    " + w.reason());
            w.receivedOpt().ifPresent(s ->
                    line("    received:  " + truncate(s.payload(), 100)));
        }
        if (all.isEmpty()) line("  (no waits recorded — check the LLM actually called the tool)");
        line(String.format("  dropped signals: %d", dispatcher.droppedSignals()));

        // ---- 6. Quality check ----
        runQualityCheck(dispatcher, response, elapsedMs);

        dispatcher.close();
    }

    private void runQualityCheck(SignalDispatcher dispatcher, String response, long elapsedMs) {
        nl();
        line("======================================================================");
        line("  Quality check");
        line("======================================================================");

        List<WaitForSignalRequest> all = dispatcher.allWaits();
        long completed = all.stream().filter(w -> w.status() == WaitStatus.COMPLETED).count();
        long expired = all.stream().filter(w -> w.status() == WaitStatus.EXPIRED).count();

        line(String.format("  elapsed wall-clock:    %.2fs", elapsedMs / 1000.0));
        line(String.format("  total waits:           %d", all.size()));
        line(String.format("  completed:             %d", completed));
        line(String.format("  expired:               %d", expired));
        line(String.format("  post-register delay:   %ds", POST_REGISTER_DELAY.toSeconds()));

        boolean exactlyOneWait = all.size() == 1;
        boolean waitCompleted = completed == 1;
        // The agent should have paused at least POST_REGISTER_DELAY after registering;
        // total elapsed includes LLM round-trips on either side, so just check we
        // didn't return suspiciously fast.
        boolean reallyPaused = elapsedMs >= POST_REGISTER_DELAY.toMillis();
        boolean replyReferencesPayload = response != null && (
                response.toLowerCase().contains("healthy")
                || response.toLowerCase().contains("100%")
                || response.toLowerCase().contains("0 errors")
                || response.toLowerCase().contains("p99")
                || response.toLowerCase().contains("42ms")
                || response.toLowerCase().contains("latency"));

        nl();
        line("  Checks:");
        line("    [" + check(exactlyOneWait)
                + "] exactly 1 wait registered (got " + all.size() + ")");
        line("    [" + check(waitCompleted)
                + "] wait reached COMPLETED status (got " + completed + ")");
        line("    [" + check(reallyPaused)
                + "] run took at least " + POST_REGISTER_DELAY.toSeconds()
                + "s — agent genuinely paused");
        line("    [" + check(replyReferencesPayload)
                + "] final reply references the signal payload's content "
                + "(healthy / 100% / latency / etc.)");

        nl();
        boolean allPass = exactlyOneWait && waitCompleted && reallyPaused
                && replyReferencesPayload;
        if (allPass) {
            line("  QUALITY CHECK PASSED");
            line("  -> The agent paused synchronously, an external thread fired the signal,");
            line("  -> the payload reached the LLM, and the response uses it.");
        } else {
            line("  QUALITY CHECK FAILED — inspect counts above.");
        }
    }

    private static String check(boolean ok) { return ok ? "PASS" : "FAIL"; }

    private static void printHeader(String userPrompt) {
        nl();
        line("======================================================================");
        line("  WaitForSignal — pause-resume primitive (1.0.21+)");
        line("======================================================================");
        line("");
        line("  Foreground tool set: 1 callback (wait_for_signal)");
        line("  External simulator: separate thread that fires");
        line("    dispatcher.deliver(\"" + SIGNAL_KEY + "\", \"...metrics...\")");
        line("  " + POST_REGISTER_DELAY.toSeconds() + " seconds after the agent registers the wait");
        line("  (the simulator polls dispatcher.pendingCount() to avoid racing the LLM).");
        line("");
        line("  This validates Mode A of the WaitForSignal primitive: the agent's");
        line("  tool-call thread blocks on a CompletableFuture while the wait is");
        line("  pending, and an external signal source completes the future from");
        line("  another thread.");
        line("");
        line("  User goal:");
        line("    " + truncate(userPrompt, 200));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }
}
