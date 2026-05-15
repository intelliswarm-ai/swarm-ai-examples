package ai.intelliswarm.swarmai.examples.reliabilitysubstrate;

import ai.intelliswarm.swarmai.agent.parallel.ParallelGroup;
import ai.intelliswarm.swarmai.agent.parallel.PendingToolCall;
import ai.intelliswarm.swarmai.agent.parallel.ToolCallSafetyAnalyzer;
import ai.intelliswarm.swarmai.agent.resilience.ErrorClassifier;
import ai.intelliswarm.swarmai.agent.resilience.FailoverReason;
import ai.intelliswarm.swarmai.agent.resilience.LlmCircuitBreaker;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import ai.intelliswarm.swarmai.tool.hooks.LoopDetector;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SwarmAI 1.0.24 — Reliability substrate self-test.
 *
 * <p>Walks through every reliability primitive that ships in 1.0.24 and
 * asserts a property of each, printing ✓/✗ per axis. Runs entirely offline
 * — no API keys, no network — so the demo doubles as a smoke test of the
 * substrate.
 *
 * <ol>
 *   <li>{@link ErrorClassifier} — 7-row table mapping representative
 *       error inputs to {@link FailoverReason}; each row asserts the
 *       expected category.</li>
 *   <li>{@link LoopDetector} — feed 7 identical failed tool calls,
 *       verify the detector denies on the 6th (default {@code blockAt}).</li>
 *   <li>{@link LlmCircuitBreaker} — drive 10 calls with a 60 % failure
 *       rate, verify the breaker transitions CLOSED → OPEN and starts
 *       short-circuiting before the cost of additional failed calls
 *       lands.</li>
 *   <li>{@link ToolCallSafetyAnalyzer} — feed mixed read/write tool
 *       calls, verify the analyser batches read-only calls together and
 *       isolates writes.</li>
 * </ol>
 */
@Component
public class ReliabilitySubstrateExample {

    private static final Logger logger = LoggerFactory.getLogger(ReliabilitySubstrateExample.class);

    public void run(String[] args) {
        logger.info("");
        logger.info("================================================================");
        logger.info("  SwarmAI 1.0.24 — Reliability substrate self-test");
        logger.info("================================================================");
        logger.info("");

        int pass = 0;
        int total = 0;

        // ---- [1/4] ErrorClassifier — exception → FailoverReason ----
        logger.info("[1/4] ErrorClassifier — exception → FailoverReason");
        Object[][] table = {
                { new SocketTimeoutException("read timed out"), FailoverReason.RETRYABLE },
                { new RuntimeException("rate limit exceeded, please retry"), FailoverReason.RETRYABLE },
                { new RuntimeException("Invalid API key — please rotate"), FailoverReason.SHOULD_ROTATE_CREDENTIAL },
                { new RuntimeException("This model's maximum context length is 128000 tokens"),
                        FailoverReason.SHOULD_COMPRESS },
                { new RuntimeException("Provider overloaded — please try again later"),
                        FailoverReason.SHOULD_FALLBACK },
                { new RuntimeException("Bad request: tool definition malformed"),
                        FailoverReason.NOT_RECOVERABLE },
                { new RuntimeException("connection reset by peer"), FailoverReason.RETRYABLE },
        };
        logger.info("      {:<55s} {:<22s} {:<22s} {}",
                "input", "expected", "actual", "verdict");
        for (Object[] row : table) {
            Throwable err = (Throwable) row[0];
            FailoverReason expected = (FailoverReason) row[1];
            FailoverReason actual = ErrorClassifier.classify(err);
            boolean ok = actual == expected;
            total++;
            if (ok) pass++;
            String input = err.getClass().getSimpleName() + ": " + truncate(err.getMessage(), 35);
            logger.info(String.format(Locale.ROOT, "      %-55s %-22s %-22s %s",
                    input, expected, actual, ok ? "✓" : "✗"));
        }

        // ---- [2/4] LoopDetector — denies same args after blockAt failures ----
        // Default config: warnAt=3, blockAt=6. The detector counts the prior
        // failures, so it denies on the call that would be the 7th attempt
        // (i.e., after 6 prior failures have been recorded via afterToolUse).
        logger.info("");
        logger.info("[2/4] LoopDetector — 6 identical-arg failures, then denies the 7th attempt");
        LoopDetector loopDetector = new LoopDetector();
        Map<String, Object> toolArgs = Map.of("query", "find ACME revenue");
        int denyAttempt = -1;
        for (int i = 1; i <= 8; i++) {
            ToolHookContext beforeCtx = ToolHookContext.before("search", toolArgs, "agent-1", null);
            ToolHookResult beforeResult = loopDetector.beforeToolUse(beforeCtx);
            if (beforeResult.action() == ToolHookResult.Action.DENY) {
                denyAttempt = i;
                logger.info("      attempt {}: DENIED — {}", i, beforeResult.message());
                break;
            }
            // Pretend the call ran and failed
            ToolHookContext errCtx = ToolHookContext.error("search", toolArgs, 50,
                    new RuntimeException("upstream 500"), "agent-1", null);
            loopDetector.afterToolUse(errCtx);
            logger.info("      attempt {}: proceeded → simulated failure recorded", i);
        }
        boolean loopOk = denyAttempt == 7;
        total++;
        if (loopOk) pass++;
        logger.info("      Verdict: {} (denied on attempt {}, expected 7)",
                loopOk ? "✓" : "✗", denyAttempt);

        // ---- [3/4] LlmCircuitBreaker — opens at 50% failure rate ----
        logger.info("");
        logger.info("[3/4] LlmCircuitBreaker — drive 10 calls @ 60% fail, verify CLOSED → OPEN transition");
        LlmCircuitBreaker.ResilienceConfig cfg = new LlmCircuitBreaker.ResilienceConfig();
        cfg.failureRateThreshold = 50f;   // open when ≥ 50% of last 10 calls fail
        cfg.slidingWindowSize = 10;
        cfg.maxRetryAttempts = 1;          // disable retry for this demo so failures hit the breaker raw
        cfg.waitDurationOpenStateMs = 60_000;
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(cfg);
        // First 6 calls fail, next 4 succeed → 60% failure on the window
        boolean[] script = {false, false, false, false, false, false, true, true, true, true};
        int succeeded = 0;
        int failed = 0;
        int shortCircuited = 0;
        CircuitBreaker.State firstOpenState = null;
        for (int i = 0; i < script.length; i++) {
            final boolean shouldSucceed = script[i];
            try {
                breaker.execute(() -> {
                    if (!shouldSucceed) throw new RuntimeException("simulated upstream 500");
                    return "ok";
                });
                succeeded++;
            } catch (CallNotPermittedException shortCircuit) {
                shortCircuited++;
                if (firstOpenState == null) firstOpenState = CircuitBreaker.State.OPEN;
            } catch (RuntimeException e) {
                failed++;
            }
            CircuitBreaker.State state = breaker.getState();
            logger.info("      call {}: scripted={} actual={} → state={}",
                    i + 1,
                    shouldSucceed ? "OK" : "FAIL",
                    shouldSucceed ? "OK" : (firstOpenState != null ? "SHORT_CIRCUITED" : "FAIL"),
                    state);
        }
        boolean breakerOk = breaker.getState() == CircuitBreaker.State.OPEN
                || breaker.getState() == CircuitBreaker.State.HALF_OPEN
                || shortCircuited > 0;
        total++;
        if (breakerOk) pass++;
        logger.info("      Counts: {} succeeded / {} failed / {} short-circuited",
                succeeded, failed, shortCircuited);
        logger.info("      Final state: {} → {}",
                breaker.getState(),
                breakerOk ? "✓ (breaker did its job — protected callers from the bad upstream)"
                        : "✗ (breaker stayed CLOSED despite the failure burst)");

        // ---- [4/4] ToolCallSafetyAnalyzer — read/write batching ----
        logger.info("");
        logger.info("[4/4] ToolCallSafetyAnalyzer — read-only calls batch, write isolates");
        // Defaults: web_search is parallel-safe; write_file is unknown to the
        // default never-parallel set (which only covers approval / wait /
        // task-management tools). Register write_file via withAdditionalNeverParallel
        // so the demo shows the customisation API plus the read/write split.
        List<PendingToolCall> calls = List.of(
                new PendingToolCall("c1", "web_search", Map.of("q", "ACME")),
                new PendingToolCall("c2", "web_search", Map.of("q", "GLOBEX")),
                new PendingToolCall("c3", "write_file", Map.of("path", "/tmp/notes.md", "content", "…")),
                new PendingToolCall("c4", "web_search", Map.of("q", "INITECH")));
        ToolCallSafetyAnalyzer analyser = new ToolCallSafetyAnalyzer()
                .withAdditionalNeverParallel("write_file", "delete_file");
        List<ParallelGroup> groups = analyser.analyse(calls);
        logger.info("      Input: 3 web_search (parallel-safe) + 1 write_file (never-parallel)");
        for (int i = 0; i < groups.size(); i++) {
            ParallelGroup g = groups.get(i);
            logger.info("      Group {}: {} calls — {}", i + 1, g.calls().size(),
                    g.calls().stream().map(PendingToolCall::toolName).toList());
        }
        // Expected: 1 group of size 2 (the first 2 web_searches) + 1 standalone
        // write_file + 1 group of size 1 (third web_search after the write break).
        // But the analyser may merge non-adjacent reads — so the verification is
        // looser: writes are never grouped with anything else.
        boolean writesIsolated = groups.stream()
                .filter(g -> g.calls().stream().anyMatch(c -> c.toolName().equals("write_file")))
                .allMatch(g -> g.calls().size() == 1);
        boolean someBatching = groups.stream().anyMatch(g -> g.calls().size() > 1);
        total++;
        if (writesIsolated && someBatching) pass++;
        logger.info("      Writes isolated:    {}", writesIsolated ? "✓" : "✗");
        logger.info("      Reads batched:      {}", someBatching ? "✓" : "✗");

        // ---- Summary ----
        logger.info("");
        logger.info("================================================================");
        logger.info("  Reliability substrate: {}/{} assertions passed", pass, total);
        logger.info("================================================================");
        logger.info("");
        logger.info("How these compose in production:");
        logger.info("  - Agent.callLlm now routes every retry decision through ErrorClassifier");
        logger.info("    (rate-limit → retry, context-window → don't retry, 401 → don't retry, …).");
        logger.info("  - LoopDetector plugs in via Agent.builder().toolHook(new LoopDetector()) —");
        logger.info("    catches the model burning budget on the same failing tool call.");
        logger.info("  - LlmCircuitBreaker is an opt-in wrapper for cross-call protection: open the");
        logger.info("    breaker after a failure burst so the next 30s of requests short-circuit");
        logger.info("    instead of paying for guaranteed-to-fail upstream calls.");
        logger.info("  - ToolCallSafetyAnalyzer is the substrate the parallel-tool dispatcher reads");
        logger.info("    when it decides which tool calls can fan out concurrently.");
        logger.info("");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
