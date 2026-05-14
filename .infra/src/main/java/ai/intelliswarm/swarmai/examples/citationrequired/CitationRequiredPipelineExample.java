package ai.intelliswarm.swarmai.examples.citationrequired;

import ai.intelliswarm.swarmai.agent.llm.CacheBreakpoint;
import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.CitationConfig;
import ai.intelliswarm.swarmai.agent.llm.DocumentBlock;
import ai.intelliswarm.swarmai.agent.llm.ThinkingBudget;
import ai.intelliswarm.swarmai.agent.llm.ThinkingEffort;
import ai.intelliswarm.swarmai.governance.compliance.CitationRequiredGate;
import ai.intelliswarm.swarmai.governance.compliance.ComplianceEvaluator;
import ai.intelliswarm.swarmai.governance.compliance.ComplianceGate;
import ai.intelliswarm.swarmai.governance.compliance.ComplianceReport;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicConfig;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicMessage;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicNativeClient;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicRequest;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SwarmAI 1.0.24 — Citation-required compliance pipeline.
 *
 * <p>Demonstrates the end-to-end native-Anthropic + compliance flow:
 * <ol>
 *   <li>Build an {@link AnthropicRequest} that attaches a plain-text document
 *       with citations enabled, prompt-caching breakpoints, and an extended
 *       thinking budget</li>
 *   <li>Send via {@link AnthropicNativeClient} — get back text + inline
 *       citations + cache usage telemetry</li>
 *   <li>Run the response through {@link CitationRequiredGate#strict} via
 *       {@link ComplianceEvaluator}</li>
 *   <li>Print PASS / FAIL with the specific findings if any numeric claim
 *       lacks a citation</li>
 * </ol>
 *
 * <p>Requires {@code ANTHROPIC_API_KEY} in the environment.
 *
 * <pre>{@code
 *   ANTHROPIC_API_KEY=sk-ant-… ./run.sh citation-required-pipeline
 * }</pre>
 */
@Component
public class CitationRequiredPipelineExample {

    private static final Logger logger = LoggerFactory.getLogger(CitationRequiredPipelineExample.class);

    private static final String SAMPLE_FILING = """
            ACME Corp — FY2025 10-K Excerpt (synthetic for example purposes)

            Revenue:           $1,200 million in FY25 (vs $1,000 million in FY24, +20.0%)
            EBITDA:             $360 million in FY25 (vs $280 million in FY24, +28.6%)
            Operating Margin:   18.4% in FY25 (vs 16.0% in FY24, +240 bps)
            Free Cash Flow:     $220 million in FY25 (vs $180 million in FY24, +22.2%)
            Net Debt:           $200 million (year-end FY25)
            Shares Outstanding: 100 million (year-end FY25)
            """;

    public void run(String[] args) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            logger.error("ANTHROPIC_API_KEY env var not set. This example calls the real Anthropic API.");
            logger.error("Set it and re-run: ANTHROPIC_API_KEY=sk-ant-... ./run.sh citation-required-pipeline");
            return;
        }

        logger.info("");
        logger.info("================================================================");
        logger.info("  SwarmAI 1.0.24 — Citation-Required Compliance Pipeline");
        logger.info("================================================================");
        logger.info("");

        AnthropicNativeClient client = AnthropicNativeClient.from(AnthropicConfig.withApiKey(apiKey));

        DocumentBlock filing = new DocumentBlock.PlainText(
                "ACME-10K-FY25",
                "ACME Corp FY2025 10-K Excerpt",
                SAMPLE_FILING,
                /* citationsEnabled */ true);

        AnthropicRequest req = AnthropicRequest.builder()
                // Opus 4-7 requires thinking.type=adaptive + output_config.effort
                // — both wired by our adapter as of 1.0.24.
                .model("claude-opus-4-7")
                .system("You are a financial analyst. Cite every numeric claim back to the filing.")
                .document(filing)
                .message(AnthropicMessage.userText(
                        "Summarise ACME's FY25 financial performance in 3-4 sentences. "
                                + "Include revenue, margin, and free cash flow with proper citations."))
                .maxOutputTokens(2048)
                .cachePolicy(CachePolicy.builder()
                        .breakpoint(CacheBreakpoint.oneHour("system"))
                        .breakpoint(CacheBreakpoint.fiveMinutes("documents"))
                        .build())
                .thinkingBudget(ThinkingBudget.adaptive(ThinkingEffort.MEDIUM))
                .citationConfig(CitationConfig.ENABLED)
                .build();

        logger.info("[1/4] First request — populates the prompt cache …");
        AnthropicResponse resp1 = client.send(req);
        long firstReadTokens = resp1.tokenUsage().cacheUsage().cacheReadInputTokens();
        long firstWrite5m    = resp1.tokenUsage().cacheUsage().cacheCreationFiveMinuteInputTokens();
        long firstWrite1h    = resp1.tokenUsage().cacheUsage().cacheCreationOneHourInputTokens();
        logger.info("      Model:           {}", resp1.modelId());
        logger.info("      Stop reason:     {}", resp1.stopReason());
        logger.info("      Input tokens:    {}", resp1.tokenUsage().inputTokens());
        logger.info("      Output tokens:   {}", resp1.tokenUsage().outputTokens());
        logger.info("      Cache read:      {} tokens", firstReadTokens);
        logger.info("      Cache write 5m:  {} tokens (= what the cache now stores)", firstWrite5m);
        logger.info("      Cache write 1h:  {} tokens", firstWrite1h);

        logger.info("");
        logger.info("[2/4] Second request — same prompt prefix, served from cache …");
        AnthropicResponse resp2 = client.send(req);
        long secondReadTokens = resp2.tokenUsage().cacheUsage().cacheReadInputTokens();
        long secondWrite5m    = resp2.tokenUsage().cacheUsage().cacheCreationFiveMinuteInputTokens();
        long secondWrite1h    = resp2.tokenUsage().cacheUsage().cacheCreationOneHourInputTokens();
        logger.info("      Input tokens:    {} (uncached input only)", resp2.tokenUsage().inputTokens());
        logger.info("      Cache read:      {} tokens  ← cache hit, proves caching fired", secondReadTokens);
        logger.info("      Cache write 5m:  {} tokens", secondWrite5m);
        logger.info("      Cache write 1h:  {} tokens", secondWrite1h);
        if (secondReadTokens > firstReadTokens) {
            logger.info("      Delta:           cache_read jumped {} → {} tokens between calls",
                    firstReadTokens, secondReadTokens);
        } else {
            logger.warn("      Note: cache_read did not increase — caching may not have taken effect "
                    + "(likely because the prompt was below the cache-eligible token floor; "
                    + "see https://docs.claude.com/en/docs/build-with-claude/prompt-caching).");
        }

        // Use the second response for the rest of the demo so the citation parsing
        // and gate evaluation reflect the cache-served path.
        AnthropicResponse resp = resp2;

        logger.info("");
        logger.info("[3/4] Response text:");
        logger.info("------------------------------------------------------------");
        logger.info("{}", resp.text());
        logger.info("------------------------------------------------------------");
        logger.info("Inline citations: {}", resp.citations().size());
        resp.citations().forEach(c ->
                logger.info("  - [{}] {}", c.location(),
                        c.citedText().length() > 80
                                ? c.citedText().substring(0, 80) + "…"
                                : c.citedText()));

        logger.info("");
        logger.info("[4/4] Running CitationRequiredGate.strict() …");
        ComplianceEvaluator evaluator = new ComplianceEvaluator(
                List.of(CitationRequiredGate.strict()));
        ComplianceReport report = evaluator.evaluate(
                ComplianceGate.Output.of(resp.text(), resp.citations()));

        if (report.pass()) {
            logger.info("      Verdict:         PASS — every numeric claim is cited");
        } else {
            logger.warn("      Verdict:         FAIL — {} blocker(s), {} warning(s)",
                    report.blockerCount(), report.warningCount());
            report.findings().forEach(f ->
                    logger.warn("        - [{}] {} — {}",
                            f.severity(), f.code(), f.message()));
        }

        logger.info("");
    }
}
