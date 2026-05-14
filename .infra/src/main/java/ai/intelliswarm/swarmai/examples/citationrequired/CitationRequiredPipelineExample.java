package ai.intelliswarm.swarmai.examples.citationrequired;

import ai.intelliswarm.swarmai.agent.llm.CacheBreakpoint;
import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.CitationConfig;
import ai.intelliswarm.swarmai.agent.llm.DocumentBlock;
import ai.intelliswarm.swarmai.agent.llm.ThinkingBudget;
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
                .thinkingBudget(ThinkingBudget.enabled(2048))
                .citationConfig(CitationConfig.ENABLED)
                .build();

        logger.info("[1/3] Sending request to Anthropic …");
        AnthropicResponse resp = client.send(req);
        logger.info("      Model:           {}", resp.modelId());
        logger.info("      Stop reason:     {}", resp.stopReason());
        logger.info("      Input tokens:    {}", resp.tokenUsage().inputTokens());
        logger.info("      Output tokens:   {}", resp.tokenUsage().outputTokens());
        logger.info("      Cache read:      {} tokens",
                resp.tokenUsage().cacheUsage().cacheReadInputTokens());
        logger.info("      Cache write 5m:  {} tokens",
                resp.tokenUsage().cacheUsage().cacheCreationFiveMinuteInputTokens());
        logger.info("      Cache write 1h:  {} tokens",
                resp.tokenUsage().cacheUsage().cacheCreationOneHourInputTokens());

        logger.info("");
        logger.info("[2/3] Response text:");
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
        logger.info("[3/3] Running CitationRequiredGate.strict() …");
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
        logger.info("Run again — the second run should show cache_read > 0 (5m TTL).");
        logger.info("");
    }
}
