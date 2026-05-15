package ai.intelliswarm.swarmai.examples.anthropicbatch;

import ai.intelliswarm.swarmai.llm.anthropic.AnthropicConfig;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicMessage;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicRequest;
import ai.intelliswarm.swarmai.llm.anthropic.batch.AnthropicBatchClient;
import ai.intelliswarm.swarmai.llm.anthropic.batch.BatchHandle;
import ai.intelliswarm.swarmai.llm.anthropic.batch.BatchItem;
import ai.intelliswarm.swarmai.llm.anthropic.batch.BatchItemOutcome;
import ai.intelliswarm.swarmai.llm.anthropic.batch.BatchProcessingStatus;
import ai.intelliswarm.swarmai.llm.anthropic.batch.BatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SwarmAI 1.0.24 — Anthropic Message Batches end-to-end.
 *
 * <p>Submits five synthetic 10-K excerpts as a single batch, polls until
 * ended, then joins each per-item outcome back to its originating customId
 * and prints a one-line headline per company. Demonstrates:
 *
 * <ol>
 *   <li>Building a {@link BatchItem} list — caller picks the customIds</li>
 *   <li>{@link AnthropicBatchClient#submit(List)} returns a handle immediately</li>
 *   <li>Polling {@link AnthropicBatchClient#status(String)} until
 *       {@link BatchProcessingStatus#ENDED}</li>
 *   <li>{@link AnthropicBatchClient#results(String)} streams the per-item
 *       outcomes; {@code switch} over the sealed {@link BatchItemOutcome}
 *       handles all four cases (Succeeded / Errored / Canceled / Expired)</li>
 *   <li>Per-item cost telemetry — batch calls are billed at 50% of the
 *       standard request price</li>
 * </ol>
 *
 * <p>Requires {@code ANTHROPIC_API_KEY}.
 */
@Component
public class AnthropicBatchExample {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicBatchExample.class);

    /**
     * Five synthetic filings — kept short so the demo runs quickly and the
     * output stays readable. Real workloads would attach DocumentBlocks with
     * citations enabled (see CitationRequiredPipelineExample).
     */
    private static final Map<String, String> FILINGS = Map.of(
            "ACME-FY25",
            "ACME Corp FY25: revenue $1,200M (+20.0% y/y), EBITDA $360M (+28.6%), "
                    + "free cash flow $220M (+22.2%), net debt $200M, 100M shares.",
            "GLOBEX-FY25",
            "Globex Industries FY25: revenue $4,800M (+8.5%), EBITDA $720M (+12.0%), "
                    + "free cash flow $510M (+18.7%), net debt $1,100M, 250M shares.",
            "INITECH-FY25",
            "Initech FY25: revenue $890M (-3.2%), EBITDA $134M (-11.4%), "
                    + "free cash flow $62M (-28.0%), net debt $310M, 45M shares. Margin compression flagged.",
            "UMBRELLA-FY25",
            "Umbrella Corp FY25: revenue $2,100M (+14.0%), EBITDA $588M (+19.5%), "
                    + "free cash flow $315M (+22.4%), net debt $640M, 80M shares.",
            "CYBERDYNE-FY25",
            "Cyberdyne Systems FY25: revenue $3,400M (+11.2%), EBITDA $1,020M (+15.8%), "
                    + "free cash flow $720M (+19.0%), net debt $850M, 140M shares.");

    public void run(String[] args) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            logger.error("ANTHROPIC_API_KEY env var not set. This example calls the real Anthropic API.");
            return;
        }

        logger.info("");
        logger.info("================================================================");
        logger.info("  SwarmAI 1.0.24 — Anthropic Message Batches");
        logger.info("  {} filings → one batch → joined results", FILINGS.size());
        logger.info("================================================================");
        logger.info("");

        AnthropicBatchClient batch = AnthropicBatchClient.from(AnthropicConfig.withApiKey(apiKey));

        // ---- [1/4] Build the batch ----
        List<BatchItem> items = new ArrayList<>(FILINGS.size());
        // LinkedHashMap iteration is insertion-order, but Map.of() isn't —
        // sort by ticker so output is deterministic.
        for (var entry : new java.util.TreeMap<>(FILINGS).entrySet()) {
            String customId = entry.getKey();
            String filing = entry.getValue();
            AnthropicRequest req = AnthropicRequest.builder()
                    .model("claude-sonnet-4-5")
                    .system("You are a financial analyst. In one sentence, name the company, "
                            + "the headline number (revenue), and the single most important "
                            + "year-over-year change. No commentary, no disclaimers.")
                    .message(AnthropicMessage.userText("Filing excerpt:\n" + filing))
                    .maxOutputTokens(120)
                    .temperature(0.0)
                    .build();
            items.add(new BatchItem(customId, req));
        }
        logger.info("[1/4] Built {} batch items (customIds: {})",
                items.size(), items.stream().map(BatchItem::customId).toList());

        // ---- [2/4] Submit ----
        Instant submitAt = Instant.now();
        BatchHandle handle = batch.submit(items);
        logger.info("[2/4] Submitted batch {}", handle.id());
        logger.info("      Status:          {}", handle.processingStatus());
        logger.info("      Submitted at:    {}", submitAt);
        logger.info("      Expires at:      {}  (Anthropic guarantees ≤24h)", handle.expiresAt());
        logger.info("      Counts:          {} processing / {} succeeded / {} errored",
                handle.requestCounts().processing(),
                handle.requestCounts().succeeded(),
                handle.requestCounts().errored());

        // ---- [3/4] Poll until ended ----
        logger.info("");
        logger.info("[3/4] Polling status …");
        int poll = 0;
        while (handle.processingStatus() != BatchProcessingStatus.ENDED) {
            if (poll++ > 60) {
                logger.warn("Aborting poll after 5 minutes — batches usually finish in seconds for small workloads.");
                return;
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            handle = batch.status(handle.id());
            logger.info("      [{}s] status={} processing={} succeeded={} errored={}",
                    poll * 5, handle.processingStatus(),
                    handle.requestCounts().processing(),
                    handle.requestCounts().succeeded(),
                    handle.requestCounts().errored());
        }
        Duration wall = Duration.between(submitAt, Instant.now());
        logger.info("      Ended after:     {}s wall time", wall.toSeconds());

        // ---- [4/4] Join results back to filings ----
        logger.info("");
        logger.info("[4/4] Joining results back to filings:");
        logger.info("");
        Map<String, BatchResult> byId = new LinkedHashMap<>();
        for (BatchResult r : batch.results(handle.id())) {
            byId.put(r.customId(), r);
        }
        long totalIn = 0, totalOut = 0;
        int succeeded = 0, errored = 0, canceled = 0, expired = 0;
        int factsOk = 0, factsFail = 0;
        // Iterate in the same order as the input so the output reads top-to-bottom.
        for (String customId : new java.util.TreeSet<>(FILINGS.keySet())) {
            BatchResult r = byId.get(customId);
            if (r == null) {
                logger.warn("  {} → missing in result stream", customId);
                continue;
            }
            switch (r.outcome()) {
                case BatchItemOutcome.Succeeded ok -> {
                    succeeded++;
                    totalIn  += ok.response().tokenUsage().inputTokens();
                    totalOut += ok.response().tokenUsage().outputTokens();
                    String text = ok.response().text().replace('\n', ' ').trim();
                    // Self-verify: the headline revenue is asserted in every filing; if the
                    // model's summary contains the exact dollar figure, we count it as
                    // factually grounded. This turns the demo into a self-checking eval.
                    String expectedRevenue = REVENUE_BY_TICKER.get(customId);
                    boolean factCheck = expectedRevenue != null && text.contains(expectedRevenue);
                    if (factCheck) {
                        factsOk++;
                    } else {
                        factsFail++;
                    }
                    logger.info("  [OK]   {}  {}", customId,
                            factCheck ? "(fact-check ✓ contains " + expectedRevenue + ")"
                                    : "(fact-check ✗ missing " + expectedRevenue + ")");
                    logger.info("         {}", text);
                    logger.info("         tokens in/out: {}/{}",
                            ok.response().tokenUsage().inputTokens(),
                            ok.response().tokenUsage().outputTokens());
                }
                case BatchItemOutcome.Errored err -> {
                    errored++;
                    logger.warn("  [ERR]  {} — {}: {}", customId, err.errorType(), err.message());
                }
                case BatchItemOutcome.Canceled c -> {
                    canceled++;
                    logger.info("  [CXL]  {}", customId);
                }
                case BatchItemOutcome.Expired e -> {
                    expired++;
                    logger.info("  [EXP]  {}", customId);
                }
            }
        }

        // ---- Cost modelling: Sonnet 4.5 published price (Q1-2026), 50% batch discount ----
        // https://www.anthropic.com/pricing#anthropic-api
        final double pricePerInputMtok = 3.00;     // standard $/Mtok input
        final double pricePerOutputMtok = 15.00;   // standard $/Mtok output
        double standardCost = (totalIn / 1_000_000.0) * pricePerInputMtok
                            + (totalOut / 1_000_000.0) * pricePerOutputMtok;
        double batchCost = standardCost * 0.50;
        double saved = standardCost - batchCost;

        logger.info("");
        logger.info("Summary:");
        logger.info("  {} succeeded / {} errored / {} canceled / {} expired",
                succeeded, errored, canceled, expired);
        logger.info(String.format(Locale.ROOT,
                "  Fact-check:    %d/%d summaries contain the source-of-truth revenue figure",
                factsOk, factsOk + factsFail));
        logger.info("  Total tokens:  {} input / {} output", totalIn, totalOut);
        logger.info(String.format(Locale.ROOT,
                "  Cost:          $%.6f (batch)  vs.  $%.6f (single-shot)  →  $%.6f saved on this run",
                batchCost, standardCost, saved));
        logger.info(String.format(Locale.ROOT,
                "  At 100K items / batch this scales to ≈ $%.0f saved per run.",
                saved * (100_000.0 / Math.max(1, succeeded))));
        logger.info(String.format(Locale.ROOT,
                "  Wall time:     %ds (batches are throughput-optimised — small jobs may "
                        + "take a minute or two; very large jobs can take up to 24h).",
                wall.toSeconds()));
        logger.info("");
    }

    /**
     * Source-of-truth headline revenue per ticker. Used by the self-verification
     * step: if the model's summary contains this exact dollar figure, the
     * extraction is grounded.
     */
    private static final Map<String, String> REVENUE_BY_TICKER = Map.of(
            "ACME-FY25",     "$1,200M",
            "GLOBEX-FY25",   "$4,800M",
            "INITECH-FY25",  "$890M",
            "UMBRELLA-FY25", "$2,100M",
            "CYBERDYNE-FY25","$3,400M");
}
