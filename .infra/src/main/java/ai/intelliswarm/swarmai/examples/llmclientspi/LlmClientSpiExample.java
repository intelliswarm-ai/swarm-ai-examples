package ai.intelliswarm.swarmai.examples.llmclientspi;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicConfig;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicLlmClient;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * SwarmAI 1.0.24 — {@code LlmClient} SPI plugged into {@link Agent#callLlm}.
 *
 * <p>Runs the same one-task workflow twice, side-by-side:
 * <ol>
 *   <li>Once with the default Spring AI {@link ChatClient} (existing behaviour).</li>
 *   <li>Once with an {@link AnthropicLlmClient} plugged into the new
 *       {@code Agent.Builder.llmClient(...)} hook — which routes
 *       {@link Agent#callLlm} through the SPI and bypasses Spring AI for the
 *       LLM call. The fallback Spring AI {@code ChatClient} is still required
 *       (it handles tool-calling and streaming when those features are needed)
 *       — but the actual model dispatch comes from the native adapter.</li>
 * </ol>
 *
 * <p>The proof points printed at the end:
 * <ul>
 *   <li><b>Output text</b> from each path (semantically equivalent prompts).</li>
 *   <li><b>Token counts</b> reported by each path (Spring AI's accounting vs
 *       Anthropic's native usage).</li>
 *   <li><b>Wall time</b> per path (rough latency comparison).</li>
 * </ul>
 *
 * <p>Requires {@code ANTHROPIC_API_KEY} in the environment, plus Spring AI's
 * default {@link ChatClient} (driven by whichever profile is active —
 * {@code openai-mini} works well).
 */
@Component
public class LlmClientSpiExample {

    private static final Logger logger = LoggerFactory.getLogger(LlmClientSpiExample.class);

    private final ChatClient chatClient;

    @Autowired
    public LlmClientSpiExample(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void run(String[] args) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            logger.error("ANTHROPIC_API_KEY env var not set. This example exercises the native Anthropic SPI path.");
            return;
        }

        logger.info("");
        logger.info("================================================================");
        logger.info("  SwarmAI 1.0.24 — LlmClient SPI in Agent.callLlm()");
        logger.info("  Same task, two agents: Spring AI vs AnthropicLlmClient");
        logger.info("================================================================");
        logger.info("");

        String taskDescription = "In one paragraph, summarise why prompt caching matters "
                + "for production LLM workloads. Be concrete and avoid hype.";
        String expectedOutput = "One paragraph, plain prose, no bullet lists.";

        // ---- [1/3] Spring AI baseline ----
        logger.info("[1/3] Running task via Spring AI ChatClient (baseline) …");
        AgentRun springRun = runOnce("baseline-springai",
                taskDescription, expectedOutput,
                Agent.builder()
                        .role("Caching Educator")
                        .goal("Explain prompt caching to engineers")
                        .backstory("You write clear, no-fluff technical prose.")
                        .chatClient(chatClient)
                        .maxTurns(1)
                        .permissionMode(PermissionLevel.READ_ONLY)
                        .build());

        // ---- [2/3] LlmClient SPI path with AnthropicLlmClient ----
        logger.info("");
        logger.info("[2/3] Running same task via Agent.builder().llmClient(AnthropicLlmClient) …");
        AnthropicLlmClient anthropic = AnthropicLlmClient.from(AnthropicConfig.withApiKey(apiKey));
        AgentRun spiRun = runOnce("spi-anthropic-native",
                taskDescription, expectedOutput,
                Agent.builder()
                        .role("Caching Educator")
                        .goal("Explain prompt caching to engineers")
                        .backstory("You write clear, no-fluff technical prose.")
                        // The Spring AI ChatClient is still required (used for streaming
                        // and tool-bearing calls). For text-only single-shot calls the
                        // SPI takes over.
                        .chatClient(chatClient)
                        .llmClient(anthropic)
                        .modelName("claude-sonnet-4-5")
                        .maxTurns(1)
                        .permissionMode(PermissionLevel.READ_ONLY)
                        .build());

        // ---- [3/3] Side-by-side comparison ----
        logger.info("");
        logger.info("[3/3] Side-by-side:");
        logger.info("");
        logger.info("  ┌─ Spring AI ChatClient (baseline) ────────────────────────────");
        if (springRun.text.isBlank()) {
            logger.info("  │  [failed/empty — environmental, not a SPI issue]");
            logger.info("  │  Wall time:    {} ms (failure path)", springRun.wallMs);
        } else {
            logger.info("  │  Wall time:    {} ms", springRun.wallMs);
            logger.info("  │  Output chars: {}", springRun.text.length());
            logger.info("  │  Tokens in/out: {}/{}", springRun.promptTokens, springRun.completionTokens);
            logger.info("  │  First 240ch:  {}", abbrev(springRun.text, 240));
        }
        logger.info("  └─────────────────────────────────────────────────────────────");
        logger.info("");
        logger.info("  ┌─ AnthropicLlmClient SPI (native adapter) ───────────────────");
        logger.info("  │  Wall time:     {} ms", spiRun.wallMs);
        logger.info("  │  Output chars:  {}", spiRun.text.length());
        logger.info("  │  Tokens in/out: {}/{} ← reported by Anthropic, plumbed through SPI",
                spiRun.promptTokens, spiRun.completionTokens);
        logger.info("  │  First 240ch:   {}", abbrev(spiRun.text, 240));
        logger.info("  └─────────────────────────────────────────────────────────────");
        logger.info("");

        // Verification is SPI-focused: the baseline can fail for environmental
        // reasons (revoked OpenAI key, model deprecation, etc.) and that
        // shouldn't make the SPI demo look broken. Each axis below proves a
        // distinct property of the LlmClient → Agent integration.
        logger.info("Verification (SPI path):");
        logger.info("  Returned non-empty text:                 {}",
                !spiRun.text.isBlank() ? "✓" : "✗");
        logger.info("  Token usage plumbed through:             {}",
                spiRun.promptTokens > 0 && spiRun.completionTokens > 0
                        ? "✓ (proves AnthropicLlmClient's TokenUsage was translated)"
                        : "✗ (tokens lost in the SPI → ChatResponse synthesis)");
        logger.info("  Completed within 30s timeout:            {}",
                spiRun.wallMs < 30_000 ? "✓" : "✗");
        if (!springRun.text.isBlank()) {
            logger.info("  Distinct from baseline text:             {}",
                    !spiRun.text.equals(springRun.text)
                            ? "✓ (different model produced different prose)"
                            : "✗ (suspiciously identical — check baseline isn't shadowing SPI)");
        } else {
            logger.info("  Baseline comparison:                     [skipped — baseline failed]");
        }

        logger.info("");
        logger.info("Notes:");
        logger.info("  - Same Agent.Builder API on both paths — only difference is the");
        logger.info("    .llmClient(...) line. No changes to Task or Swarm.");
        logger.info("  - The SPI path bypasses Spring AI's prompt-template + tool-callback");
        logger.info("    layer entirely; tool-bearing calls fall back to Spring AI.");
        logger.info("  - This unlocks native Anthropic features (prompt caching, extended");
        logger.info("    + adaptive thinking, citations) on agents that don't need tools.");
        logger.info("");
    }

    private AgentRun runOnce(String label, String description, String expectedOutput, Agent agent) {
        Task task = Task.builder()
                .description(description)
                .expectedOutput(expectedOutput)
                .agent(agent)
                .build();
        Swarm swarm = Swarm.builder()
                .agent(agent)
                .task(task)
                .process(ProcessType.SEQUENTIAL)
                .build();
        Instant t0 = Instant.now();
        SwarmOutput out;
        try {
            out = swarm.kickoff(java.util.Collections.emptyMap());
        } catch (RuntimeException e) {
            logger.warn("[{}] kickoff failed: {}", label, e.getMessage());
            return new AgentRun("", 0, 0, Duration.between(t0, Instant.now()).toMillis());
        }
        long wallMs = Duration.between(t0, Instant.now()).toMillis();
        return new AgentRun(
                out.getRawOutput() != null ? out.getRawOutput() : "",
                out.getTotalPromptTokens(),
                out.getTotalCompletionTokens(),
                wallMs);
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        String trimmed = s.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    private record AgentRun(String text, long promptTokens, long completionTokens, long wallMs) {}
}
