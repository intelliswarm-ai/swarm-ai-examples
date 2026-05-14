package ai.intelliswarm.swarmai.examples.mcpconnectorstatus;

import ai.intelliswarm.swarmai.spi.LicenseProvider;
import ai.intelliswarm.swarmai.tool.finance.mcp.FinancialMcpConnectors;
import ai.intelliswarm.swarmai.tool.finance.mcp.McpConnectorBinder;
import ai.intelliswarm.swarmai.tool.finance.mcp.McpConnectorRegistry;
import ai.intelliswarm.swarmai.tool.finance.mcp.McpCredentialResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * SwarmAI 1.0.24 — MCP Connector Status example.
 *
 * <p>Prints the {@link McpConnectorRegistry} + {@link McpConnectorBinder}
 * status reports under a configurable license edition + feature-flag set.
 * Demonstrates Phase 8's license-aware filtering and the Phase 15 credential
 * binding without making any actual network calls.
 *
 * <p>Args (all optional):
 * <pre>{@code
 *   ./run.sh mcp-connector-status [edition] [comma-separated-features]
 *
 *   ./run.sh mcp-connector-status COMMUNITY
 *   ./run.sh mcp-connector-status ENTERPRISE mcp.lseg,mcp.factset
 *   ./run.sh mcp-connector-status ENTERPRISE all
 * }</pre>
 */
@Component
public class McpConnectorStatusExample {

    private static final Logger logger = LoggerFactory.getLogger(McpConnectorStatusExample.class);

    private static final Set<String> ALL_FEATURES = Set.of(
            "mcp.lseg", "mcp.sp_capital_iq", "mcp.factset", "mcp.pitchbook",
            "mcp.chronograph", "mcp.daloopa", "mcp.morningstar", "mcp.moodys",
            "mcp.mt_newswires", "mcp.aiera", "mcp.egnyte");

    public void run(String[] args) {
        LicenseProvider.Edition edition = args.length > 0
                ? LicenseProvider.Edition.valueOf(args[0].toUpperCase())
                : LicenseProvider.Edition.ENTERPRISE;
        Set<String> features = args.length > 1
                ? ("all".equalsIgnoreCase(args[1]) ? ALL_FEATURES : Set.of(args[1].split(",")))
                : ALL_FEATURES;

        LicenseProvider license = new LicenseProvider() {
            @Override public LicenseInfo getLicense() {
                return new LicenseInfo("demo", edition,
                        Instant.now().plusSeconds(86_400), 100, features);
            }
            @Override public boolean hasFeature(String f) { return features.contains(f); }
            @Override public boolean isValid() { return true; }
        };

        logger.info("");
        logger.info("================================================================");
        logger.info("  SwarmAI 1.0.24 — Financial MCP Connector Status");
        logger.info("  License: {} (features: {})", edition, features.size());
        logger.info("================================================================");
        logger.info("");

        McpConnectorRegistry registry = McpConnectorRegistry.defaultRegistry(license);

        logger.info("Catalog ({} connectors total):", FinancialMcpConnectors.all().size());
        logger.info("Active under this license: {}", registry.activeConnectors().size());
        logger.info("");

        logger.info(String.format("%-7s %-16s %-28s %-12s %s",
                "status", "name", "vendor", "edition", "reason"));
        logger.info("{}", "─".repeat(110));
        registry.statusReport().forEach((name, result) -> {
            String marker = switch (result.status()) {
                case ACTIVE -> "[OK]   ";
                case LICENSE_FLOOR_NOT_MET -> "[X]    ";
                case FEATURE_FLAG_MISSING -> "[FLAG] ";
                case DISABLED -> "[OFF]  ";
            };
            logger.info(String.format("%-7s %-16s %-28s %-12s %s",
                    marker, name,
                    result.definition() != null ? result.definition().vendor() : "—",
                    result.definition() != null ? result.definition().requiredEdition().toString() : "—",
                    result.reason()));
        });

        // Now show what a credential-aware binder would activate.
        // We use an InMemory resolver populated only for two connectors to show
        // CREDENTIALS_MISSING vs READY.
        logger.info("");
        logger.info("Bind status (with credentials for lseg-lfa + factset):");
        logger.info("");

        McpCredentialResolver creds = new McpCredentialResolver.InMemory(java.util.Map.of(
                "lseg-lfa", ai.intelliswarm.swarmai.tool.finance.mcp.McpCredentials.bearer("demo-token"),
                "factset", ai.intelliswarm.swarmai.tool.finance.mcp.McpCredentials.apiKey("demo-key")));
        McpConnectorBinder binder = new McpConnectorBinder(registry, creds);
        binder.statusReport().forEach((name, status) -> {
            String marker = switch (status.result()) {
                case READY -> "[READY]";
                case CREDENTIALS_MISSING -> "[NEED CREDS]";
                case NOT_ACTIVE -> "[INACTIVE]";
                case FAILED -> "[FAILED]";
            };
            logger.info("  {} {} — {}", marker, name, status.reason());
        });

        logger.info("");
        logger.info("Try other license tiers:");
        logger.info("  ./run.sh mcp-connector-status COMMUNITY");
        logger.info("  ./run.sh mcp-connector-status TEAM mcp.egnyte");
        logger.info("  ./run.sh mcp-connector-status BUSINESS mcp.daloopa,mcp.morningstar");
        logger.info("");
    }
}
