package ai.intelliswarm.swarmai.examples.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans pre-fetched tool evidence for common failure signals and emits warnings.
 * Shared by example workflows that pre-fetch evidence before kicking off a swarm —
 * without this, analysts inherit silent "DATA NOT AVAILABLE" stubs for tickers
 * where API keys were missing or remote calls errored.
 *
 * <p>Replaces private {@code logEvidenceWarnings()} duplicates in the
 * stock-market-analysis and iterative-investment-memo examples.
 */
public final class EvidenceWarningLogger {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceWarningLogger.class);

    private EvidenceWarningLogger() {
        // Static helper — no instantiation.
    }

    /**
     * Logs WARN-level messages if the evidence is empty, indicates missing
     * configuration (API key), or contains error text. The {@code entity}
     * parameter (typically a ticker or topic) is included in each message so
     * multi-workflow runs stay debuggable.
     */
    public static void logWarnings(String toolEvidence, String entity) {
        if (toolEvidence == null || toolEvidence.isEmpty()) {
            logger.warn("Tool evidence is empty for {}", entity);
            return;
        }

        String evidenceLower = toolEvidence.toLowerCase();
        if (evidenceLower.contains("configure") || evidenceLower.contains("api key")) {
            logger.warn("Tool evidence indicates missing API configuration for {}", entity);
        }
        if (evidenceLower.contains("error")) {
            logger.warn("Tool evidence contains errors for {}", entity);
        }
    }
}
