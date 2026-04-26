package ai.intelliswarm.swarmai.examples.eodhd;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.common.EodhdDiscoveryTool;
import ai.intelliswarm.swarmai.tool.common.EodhdMarketDataTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * EODHD showcase — wires {@link EodhdMarketDataTool} and {@link EodhdDiscoveryTool} into a
 * two-agent global-markets pipeline.
 *
 * <p>Pipeline (SEQUENTIAL):
 * <ol>
 *   <li><b>Global Markets Scout</b> — uses {@code eodhd_discovery} to find peer tickers
 *       in the same sector and pull the next 14 days of earnings releases.</li>
 *   <li><b>Global Markets Analyst</b> — uses {@code eodhd_market_data} to pull EOD prices,
 *       fundamentals, the RSI(14) trend, and dividend history for the focal ticker, then
 *       writes a one-page brief grounded in citation-tagged figures.</li>
 * </ol>
 *
 * <p>Run: {@code ./run.sh eodhd BMW.XETRA}
 */
@Component
public class EodhdGlobalMarketsExample {

    private static final Logger logger = LoggerFactory.getLogger(EodhdGlobalMarketsExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final EodhdMarketDataTool marketDataTool;
    private final EodhdDiscoveryTool discoveryTool;

    public EodhdGlobalMarketsExample(ChatClient.Builder chatClientBuilder,
                                     ApplicationEventPublisher eventPublisher,
                                     EodhdMarketDataTool marketDataTool,
                                     EodhdDiscoveryTool discoveryTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.marketDataTool = marketDataTool;
        this.discoveryTool = discoveryTool;
    }

    public void run(String... args) {
        String symbol = args.length > 0 ? args[0].trim() : "BMW.XETRA";

        if (!marketDataTool.isHealthy()) {
            logger.error("EODHD tools unhealthy: EODHD_API_KEY is not configured.");
            logger.error("Get a key at https://eodhd.com/ (free tier: 20 requests/day) and");
            logger.error("set EODHD_API_KEY in your environment, e.g.");
            logger.error("    export EODHD_API_KEY=your-token-here");
            return;
        }

        ChatClient chatClient = chatClientBuilder.build();

        Agent scout = Agent.builder()
                .role("Global Markets Scout")
                .goal("For " + symbol + ", surface (a) the next 14 days of scheduled earnings " +
                      "releases worldwide and (b) a handful of comparable tickers found via the " +
                      "EODHD search/screener. Return a short Discovery Brief.")
                .backstory("You are a discovery specialist. You always start by calling " +
                           "eodhd_discovery — never invent tickers or earnings dates from prior " +
                           "knowledge. Every fact you report must come from a tool result.")
                .chatClient(chatClient)
                .tools(List.of(discoveryTool))
                .maxTurns(4)
                .verbose(true)
                .temperature(0.1)
                .build();

        Agent analyst = Agent.builder()
                .role("Global Markets Analyst")
                .goal("Write a one-page brief on " + symbol + " grounded in EODHD data: latest " +
                      "quote and fundamentals, the last 30 trading days of EOD prices, the RSI(14) " +
                      "indicator trend, and the most recent dividend history.")
                .backstory("You are a global markets analyst with a strong preference for citation-" +
                           "anchored facts. For every numeric claim you write, you call eodhd_market_data " +
                           "with the appropriate endpoint suffix (':eod', ':fundamentals', ':technical:rsi:14', " +
                           "':dividends'). You never paraphrase numbers — you quote them with the " +
                           "[EODHD: <endpoint>, <period>] tag the tool returned.")
                .chatClient(chatClient)
                .tools(List.of(marketDataTool, discoveryTool))
                .maxTurns(6)
                .verbose(true)
                .temperature(0.1)
                .build();

        Task scoutTask = Task.builder()
                .description("Use eodhd_discovery to gather two pieces of context for " + symbol + ":\n" +
                             "1. Call 'earnings' (today → +14 days) and list up to 5 high-profile releases. " +
                             "If the call returns 'EODHD Discovery Unavailable' or an empty table, write " +
                             "'_No earnings calendar available on the current EODHD plan._' and proceed. " +
                             "Do NOT invent earnings dates.\n" +
                             "2. Call 'search:" + simpleQueryFor(symbol) + "' to find related tickers, then\n" +
                             "   FILTER the results: keep ONLY rows whose Type is 'Common Stock' AND whose " +
                             "Code is NOT identical to the focal symbol's primary ticker (i.e. exclude the " +
                             "input itself), and DROP any leveraged/inverse ETFs (names containing 'Bull', " +
                             "'Bear', 'Daily', '1.5X', '2X', '3X'). Keep up to 5 distinct issuers. The goal " +
                             "is cross-listings (foreign ADRs, dual listings) and *real* sector peers, not " +
                             "leveraged derivatives.\n" +
                             "Produce a 'Discovery Brief' with two sections: Upcoming Earnings (table) and " +
                             "Cross-Listings & Peer Universe (table). Tag every row with the [EODHD: ...] " +
                             "citation the tool emitted.")
                .expectedOutput("Markdown 'Discovery Brief' with Upcoming Earnings and Cross-Listings & " +
                                "Peer Universe tables. Empty sections must be marked as unavailable rather " +
                                "than hallucinated. Each row carries [EODHD: ...] citation.")
                .agent(scout)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Task analystTask = Task.builder()
                .description("Build a one-page Markets Brief for " + symbol + " grounded in EODHD data. " +
                             "Make every tool call below; for each section, treat 'EODHD: not configured' " +
                             "or empty results as a real signal — see OUTPUT RULES.\n\n" +
                             "DATA COLLECTION (call all four):\n" +
                             "1. eodhd_market_data input '" + symbol + ":fundamentals' — extract market " +
                             "cap, P/E, sector, industry.\n" +
                             "2. eodhd_market_data input '" + symbol + ":eod' — last 30 trading sessions.\n" +
                             "3. eodhd_market_data input '" + symbol + ":technical:rsi:14' — most recent RSI.\n" +
                             "4. eodhd_market_data input '" + symbol + ":dividends' — last 4 distributions.\n\n" +
                             "DERIVED METRICS (you MUST compute these from the tool data):\n" +
                             "- 30-day % change between earliest and latest close.\n" +
                             "- Trailing 12-month dividend total = sum of last 4 quarterly payments.\n" +
                             "- Indicated dividend yield = (TTM dividend / latest close) × 100, rounded " +
                             "to 2 decimal places. Show the arithmetic in one line " +
                             "(e.g. '0.26 × 4 = 1.04 / 271.06 = 0.38%').\n" +
                             "- RSI classification: >70 overbought, <30 oversold, otherwise neutral.\n\n" +
                             "OUTPUT RULES (these are quality-graded — follow them strictly):\n" +
                             "(a) Round prices to 2 decimal places in narrative ($271.06, NOT $271.0600). " +
                             "Round dividend values to 2 decimals ($0.26, NOT $0.2600). Tables can keep " +
                             "the formatter's source precision unchanged — only the narrative gets rounded.\n" +
                             "(b) Anchor every relative date to the actual ISO date from the citation. " +
                             "Write 'closing price on 2026-04-24' instead of 'latest close'. Write " +
                             "'window 2026-03-13 → 2026-04-24' instead of 'last 30 days'.\n" +
                             "(c) If a section's data came back empty (fundamentals/RSI not on the plan), " +
                             "write a single one-line note like '_Fundamentals not available on the " +
                             "current EODHD plan._' and OMIT the bullet list of DATA NOT AVAILABLE entries. " +
                             "Do NOT pad with empty bullets.\n" +
                             "(d) For peers: pull 2-3 names from the Discovery Brief's filtered table. " +
                             "Mention them by ticker AND name in narrative form (e.g. 'cross-listed in " +
                             "Brazil as AAPL34'), not as a copy-paste of the table.\n" +
                             "(e) Every numeric figure must carry the [EODHD: ...] citation that the tool " +
                             "emitted. Computed metrics like the yield carry [computed from " +
                             "EODHD: div + EODHD: eod].")
                .expectedOutput("Markdown 'Global Markets Brief' for " + symbol + " with sections: " +
                                "Snapshot (or one-line unavailable note), Recent Price Action with " +
                                "anchored dates, Technical (RSI) with classification or unavailable " +
                                "note, Dividend History table + indicated yield with arithmetic shown, " +
                                "Peers as 2-3 narrative sentences. Prices rounded to 2 decimals in " +
                                "narrative. Empty sections collapsed. Every figure cited.")
                .agent(analyst)
                .dependsOn(scoutTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/eodhd_global_markets_" + sanitize(symbol) + ".md")
                .build();

        Swarm swarm = Swarm.builder()
                .agent(scout)
                .agent(analyst)
                .task(scoutTask)
                .task(analystTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .build();

        SwarmOutput result = swarm.kickoff(Map.of("symbol", symbol));

        logger.info("");
        logger.info("=== EODHD Global Markets showcase result for {} ===", symbol);
        logger.info("{}", result.getFinalOutput());
    }

    /** Strip the exchange suffix to get a short query string for EODHD search. */
    private static String simpleQueryFor(String symbol) {
        int dot = symbol.indexOf('.');
        return dot > 0 ? symbol.substring(0, dot) : symbol;
    }

    private static String sanitize(String symbol) {
        return symbol.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? prepend("eodhd", args) : new String[]{"eodhd"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
