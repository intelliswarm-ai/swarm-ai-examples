package ai.intelliswarm.swarmai.examples.stock;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.CompactionConfig;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.observability.core.ObservabilityHelper;
import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.decision.DecisionTree;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.observability.replay.WorkflowRecording;

import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import ai.intelliswarm.swarmai.tool.common.FinancialDataTool;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import ai.intelliswarm.swarmai.tool.common.SECFilingsTool;
import ai.intelliswarm.swarmai.tool.common.finance.FinancialEvidenceBuilder;
import ai.intelliswarm.swarmai.tool.common.finance.IssuerProfile;
import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import org.springframework.boot.SpringApplication;

@Component
public class StockAnalysisWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(StockAnalysisWorkflow.class);

    @org.springframework.beans.factory.annotation.Value("${swarmai.workflow.model:o3-mini}")
    private String workflowModel;

    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final CalculatorTool calculatorTool;
    private final WebSearchTool webSearchTool;
    private final SECFilingsTool secFilingsTool;
    private final FinancialDataTool financialDataTool;
    private final FinancialEvidenceBuilder evidence;

    // Observability components
    private final ObservabilityHelper observabilityHelper;
    private final DecisionTracer decisionTracer;
    private final EventStore eventStore;

    public StockAnalysisWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            CalculatorTool calculatorTool,
            WebSearchTool webSearchTool,
            SECFilingsTool secFilingsTool,
            FinancialDataTool financialDataTool,
            FinancialEvidenceBuilder evidence,
            @Autowired(required = false) ObservabilityHelper observabilityHelper,
            @Autowired(required = false) DecisionTracer decisionTracer,
            @Autowired(required = false) EventStore eventStore) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.calculatorTool = calculatorTool;
        this.webSearchTool = webSearchTool;
        this.secFilingsTool = secFilingsTool;
        this.financialDataTool = financialDataTool;
        this.evidence = evidence;
        this.observabilityHelper = observabilityHelper;
        this.decisionTracer = decisionTracer;
        this.eventStore = eventStore;
    }

    public void run(String... args) throws Exception {
        logger.info("📊 Starting Stock Analysis Workflow with SwarmAI Framework (Enhanced Tool Routing)");

        try {
            String companyStock = args.length > 0 ? args[0].trim().toUpperCase() : "IMPP";

            // Pre-flight ticker validation. Fails fast with a clear error before spending
            // $0.15+ on an analyst workflow that has no data to analyze.
            try {
                var v = evidence.validateOrFail(companyStock);
                logger.info("✅ Ticker validated: {} ({})", companyStock, v.companyName());
            } catch (IllegalArgumentException e) {
                logger.error("");
                logger.error("===========================================================");
                logger.error("  ❌ {}", e.getMessage());
                logger.error("  Usage: stock-analysis <TICKER>  (e.g. AAPL, MSFT, TSLA, IMPP)");
                logger.error("===========================================================");
                logger.error("");
                throw e;
            }

            runStockAnalysisWorkflow(companyStock);
        } catch (Exception e) {
            logger.error("❌ Error running stock analysis workflow", e);
            throw e;
        }
    }

    private void runStockAnalysisWorkflow(String companyStock) {
        logger.info("Analyzing stock: {}", companyStock);

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("stock-analysis");
        metrics.start();

        ChatClient chatClient = chatClientBuilder.build();

        String toolEvidence = evidence.build(companyStock);
        logEvidenceWarnings(toolEvidence, companyStock);

        // Detect whether this is a domestic filer (10-K/10-Q) or a foreign private
        // issuer (20-F/6-K like IMPP). Task prompts reference the correct filing types
        // based on this, so foreign-issuer runs don't tell the LLM to look for 10-Ks
        // that don't exist.
        IssuerProfile issuerProfile = evidence.detectIssuer(toolEvidence);
        logger.info("Issuer profile for {}: foreign={}, annual-form={}, quarterly-form={}",
                companyStock, issuerProfile.isForeign(),
                issuerProfile.primaryAnnualForm(), issuerProfile.primaryQuarterlyForm());

        // Reusable citation-rule text embedded into every analyst task prompt. Prior
        // runs produced fabricated XBRL tags next to "DATA NOT AVAILABLE"; this rule
        // makes the absence-vs-citation distinction explicit.
        String citationRule = String.format(
                "CITATION RULE (CRITICAL):\n" +
                "- Every quantitative figure (revenue, income, margin %%, EPS, share count, $ flow) you " +
                "report MUST carry ONE citation. Acceptable forms:\n" +
                "  * [XBRL: Concept, Period, Form] — for numbers sourced from the Key Financials (XBRL) section\n" +
                "  * [%s <filing-date>] — for numbers / quotes sourced from an annual filing body\n" +
                "  * [%s <filing-date>] — for numbers / quotes sourced from a quarterly filing body\n" +
                "  * [Form 4 <filing-date>] — for insider transaction details\n" +
                "  * [Web: <source>, <date>] — for news / analyst figures from web search results\n" +
                "- If a figure is genuinely DATA NOT AVAILABLE after you have checked the Key Financials " +
                "section, the MD&A Highlights section, and the filing bodies, write EXACTLY " +
                "\"DATA NOT AVAILABLE\" with NO bracketed tag. Fabricating an [XBRL: ...] tag next to " +
                "\"DATA NOT AVAILABLE\" is strictly prohibited — the verifier will detect it and flag " +
                "it as an INCONSISTENCY, lowering the recommendation confidence.\n" +
                "- Do NOT invent XBRL concept names (valid concepts have names like \"Revenues\", " +
                "\"NetIncomeLoss\", \"OperatingIncomeLoss\", never \"NetMargin\" or \"Period1\").",
                issuerProfile.primaryAnnualForm(), issuerProfile.primaryQuarterlyForm());

        // =====================================================================
        // TOOL HEALTH CHECK — Filter out non-operational tools before assignment
        // =====================================================================

        List<BaseTool> allTools = List.of(calculatorTool, webSearchTool, secFilingsTool, financialDataTool);
        List<BaseTool> healthyTools = ToolHealthChecker.filterOperational(allTools);
        if (healthyTools.size() < allTools.size()) {
            logger.warn("Tool health check: {}/{} tools operational", healthyTools.size(), allTools.size());
            Map<String, ToolHealthChecker.HealthCheckResult> results = ToolHealthChecker.checkAll(allTools);
            results.forEach((name, result) -> {
                if (!result.healthy()) {
                    logger.warn("  {} UNHEALTHY: {}", name, result.issues());
                }
            });
        }

        // Log tool routing metadata for diagnostics
        logToolRoutingMetadata(healthyTools);

        // Partition healthy tools by role for targeted agent assignment.
        List<BaseTool> financialTools = healthyTools.stream()
            .filter(t -> t == calculatorTool || t == webSearchTool || t == secFilingsTool || t == financialDataTool)
            .collect(Collectors.toList());
        List<BaseTool> researchTools = healthyTools.stream()
            .filter(t -> t == webSearchTool || t == secFilingsTool || t == financialDataTool)
            .collect(Collectors.toList());

        // =====================================================================
        // AGENTS - Each agent has an accuracy-focused goal and rigorous backstory
        // =====================================================================

        Agent dataValidator = Agent.builder()
                .role("Stock Data Completeness Validator")
                .goal("Before the financial, research, and filings analysts run in parallel, inspect " +
                      "the pre-fetched tool evidence for " + companyStock + " and determine whether " +
                      "it is sufficient. Categorize each expected input (web search results, SEC " +
                      "filings, financial metrics, news coverage) as [OK], [MISSING], [PARTIAL], or " +
                      "[STALE]. Produce a 'Data Completeness Report' the analysts reference when " +
                      "explaining confidence levels and data gaps.")
                .backstory("You are a data quality steward for equity research. You audit raw tool " +
                          "outputs before analysts touch them, flagging empty responses, API errors, " +
                          "missing filings, and stale news. You recommend PROCEED, PROCEED-WITH-CAVEATS, " +
                          "or HALT based on data availability so analysts can calibrate confidence.")
                .chatClient(chatClient)
                // No tools — inspects the pre-fetched toolEvidence string passed via the task prompt
                .verbose(true)
                .maxRpm(10)
                .maxTurns(1)
                .toolHook(metrics.metricsHook())
                .temperature(0.1)
                .build();

        Agent portfolioManager = Agent.builder()
                .role("Senior Portfolio Manager")
                .goal("Coordinate a rigorous, evidence-based stock analysis workflow. " +
                      "Ensure each specialist provides quantitative data with cited sources. " +
                      "Reject vague or unsubstantiated claims in the final synthesis.")
                .backstory("You are a CFA charterholder with 15 years managing institutional equity portfolios. " +
                           "You are known for demanding analytical rigor from your team. " +
                           "You reject reports that contain unsubstantiated claims or generic filler. " +
                           "Every number must have a source, and every recommendation must have a confidence level.")
                .chatClient(chatClient)
                .verbose(true)
                .allowDelegation(true)
                .maxRpm(10)
                .maxTurns(1)
                .toolHook(metrics.metricsHook())
                .temperature(0.2)
                .build();

        Agent financialAnalyst = Agent.builder()
                .role("Senior Financial Analyst")
                .goal("Produce accurate, evidence-based financial analysis using quantitative data " +
                      "from SEC filings and market sources. Every claim must cite its data source. " +
                      "When data is unavailable, state it explicitly rather than estimating.")
                .backstory("You are a CFA-certified equity research analyst with 10 years of experience " +
                           "covering technology and large-cap stocks. You prioritize accuracy over narrative appeal. " +
                           "You never fabricate numbers or present estimates as facts. " +
                           "Your reports are trusted because they clearly distinguish between hard data, " +
                           "analyst estimates, and your own assumptions.")
                .chatClient(chatClient)
                .tools(financialTools)
                .verbose(true)
                .maxRpm(10)
                .maxTurns(3)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .temperature(0.1)
                .build();

        Agent researchAnalyst = Agent.builder()
                .role("Senior Equity Research Analyst")
                .goal("Gather, verify, and summarize recent market intelligence including news, " +
                      "analyst opinions, and industry developments. Cite the source and date for " +
                      "every factual claim. Distinguish between confirmed facts and market speculation.")
                .backstory("You are a research analyst with 8 years of experience in equity research " +
                           "at a top-tier investment bank. You are methodical and skeptical: you only report " +
                           "information that has a verifiable source. You always note the date and origin " +
                           "of each piece of information. You clearly separate confirmed facts from " +
                           "analyst opinions and market rumors.")
                .chatClient(chatClient)
                .tools(researchTools)
                .verbose(true)
                .maxRpm(12)
                .maxTurns(3)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .temperature(0.2)
                .build();

        Agent investmentAdvisor = Agent.builder()
                .role("Senior Investment Advisor")
                .goal("Synthesize financial analysis, research findings, and SEC filing data into a " +
                      "calibrated investment recommendation. Ground every recommendation in specific " +
                      "evidence from prior analyses. State confidence levels explicitly.")
                .backstory("You are a Series 65 licensed investment advisor with 12 years of experience " +
                           "in wealth management. You provide calibrated assessments: you state your " +
                           "confidence level (HIGH/MEDIUM/LOW) for each recommendation. You never overstate " +
                           "certainty or understate risk. You always reference the specific data points that " +
                           "support your conclusions, citing which prior analysis they came from.")
                .chatClient(chatClient)
                // No tools — synthesis agent reasons over prior outputs, doesn't need tool access
                .verbose(true)
                .maxRpm(10)
                .maxTurns(1)
                .toolHook(metrics.metricsHook())
                .temperature(0.2)
                .build();

        // Gap Remediator agent — runs AFTER the verifier, has tool access. For each
        // gap the verifier confirmed, this agent performs a targeted reformulated
        // query with web_search / sec_filings and either fills it (REMEDIATED) or
        // confirms it's truly absent (STILL MISSING).
        Agent gapRemediator = Agent.builder()
                .role("Data Gap Remediator")
                .goal("For each CONFIRMED GAP identified by the verifier, design and execute ONE " +
                      "targeted reformulated query using web_search or sec_filings tools. Return either " +
                      "'REMEDIATED: <metric> = <value> [<citation>]' or 'STILL MISSING: <metric>' for each.")
                .backstory("You are a meticulous retrieval specialist. You know that the first query " +
                           "an analyst writes often misses the specific data point, but a reformulated " +
                           "query (narrower scope, different keywords, form-specific) usually finds it. " +
                           "You NEVER fabricate citations. When a gap is truly unfillable after your " +
                           "reformulated query, you say so cleanly.")
                .chatClient(chatClient)
                .tools(financialTools)
                .verbose(true)
                .maxRpm(10)
                .maxTurns(3)
                .compactionConfig(CompactionConfig.of(3, 4000))
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .temperature(0.1)
                .build();

        // Consensus/Verifier agent — runs AFTER the three analyst streams and BEFORE the
        // final recommendation. Catches "DATA NOT AVAILABLE" escape hatches the analysts
        // use when they skip hard work.
        Agent consensusVerifier = Agent.builder()
                .role("Data Completeness & Consensus Verifier")
                .goal("Scan the three analyst outputs for any 'DATA NOT AVAILABLE', 'unavailable', " +
                      "'could not extract', or otherwise-missing quantitative answers. For EACH gap, " +
                      "inspect the raw tool evidence (particularly the 'Key Financials (XBRL)' section) " +
                      "and either fill the gap with a cited figure or confirm the gap is genuine. " +
                      "Also verify that the three analyst outputs don't contradict each other on " +
                      "numeric facts (revenue, EPS, etc.) and flag any inconsistencies.")
                .backstory("You are a forensic auditor of equity research. You have zero tolerance for " +
                           "analysts using 'DATA NOT AVAILABLE' as a shortcut when the data actually IS " +
                           "in the tool evidence. You read the XBRL-cited figures line by line and " +
                           "rescue numbers that analysts missed. When the data is truly absent, you say " +
                           "so explicitly and the downstream recommendation confidence must drop.")
                .chatClient(chatClient)
                // No tools — this agent reasons over the evidence + analyst outputs provided in the prompt
                .verbose(true)
                .maxRpm(10)
                .maxTurns(2)
                .toolHook(metrics.metricsHook())
                .temperature(0.1)
                .build();

        // =====================================================================
        // TASKS - Specific, numbered requirements with data grounding rules
        // =====================================================================

        Task validationTask = Task.builder()
                .description(String.format(
                        "Validate the pre-fetched tool evidence for %s before the 3 analyst streams run.\n\n" +
                        "INSPECT the evidence below and categorize each expected input as:\n" +
                        "- [OK]      — data is present and usable\n" +
                        "- [MISSING] — data is absent (e.g., empty response, API error)\n" +
                        "- [PARTIAL] — data is present but incomplete\n" +
                        "- [STALE]   — data exists but appears outdated\n\n" +
                        "EXPECTED INPUTS TO CHECK:\n" +
                        "1. Web search results (news, market commentary)\n" +
                        "2. SEC filings summary (10-K/10-Q/8-K presence, dates)\n" +
                        "3. Recent financial metrics (revenue, earnings, ratios)\n" +
                        "4. Insider transaction data\n\n" +
                        "PRODUCE a 'Data Completeness Report' ending with a PROCEED / " +
                        "PROCEED-WITH-CAVEATS / HALT recommendation. The financial, research, and " +
                        "filings analysts will consult this report when describing Data Gaps.\n\n" +
                        "TOOL EVIDENCE:\n%s",
                        companyStock, toolEvidence))
                .expectedOutput("Markdown 'Data Completeness Report' listing each input with " +
                               "[OK]/[MISSING]/[PARTIAL]/[STALE] category and a PROCEED/" +
                               "PROCEED-WITH-CAVEATS/HALT recommendation")
                .agent(dataValidator)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(90000)
                .build();

        Task financialAnalysisTask = Task.builder()
                .description(String.format(
                        "Analyze %s's financial health using ONLY the tool evidence provided below.\n\n" +
                        "IMPORTANT — the tool evidence contains multiple structured sections at the top. " +
                        "Use them in this priority order:\n" +
                        "  FINNHUB (primary, clean JSON-backed financial data):\n" +
                        "    - '## Income Statement (annual)' — revenue, YoY growth, gross/operating/net margins, net income\n" +
                        "    - '## Revenue (recent quarters)' — quarterly revenue with QoQ and YoY\n" +
                        "    - '## Balance Sheet (most recent annual)' — assets, liabilities, equity, cash\n" +
                        "    - '## Cash Flow (most recent annual)' — operating / investing / financing\n" +
                        "    - '## Key Metrics & Ratios' — P/E, P/B, ROE, TTM margins, revenue growth\n" +
                        "    - '## Insider Transactions (last 90 days)' — net $ flow with per-tx rows\n" +
                        "    Cite Finnhub figures as [Finnhub: <metric>, <period>].\n" +
                        "  SEC FILINGS (secondary, for MD&A text and XBRL cross-check):\n" +
                        "    - '## Key Financials (XBRL)' — cross-check Finnhub numbers against XBRL\n" +
                        "    - '## MD&A Highlights' — themed risk / liquidity / guidance bullets\n" +
                        "    - '## Insider Transaction Flow' — cross-check Finnhub insider totals\n" +
                        "    Cite SEC figures as [XBRL: Concept, Period, Form] or [Form <date>].\n" +
                        "Use Finnhub FIRST for financial metrics; use SEC for MD&A narrative and as a cross-check.\n\n" +
                        "%s\n\n" +
                        "REQUIRED ANALYSIS (address each numbered item with concrete numbers):\n" +
                        "1. Revenue — most recent annual + YoY growth %% (from XBRL Revenue row)\n" +
                        "2. Net income + net margin %% (from XBRL Profitability table)\n" +
                        "3. Gross margin %% and operating margin %% trend across last 3 annual periods\n" +
                        "4. EPS (diluted preferred) for last 3 annual periods (from XBRL EPS table)\n" +
                        "5. Key ratios: P/E, P/B, debt-to-equity, current ratio, ROE (cite the source " +
                        "for each — use web-search results for market multiples and XBRL for accounting ratios)\n" +
                        "6. Operating cash flow (most recent annual, from XBRL section)\n" +
                        "7. Comparison with 2-3 industry peers on at least 3 shared financial metrics\n" +
                        "8. Top 3 financial risks with specific supporting evidence (preferably quoting " +
                        "a bullet from the MD&A Highlights Risks section)\n\n" +
                        "TOOL EVIDENCE:\n%s",
                        companyStock, citationRule, toolEvidence))
                .expectedOutput("Markdown report with exactly these sections:\n" +
                        "1. Executive Summary (3-5 bullet points — MUST include latest revenue $ and YoY growth %, " +
                        "latest net margin %, and latest EPS, all with XBRL citations, max 100 words)\n" +
                        "2. Financial Metrics Table — at minimum these rows, each with a value and a citation:\n" +
                        "   Revenue (annual), Revenue YoY growth %, Net Income, Net Margin %, Gross Margin %, " +
                        "Operating Margin %, EPS Diluted, Operating Cash Flow, Total Assets, Total Liabilities. " +
                        "Every row's citation column must show [XBRL: Concept, Period, Form] for XBRL-sourced items.\n" +
                        "3. Margin Trend — 3-year gross/operating/net margin trajectory (table with 3 periods)\n" +
                        "4. Peer Comparison Table (2-3 peers, 3+ shared metrics — identify peers by ticker)\n" +
                        "5. Risk Assessment (3 risks, each with a numeric anchor — e.g., 'debt/equity 1.8x vs. peer median 0.9x')\n" +
                        "6. Data Gaps — list ONLY metrics genuinely missing after checking XBRL section; " +
                        "for each gap, state which source was checked (XBRL / 10-K / web search).")
                .agent(financialAnalyst)
                .dependsOn(validationTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task researchTask = Task.builder()
                .description(String.format(
                        "Research recent market intelligence for %s using ONLY the tool evidence below.\n\n" +
                        "%s\n\n" +
                        "REQUIRED SECTIONS (address each numbered item):\n" +
                        "1. Recent News: 3-5 most significant news items, each with date and source. " +
                        "Cite as [Web: <source>, <date>].\n" +
                        "2. Market Sentiment: Bull case vs. bear case with specific evidence for each " +
                        "(preferably anchored to an XBRL-cited figure or an MD&A Highlights bullet)\n" +
                        "3. Industry Context: 2-3 industry trends affecting %s, with sources\n" +
                        "4. Upcoming Catalysts: Earnings dates, product launches, regulatory events\n" +
                        "5. Data Gaps: What data sources were unavailable or incomplete (apply the citation rule: " +
                        "write \"DATA NOT AVAILABLE\" alone, never with a fabricated tag).\n\n" +
                        "DATA RULES:\n" +
                        "- Only report news items that appear in the tool evidence\n" +
                        "- Distinguish between confirmed facts and analyst opinions\n" +
                        "- Do NOT invent or assume news events not in the evidence\n\n" +
                        "TOOL EVIDENCE:\n%s",
                        companyStock, citationRule, companyStock, toolEvidence))
                .expectedOutput(String.format("Markdown report for %s with sections: " +
                        "Recent News (3-5 items with dates), Bull/Bear Cases (with evidence), " +
                        "Industry Trends (2-3 trends), Upcoming Catalysts, Data Gaps", companyStock))
                .agent(researchAnalyst)
                .dependsOn(validationTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task filingsAnalysisTask = Task.builder()
                .description(String.format(
                        "Analyze SEC filings data for %s from the tool evidence below.\n\n" +
                        "IMPORTANT — this is a %s filer. The primary annual form is %s; the primary " +
                        "quarterly/interim form is %s. The '=== SEC FILINGS DATA (EDGAR) ===' section " +
                        "of the evidence opens with structured blocks you should use FIRST:\n" +
                        "  - '## Key Financials (XBRL)' — multi-period revenue / margin / EPS / balance sheet\n" +
                        "  - '## MD&A Highlights' — themed quotations with per-filing citations\n" +
                        "  - '## Insider Transaction Flow' — aggregated Form 4 table + net $ flow\n\n" +
                        "%s\n\n" +
                        "REQUIRED ANALYSIS (address each numbered item with concrete numbers):\n" +
                        "1. Filing Inventory — table with columns: form type, filing date, accession " +
                        "number, URL. At minimum: most recent %s, most recent %s, last 2 8-Ks or 6-Ks, " +
                        "most recent DEF 14A (if present), most recent Form 4 (if present).\n" +
                        "2. Revenue & Income Trends — quote the XBRL-cited annual revenue figures for " +
                        "the last 3-5 fiscal years, including YoY growth %%. Include the [XBRL: …] " +
                        "citation tag for each figure. Then the same for net income.\n" +
                        "3. Margin Trajectory — gross margin %%, operating margin %%, net margin %% " +
                        "for the last 3 annual periods (from the XBRL 'Profitability & Margins' table).\n" +
                        "4. Management Discussion — quote 2-3 themed bullets VERBATIM from the '## MD&A " +
                        "Highlights' section (one from Risks, one from Liquidity or Guidance, one from " +
                        "Opportunities). Preserve each bullet's [%s <date>] or [%s <date>] citation.\n" +
                        "5. Insider Transactions — cite the net $ flow figure, acquired / disposed totals, " +
                        "and transaction count directly from the '## Insider Transaction Flow' section. " +
                        "Do NOT recompute from raw Form 4 bodies if the aggregated section is present.\n" +
                        "6. Material Changes — quarter-over-quarter or year-over-year changes in financial " +
                        "metrics (using the XBRL series). Any significant 8-K / 6-K disclosures.\n\n" +
                        "TOOL EVIDENCE:\n%s",
                        companyStock,
                        issuerProfile.isForeign() ? "FOREIGN PRIVATE ISSUER" : "DOMESTIC",
                        issuerProfile.primaryAnnualForm(), issuerProfile.primaryQuarterlyForm(),
                        citationRule,
                        issuerProfile.primaryAnnualForm(), issuerProfile.primaryQuarterlyForm(),
                        issuerProfile.primaryAnnualForm(), issuerProfile.primaryQuarterlyForm(),
                        toolEvidence))
                .expectedOutput("Markdown report with sections:\n" +
                        "1. Filing Inventory (table: form / date / accession / URL — at least 6 rows)\n" +
                        "2. Revenue & Income Trends — for EACH of the last 3-5 fiscal years: revenue $ with " +
                        "YoY %, net income $ with YoY %, and the [XBRL: Concept, Period, Form] citation tag.\n" +
                        "3. Margin Trajectory (table: period / gross / operating / net margin %)\n" +
                        "4. MD&A Highlights — 2-3 quotations with [10-K/10-Q filing-date] prefixes\n" +
                        "5. Insider Transactions — per-filing summary + total net insider $ flow\n" +
                        "6. Material Changes — QoQ/YoY financial deltas and 8-K highlights\n" +
                        "7. Data Gaps — ONLY list items not present in XBRL section OR filing bodies " +
                        "(state which source was checked for each gap).")
                .agent(financialAnalyst)
                .dependsOn(validationTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        // Slim evidence for the verifier — only the high-signal structured sections.
        // Keeps the verifier's context under the 16K-token ceiling.
        String slimEvidence = evidence.extractHighSignalSections(toolEvidence);

        // Verifier/consensus task — gates the recommendation. Flags every 'DATA NOT AVAILABLE'
        // and attempts to rescue the number from the XBRL + MD&A + insider sections.
        Task verifierTask = Task.builder()
                .description(String.format(
                        "Audit the three analyst outputs for %s. Your job is to catch analysts " +
                        "taking the 'DATA NOT AVAILABLE' shortcut when the data was actually present " +
                        "in the structured sections, to catch FABRICATED citations (hallucinated XBRL " +
                        "tags), and to catch numeric contradictions between analysts.\n\n" +
                        "STEP 1 — Detect fabricated citations AND hallucinated numbers.\n" +
                        "  (a) Fabricated citations: flag patterns where a value of 'DATA NOT AVAILABLE' " +
                        "is followed by a bracketed [XBRL: ...] tag — a HALLUCINATION. Also flag any XBRL " +
                        "tag using an invented concept name (real ones: Revenues, NetIncomeLoss, " +
                        "OperatingIncomeLoss, GrossProfit, EarningsPerShareDiluted, Assets, Liabilities, " +
                        "StockholdersEquity — NOT 'NetMargin', 'RevenueYoYGrowth', 'Period1', etc.).\n" +
                        "  (b) 🎯 Hallucinated numbers: scan the tool evidence for the '## 🎯 AUTHORITATIVE " +
                        "FACT CARD' table. For each metric row in that table with a numeric value, CHECK " +
                        "that each analyst's reported value for the same metric MATCHES EXACTLY. " +
                        "If the Fact Card says Revenue (latest annual) = $147.48M but an analyst wrote " +
                        "Revenue = $500M, that is a HALLUCINATED NUMBER — flag it as " +
                        "HALLUCINATED NUMBER: <metric> fact-card=<factCardValue> analyst=<analystValue>. " +
                        "Round numbers that don't match the Fact Card are almost always hallucinations.\n" +
                        "List every fabricated-citation finding as FABRICATED CITATION, every " +
                        "hallucinated number as HALLUCINATED NUMBER.\n\n" +
                        "STEP 2 — Extract gap claims. For every analyst-reported 'DATA NOT AVAILABLE', " +
                        "'unavailable', 'could not extract', 'not extractable', or equivalent phrase in " +
                        "the three prior outputs, list the metric and which analyst reported the gap.\n\n" +
                        "STEP 3 — Rescue from evidence. For each gap, inspect the structured sections " +
                        "below ('## Key Financials (XBRL)', '## MD&A Highlights', '## Insider Transaction " +
                        "Flow'). If the data IS in one of these sections, write: " +
                        "'RESCUED: <metric> = <value> [<proper-citation>]' — then flag that the original " +
                        "analyst SHOULD have reported this. If genuinely absent from ALL three sections, " +
                        "write: 'CONFIRMED GAP: <metric> — checked XBRL (absent), checked MD&A " +
                        "Highlights (absent), checked Insider section (absent).' Be specific about which " +
                        "sub-section you checked.\n\n" +
                        "STEP 4 — Cross-check numeric consistency. Compare revenue / net income / EPS " +
                        "numbers cited by each analyst. If two analysts report different values for the " +
                        "same metric and period, flag it as INCONSISTENCY and identify the authoritative " +
                        "XBRL-cited value.\n\n" +
                        "STEP 5 — Confidence impact. Weighted rule:\n" +
                        "  - CONFIDENCE: HIGH — no fabricated citations, ≤1 confirmed gap, no inconsistencies\n" +
                        "  - CONFIDENCE: MEDIUM — ≤2 fabricated citations (all flagged for fix), 2-3 " +
                        "confirmed gaps, or 1 resolvable inconsistency\n" +
                        "  - CONFIDENCE: LOW — >2 fabricated citations, >3 confirmed gaps, or any " +
                        "unresolved inconsistency\n\n" +
                        "OUTPUT RULES:\n" +
                        "- Your report directly feeds the gap-remediation and recommendation tasks. Be precise.\n" +
                        "- Cite the proper provenance tag for every rescued figure.\n" +
                        "- Do NOT invent numbers. If not in the evidence, it's a confirmed gap.\n\n" +
                        "HIGH-SIGNAL TOOL EVIDENCE (structured sections only):\n%s",
                        companyStock, slimEvidence))
                .expectedOutput("Markdown 'Verification Report' with sections:\n" +
                        "1. Fabricated Citations Flagged (list; each item cites the offending fragment)\n" +
                        "2. Gap Inventory (table: metric / reporting analyst / rescued-or-confirmed status)\n" +
                        "3. Rescued Figures (for each: metric, value, citation, originating analyst)\n" +
                        "4. Confirmed Gaps (for each: metric, sections checked)\n" +
                        "5. Numeric Consistency Check (inconsistencies with authoritative values)\n" +
                        "6. Confidence Recommendation — MUST end with a single line: " +
                        "'CONFIDENCE: HIGH' | 'CONFIDENCE: MEDIUM' | 'CONFIDENCE: LOW' with one sentence of justification.")
                .agent(consensusVerifier)
                .dependsOn(financialAnalysisTask)
                .dependsOn(researchTask)
                .dependsOn(filingsAnalysisTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        // Gap Remediation Task — retry with a reformulated query for each CONFIRMED GAP.
        Task gapRemediationTask = Task.builder()
                .description(String.format(
                        "Remediate the CONFIRMED GAPS flagged by the verifier for %s. The verifier's " +
                        "report (available in prior task outputs) lists specific metrics the analyst " +
                        "team could not find. Your job is to retry with more targeted queries, using " +
                        "the web_search and sec_filings tools.\n\n" +
                        "%s\n\n" +
                        "PROCESS:\n" +
                        "1. Read the verifier's 'Confirmed Gaps' section. If the verifier returned " +
                        "CONFIDENCE: HIGH with zero confirmed gaps, produce a short 'Nothing to remediate' " +
                        "report and exit.\n" +
                        "2. For each confirmed gap, design ONE targeted query. Examples:\n" +
                        "   - Missing revenue: sec_filings %s:revenue quarterly trends, or " +
                        "web_search '%s annual revenue 2024 2025'\n" +
                        "   - Missing MD&A risk factor: sec_filings %s:risk factors\n" +
                        "   - Missing insider net flow: sec_filings %s:Form 4 insider transactions 2026\n" +
                        "3. Execute the reformulated query. Inspect the new result specifically for " +
                        "the missing data point.\n" +
                        "4. Emit ONE of:\n" +
                        "   - REMEDIATED: <metric> = <value> [<citation>] (from <reformulated-query>)\n" +
                        "   - STILL MISSING: <metric> — reformulated query returned no additional data. " +
                        "Confirmation: truly absent from SEC + web sources.\n\n" +
                        "CITATION RULE:\n" +
                        "- Every remediated value MUST carry a proper citation. Fabricated citations are " +
                        "strictly forbidden.\n" +
                        "- If still missing, write exactly 'STILL MISSING: <metric>' with NO bracketed tag.\n\n" +
                        "OUTPUT: A 'Remediation Report' feeding the final recommendation. Keep each entry " +
                        "to 1-2 lines for readability.",
                        companyStock, citationRule, companyStock, companyStock, companyStock, companyStock))
                .expectedOutput("Markdown 'Remediation Report' with sections:\n" +
                        "1. Gaps Targeted (number + list)\n" +
                        "2. Remediated Figures (for each: metric, value, citation, reformulated-query used)\n" +
                        "3. Still-Missing Figures (for each: metric, why truly absent)\n" +
                        "4. Impact on Confidence — note whether the verifier's original CONFIDENCE call " +
                        "should be UPGRADED (many rescues) or HELD (few rescues).")
                .agent(gapRemediator)
                .dependsOn(verifierTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(240000)
                .build();

        Task recommendationTask = Task.builder()
                .description(String.format(
                        "Synthesize ALL prior task outputs into a final investment recommendation for %s.\n\n" +
                        "Inputs from prior tasks:\n" +
                        "  - Three analyst reports (financial, research, filings)\n" +
                        "  - Verification Report (flags gaps, fabricated citations, inconsistencies, " +
                        "emits initial CONFIDENCE call)\n" +
                        "  - Remediation Report (retried queries for confirmed gaps, may have REMEDIATED " +
                        "some or marked them STILL MISSING)\n\n" +
                        "The Remediation Report is the FINAL source of truth for gap figures. Use its " +
                        "REMEDIATED values to override any 'DATA NOT AVAILABLE' in the original analyst " +
                        "outputs. Use its STILL MISSING entries to inform real data gaps.\n\n" +
                        "%s\n\n" +
                        "🎯 FACT CARD RULE (CRITICAL):\n" +
                        "The tool evidence opens with a '## 🎯 AUTHORITATIVE FACT CARD' table. Every row " +
                        "in that table is a pre-extracted hard fact. In your Financial Analysis Summary:\n" +
                        "  - For EVERY row with a numeric value, quote the value VERBATIM with its " +
                        "citation tag. Example: the fact card row 'Net Margin (TTM) | 31.04%% | [Finnhub: " +
                        "metric.netProfitMarginTTM]' → your summary MUST show 'Net Margin (TTM): 31.04%% " +
                        "[Finnhub: metric.netProfitMarginTTM]'. Writing 'DATA NOT AVAILABLE' for ANY " +
                        "fact-card row with a value will be flagged by the verifier as FABRICATED-GAP.\n" +
                        "  - For rows showing '— not reported —', state the absence cleanly (no tag).\n" +
                        "  - For metrics NOT in the fact card (peer comparisons, market commentary, etc.), " +
                        "use the rest of the evidence (analyst reports, filings).\n\n" +
                        "REQUIRED (address each numbered item):\n" +
                        "1. Reference specific findings from each prior task by section name\n" +
                        "2. Quote REMEDIATED figures from the Remediation Report directly — these are " +
                        "authoritative.\n" +
                        "3. Quote RESCUED figures from the Verification Report for items already resolved " +
                        "there (no need to re-remediate).\n" +
                        "4. State a clear recommendation: STRONG BUY / BUY / HOLD / SELL / STRONG SELL\n" +
                        "5. State a confidence level: HIGH / MEDIUM / LOW — baseline is the verifier's " +
                        "CONFIDENCE call, UPGRADED by one tier if the remediator resolved ≥2 gaps, or " +
                        "HELD otherwise. If you deviate from this formula, explain why.\n" +
                        "6. Provide a risk/reward assessment with at least 3 risks and 3 opportunities, " +
                        "each anchored to a cited figure (XBRL or MD&A bullet or remediated value).\n" +
                        "7. State a 12-month outlook or explain why one cannot be justified from available data.\n\n" +
                        "RULES:\n" +
                        "- Do NOT introduce new data not present in prior task outputs\n" +
                        "- Cross-reference findings between tasks\n" +
                        "- Do NOT use phrases like \"as a language model\" or provide generic disclaimers",
                        companyStock, citationRule))
                .expectedOutput("Markdown report with sections:\n" +
                        "1. Executive Summary (recommendation + confidence level + 1-paragraph rationale with ≥2 cited figures)\n" +
                        "2. Financial Analysis Summary — include: latest revenue + YoY %, net margin %, " +
                        "EPS diluted, operating cash flow. All cited (XBRL or remediated).\n" +
                        "3. Market & News Assessment (key findings from research, cross-referenced)\n" +
                        "4. SEC Filings Assessment (key findings from filings analysis, cross-referenced)\n" +
                        "5. Rescued & Remediated Data Points — bullet list of figures reclaimed by verifier or remediator\n" +
                        "6. Investment Recommendation (BUY/HOLD/SELL with detailed justification anchored to ≥3 cited numbers)\n" +
                        "7. Risk/Reward Matrix (3+ risks, 3+ opportunities, each with a quantitative anchor)\n" +
                        "8. Data Gaps & Confidence Impact — restate truly-missing gaps after remediation " +
                        "and explain how they map to the final confidence rating.")
                .agent(investmentAdvisor)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/stock_analysis_report.md")
                .maxExecutionTime(240000)
                .dependsOn(gapRemediationTask)
                .build();

        // Create Swarm with Parallel Process
        // Layer 0: validation (sequential)
        // Layer 1 (parallel): financialAnalysis + research + filingsAnalysis
        // Layer 2: verifier (depends on all 3 analysts)
        // Layer 3: gapRemediation (depends on verifier — retry loop for CONFIRMED GAPs)
        // Layer 4: recommendation (depends on remediator — uses final rescued/remediated data)
        Swarm stockAnalysisSwarm = Swarm.builder()
                .id("stock-analysis-swarm")
                .agent(dataValidator)
                .agent(financialAnalyst)
                .agent(researchAnalyst)
                .agent(consensusVerifier)
                .agent(gapRemediator)
                .agent(investmentAdvisor)
                .task(validationTask)
                .task(financialAnalysisTask)
                .task(researchTask)
                .task(filingsAnalysisTask)
                .task(verifierTask)
                .task(gapRemediationTask)
                .task(recommendationTask)
                .process(ProcessType.PARALLEL)
                .verbose(true)
                .maxRpm(15)
                .language("en")
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .config("analysisType", "stock")
                .config("ticker", companyStock)
                // Heavy parallel research tasks may exceed default per-task limits on smaller local LLMs;
                // use a longer timeout while capping concurrent model calls to reduce timeout thrash.
                .config("perTaskTimeoutSeconds", 900)
                .config("maxConcurrentLlmCalls", 2)
                .config("outputFormat", "investment-report")
                .build();

        // Execute Workflow
        logger.info("Executing Stock Analysis Workflow for {}", companyStock);
        logger.info("Team: 3 Specialized Financial Agents");
        logger.info("Process: PARALLEL (3 independent streams + 1 synthesis)");
        logger.info("Tools: {}/{} operational (with routing metadata)", healthyTools.size(), allTools.size());
        logger.info("Dynamic Context: {} token window", "128K");

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("company_stock", companyStock);
        inputs.put("analysisScope", "Comprehensive financial and market analysis");
        inputs.put("timeframe", "Current market state with forward-looking insights");
        inputs.put("investmentObjective", "Investment decision support");

        // Initialize decision tracing if enabled
        String correlationId = java.util.UUID.randomUUID().toString();
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            decisionTracer.startTrace(correlationId, "stock-analysis-swarm");
            logger.info("🔍 Decision tracing enabled - Correlation ID: {}", correlationId);
        }

        long startTime = System.currentTimeMillis();
        SwarmOutput result = stockAnalysisSwarm.kickoff(inputs);
        long endTime = System.currentTimeMillis();

        // Deterministic post-processing: append the Fact Card verbatim to the saved
        // report under a ✅ Canonical Metrics section. Even if the LLM took a DATA NOT
        // AVAILABLE shortcut for metrics that ARE in evidence, the reader sees the
        // real, pre-extracted values here.
        String canonicalMetrics = "";
        try {
            java.nio.file.Path reportPath = java.nio.file.Paths.get("output/stock_analysis_report.md");
            if (java.nio.file.Files.exists(reportPath)) {
                String existing = java.nio.file.Files.readString(reportPath);
                String augmented = evidence.appendCanonicalMetrics(existing, companyStock);
                java.nio.file.Files.writeString(reportPath, augmented);
                canonicalMetrics = augmented.substring(existing.length());
                logger.info("Augmented stock_analysis_report.md with Canonical Metrics section");
            }
        } catch (Exception e) {
            logger.warn("Could not augment stock_analysis_report.md: {}", e.getMessage());
        }

        // Complete decision tracing
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            decisionTracer.completeTrace(correlationId);
        }

        // Display Results
        double durationMinutes = (endTime - startTime) / 60000.0;
        logger.info("\n" + "=".repeat(80));
        logger.info("✅ STOCK ANALYSIS WORKFLOW COMPLETED");
        logger.info("=".repeat(80));
        logger.info("📊 Stock Analyzed: {}", companyStock);
        logger.info("Duration: {} seconds", (endTime - startTime) / 1000);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("\n{}", result.getTokenUsageSummary(workflowModel));
        logger.info("📈 Final Investment Recommendation:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            // Give the judge the FULL augmented output (LLM narrative + Canonical Metrics).
            String outputForJudge = result.getFinalOutput() + canonicalMetrics;
            judge.evaluate("stock-analysis",
                "Multi-agent financial stock analysis: parallel analysts (financial/research/filings) + " +
                        "XBRL-enriched SEC tool (us-gaap + ifrs-full) + structured MD&A extraction + " +
                        "Form 4 insider aggregation + consensus/verifier gate (detects fabricated citations) + " +
                        "gap-remediation retry loop with tool access + domestic/foreign-issuer adaptive prompts + " +
                        "deterministic Canonical Metrics post-processing (pre-extracted Fact Card appended " +
                        "to final output — bypasses LLM laziness for authoritative numeric values)",
                outputForJudge,
                result.isSuccessful(), endTime - startTime,
                6, 7, "PARALLEL", "stock-market-analysis");
        }

        metrics.stop();
        metrics.report();

        // Display observability summary
        displayObservabilitySummary(correlationId);
    }

    /**
     * Log routing metadata for each tool: category, trigger/avoid conditions, and tags.
     */
    private void logToolRoutingMetadata(List<BaseTool> tools) {
        logger.info("Tool Routing Metadata ({} tools):", tools.size());
        Map<String, List<BaseTool>> byCategory = tools.stream()
            .collect(Collectors.groupingBy(BaseTool::getCategory));

        for (Map.Entry<String, List<BaseTool>> entry : byCategory.entrySet()) {
            logger.info("  [{}]", entry.getKey().toUpperCase());
            for (BaseTool tool : entry.getValue()) {
                StringBuilder meta = new StringBuilder();
                meta.append("    ").append(tool.getFunctionName())
                    .append(": ").append(tool.getDescription());
                if (tool.getTriggerWhen() != null) {
                    meta.append(" | USE WHEN: ").append(tool.getTriggerWhen());
                }
                if (tool.getAvoidWhen() != null) {
                    meta.append(" | AVOID WHEN: ").append(tool.getAvoidWhen());
                }
                if (!tool.getTags().isEmpty()) {
                    meta.append(" | Tags: ").append(String.join(", ", tool.getTags()));
                }
                logger.info("{}", meta);
            }
        }
    }

    private void logEvidenceWarnings(String toolEvidence, String companyStock) {
        if (toolEvidence == null || toolEvidence.isEmpty()) {
            logger.warn("Tool evidence is empty for {}", companyStock);
            return;
        }

        String evidenceLower = toolEvidence.toLowerCase();
        if (evidenceLower.contains("configure") || evidenceLower.contains("api key")) {
            logger.warn("Tool evidence indicates missing API configuration for {}", companyStock);
        }
        if (evidenceLower.contains("error")) {
            logger.warn("Tool evidence contains errors for {}", companyStock);
        }
    }

    /**
     * Displays observability summary including event timeline and decision trace.
     */
    private void displayObservabilitySummary(String correlationId) {
        logger.info("\n" + "=".repeat(80));
        logger.info("📊 OBSERVABILITY SUMMARY");
        logger.info("=".repeat(80));

        // Display workflow recording if available
        if (eventStore != null) {
            Optional<WorkflowRecording> recordingOpt = eventStore.createRecording(correlationId);
            if (recordingOpt.isPresent()) {
                WorkflowRecording recording = recordingOpt.get();
                WorkflowRecording.WorkflowSummary summary = recording.getSummary();

                logger.info("📋 Workflow Recording:");
                logger.info("   Correlation ID: {}", recording.getCorrelationId());
                logger.info("   Status: {}", recording.getStatus());
                logger.info("   Duration: {} ms", recording.getDurationMs());
                logger.info("   Total Events: {}", summary.getTotalEvents());
                logger.info("   Unique Agents: {}", summary.getUniqueAgents());
                logger.info("   Unique Tasks: {}", summary.getUniqueTasks());
                logger.info("   Unique Tools: {}", summary.getUniqueTools());
                logger.info("   Error Count: {}", summary.getErrorCount());

                // Display event timeline
                logger.info("\n📅 Event Timeline:");
                for (WorkflowRecording.EventRecord event : recording.getTimeline()) {
                    logger.info("   [{} ms] {} - {} (agent: {}, task: {}, tool: {})",
                            event.getElapsedMs() != null ? event.getElapsedMs() : 0,
                            event.getEventType(),
                            truncate(event.getMessage(), 50),
                            event.getAgentId() != null ? truncate(event.getAgentId(), 20) : "-",
                            event.getTaskId() != null ? truncate(event.getTaskId(), 20) : "-",
                            event.getToolName() != null ? event.getToolName() : "-");
                }
            } else {
                logger.info("   No workflow recording available");
            }
        }

        // Display decision trace if available
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            Optional<DecisionTree> treeOpt = decisionTracer.getDecisionTree(correlationId);
            if (treeOpt.isPresent()) {
                DecisionTree tree = treeOpt.get();
                logger.info("\n🧠 Decision Trace:");
                logger.info("   Total Decisions: {}", tree.getNodeCount());
                logger.info("   Unique Agents: {}", tree.getUniqueAgentIds().size());
                logger.info("   Unique Tasks: {}", tree.getUniqueTaskIds().size());

                // Display workflow explanation
                String explanation = decisionTracer.explainWorkflow(correlationId);
                logger.info("\n📝 Workflow Explanation:\n{}", explanation);
            } else {
                logger.info("   No decision trace available (enable decision-tracing-enabled in config)");
            }
        } else {
            logger.info("   Decision tracing not enabled");
        }

        logger.info("=".repeat(80));
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"stock-analysis"});
    }

}
