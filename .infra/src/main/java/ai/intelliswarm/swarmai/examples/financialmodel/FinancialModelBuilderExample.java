package ai.intelliswarm.swarmai.examples.financialmodel;

import ai.intelliswarm.swarmai.tool.finance.CompsAnalysisTool;
import ai.intelliswarm.swarmai.tool.finance.DcfModelTool;
import ai.intelliswarm.swarmai.tool.finance.LboModelTool;
import ai.intelliswarm.swarmai.tool.finance.TearSheetTool;
import ai.intelliswarm.swarmai.tool.office.XlsxAuditTool;
import ai.intelliswarm.swarmai.tool.office.XlsxAuthorTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * SwarmAI 1.0.24 — Financial-model-builder example.
 *
 * <p>Composes four finance computation tools (DCF, LBO, comps, tear sheet)
 * plus the Apache POI authoring + audit tools to produce a complete valuation
 * package as an Excel file — and then audits it for hardcodes / formula
 * errors / magic numbers.
 *
 * <p>No LLM call required — pure deterministic tool composition. Run this to
 * verify the new 1.0.24 finance + office tools are working without setting
 * up an Anthropic / OpenAI key.
 *
 * <p>Output: {@code financial-model.xlsx} in the example's output directory,
 * audit report logged to console.
 *
 * <pre>{@code
 * ./run.sh financial-model-builder
 * }</pre>
 */
@Component
public class FinancialModelBuilderExample {

    private static final Logger logger = LoggerFactory.getLogger(FinancialModelBuilderExample.class);

    private final DcfModelTool dcfModel;
    private final LboModelTool lboModel;
    private final CompsAnalysisTool compsAnalysis;
    private final XlsxAuthorTool xlsxAuthor;
    private final XlsxAuditTool xlsxAudit;
    private final TearSheetTool tearSheet;

    public FinancialModelBuilderExample() {
        Path outputDir = Paths.get(System.getProperty("user.dir"), "output", "financial-model");
        outputDir.toFile().mkdirs();
        this.dcfModel = new DcfModelTool();
        this.lboModel = new LboModelTool();
        this.compsAnalysis = new CompsAnalysisTool();
        this.xlsxAuthor = new XlsxAuthorTool(outputDir);
        this.xlsxAudit = new XlsxAuditTool(outputDir);
        this.tearSheet = new TearSheetTool(xlsxAuthor);
    }

    public void run(String[] args) {
        String ticker = args.length > 0 ? args[0] : "ACME";
        logger.info("");
        logger.info("================================================================");
        logger.info("  SwarmAI 1.0.24 — Financial Model Builder");
        logger.info("  Target: {}", ticker);
        logger.info("================================================================");
        logger.info("");

        // Step 1: DCF valuation
        @SuppressWarnings("unchecked")
        Map<String, Object> dcfResult = (Map<String, Object>) dcfModel.execute(Map.ofEntries(
                Map.entry("ticker", ticker),
                Map.entry("baseRevenue", 1000.0),
                Map.entry("baseEbitdaMargin", 0.30),
                Map.entry("taxRate", 0.25),
                Map.entry("capexPctOfRevenue", 0.05),
                Map.entry("deltaWcPctOfRevenue", 0.02),
                Map.entry("daPctOfRevenue", 0.04),
                Map.entry("projectionYears", 5),
                Map.entry("revenueGrowth", List.of(0.15, 0.12, 0.10, 0.08, 0.06)),
                Map.entry("marginExpansionPerYear", 0.005),
                Map.entry("wacc", 0.09),
                Map.entry("terminalGrowth", 0.025),
                Map.entry("netDebt", 200.0),
                Map.entry("sharesOutstanding", 100.0)));
        logger.info("[1/5] DCF Valuation");
        logger.info("      Enterprise Value: $ {} M", dcfResult.get("enterpriseValue"));
        logger.info("      Equity Value:     $ {} M", dcfResult.get("equityValue"));
        logger.info("      Per-Share:        $ {}", dcfResult.get("perShareValue"));

        // Step 2: Comparable-company analysis
        @SuppressWarnings("unchecked")
        Map<String, Object> compsResult = (Map<String, Object>) compsAnalysis.execute(Map.of(
                "target", Map.of(
                        "ticker", ticker, "revenue", 1000.0,
                        "ebitda", 300.0, "netIncome", 150.0,
                        "netDebt", 200.0, "sharesOutstanding", 100.0),
                "peers", List.of(
                        Map.of("ticker", "PEER1", "evRevenue", 7.5, "evEbitda", 19.0, "pe", 22.0),
                        Map.of("ticker", "PEER2", "evRevenue", 8.2, "evEbitda", 22.1, "pe", 25.0),
                        Map.of("ticker", "PEER3", "evRevenue", 6.8, "evEbitda", 17.5, "pe", 19.5)),
                "multiples", List.of("evRevenue", "evEbitda", "pe")));
        logger.info("[2/5] Comparable Companies");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breakdown =
                (List<Map<String, Object>>) compsResult.get("multipleBreakdown");
        for (Map<String, Object> row : breakdown) {
            logger.info("      {}  median {} → implied $/sh {}",
                    row.get("multiple"), row.get("median"), row.get("impliedPerShareAtMedian"));
        }

        // Step 3: LBO returns
        @SuppressWarnings("unchecked")
        Map<String, Object> lboResult = (Map<String, Object>) lboModel.execute(Map.ofEntries(
                Map.entry("entryEbitda", 300.0),
                Map.entry("entryMultiple", 11.0),
                Map.entry("debtPctOfPurchase", 0.55),
                Map.entry("interestRate", 0.075),
                Map.entry("holdYears", 5),
                Map.entry("ebitdaGrowth", List.of(0.08, 0.10, 0.12, 0.10, 0.07)),
                Map.entry("exitMultiple", 10.5),
                Map.entry("capexPctOfEbitda", 0.20),
                Map.entry("deltaWcPctOfEbitda", 0.05),
                Map.entry("taxRate", 0.25)));
        logger.info("[3/5] LBO Returns");
        logger.info("      Purchase Price:  $ {} M", lboResult.get("purchasePrice"));
        logger.info("      Exit Equity:     $ {} M", lboResult.get("exitEquity"));
        logger.info("      MOIC:            {}", lboResult.get("moic"));
        logger.info("      IRR:             {}", lboResult.get("irr"));

        // Step 4: Tear sheet → .xlsx
        @SuppressWarnings("unchecked")
        Map<String, Object> tearResult = (Map<String, Object>) tearSheet.execute(Map.of(
                "path", "financial-model.xlsx",
                "company", Map.of(
                        "ticker", ticker, "name", ticker + " Corp",
                        "sector", "Industrials", "marketCap", 12_000.0,
                        "ev", 13_200.0, "lastPrice", 120.0),
                "financials", List.of(
                        Map.of("year", "FY23", "revenue", 1000, "ebitda", 280, "fcf", 180, "netIncome", 130),
                        Map.of("year", "FY24", "revenue", 1200, "ebitda", 350, "fcf", 220, "netIncome", 170)),
                "multiples", breakdown.stream().map(r -> Map.of(
                        "label", String.valueOf(r.get("multiple")),
                        "value", r.get("median"))).toList()));
        logger.info("[4/5] Tear Sheet Written");
        logger.info("      File:            {}", tearResult.get("path"));
        logger.info("      Sheets:          {}", tearResult.get("sheetCount"));

        // Step 5: Audit the just-written file
        @SuppressWarnings("unchecked")
        Map<String, Object> auditResult = (Map<String, Object>) xlsxAudit.execute(
                Map.of("path", "financial-model.xlsx", "hardcodeThresholdRatio", 0.5));
        logger.info("[5/5] Excel Audit");
        logger.info("      Verdict:         {}", auditResult.get("audit"));
        logger.info("      Formulas:        {}", auditResult.get("totalFormulas"));
        logger.info("      Hardcodes:       {}", auditResult.get("totalHardcodes"));
        logger.info("      Errors:          {}", auditResult.get("totalErrors"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) auditResult.get("findings");
        if (findings != null && !findings.isEmpty()) {
            logger.info("      Findings:");
            findings.forEach(f -> logger.info("        - {} {} {}",
                    f.get("code"), f.get("cell"), f.get("detail")));
        }

        logger.info("");
        logger.info("Done. Open output/financial-model/financial-model.xlsx to review.");
        logger.info("");
    }
}
