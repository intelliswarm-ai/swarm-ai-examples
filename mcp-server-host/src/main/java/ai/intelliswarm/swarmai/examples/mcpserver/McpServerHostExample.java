package ai.intelliswarm.swarmai.examples.mcpserver;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.mcp.McpServerHost;
import ai.intelliswarm.swarmai.tool.mcp.McpToolAdapter;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Showcases the MCP server hosting feature added in framework 1.0.14.
 *
 * <p>Two run modes:
 * <ul>
 *   <li><b>Catalog (default)</b>: prints what would be published as MCP tools
 *       (BaseTools + Agents) and a copy-paste config snippet for Claude Desktop.
 *       The MCP server itself is NOT started — safe to run in any terminal.</li>
 *   <li><b>Serve ({@code --serve})</b>: launches the actual stdio MCP server
 *       and blocks until the JVM is killed. Use this when wired up to Claude
 *       Desktop / Cursor / Cline. <strong>All logging must go to stderr</strong>,
 *       which {@code mcp-server-host/run.sh} configures via the bundled
 *       logback config.</li>
 * </ul>
 *
 * <p>Two granularities of exposure shown:
 * <ol>
 *   <li>{@link CurrentTimeTool} — a single {@link BaseTool} bean.</li>
 *   <li>{@code docsAssistant} from {@link DocsAssistantConfig} — a whole
 *       {@link Agent} (persona + tools + LLM) wrapped as one MCP tool.</li>
 * </ol>
 */
@Component
public class McpServerHostExample {

    private static final Logger logger = LoggerFactory.getLogger(McpServerHostExample.class);

    private final List<BaseTool> tools;
    private final List<Agent> agents;
    /** Optional — only present when swarmai.mcp.server.enabled=true (i.e. --serve mode). */
    private final ObjectProvider<McpServerHost> hostProvider;

    public McpServerHostExample(List<BaseTool> tools,
                                List<Agent> agents,
                                ObjectProvider<McpServerHost> hostProvider) {
        this.tools = tools != null ? tools : List.of();
        this.agents = agents != null ? agents : List.of();
        this.hostProvider = hostProvider;
    }

    public void run(String... args) throws Exception {
        Mode mode = Mode.CATALOG;
        for (String a : args != null ? args : new String[0]) {
            if ("--serve".equalsIgnoreCase(a) || "serve".equalsIgnoreCase(a)) mode = Mode.SERVE;
            else if ("--client-demo".equalsIgnoreCase(a) || "client-demo".equalsIgnoreCase(a)) mode = Mode.CLIENT_LOOPBACK;
        }
        switch (mode) {
            case SERVE            -> runServeMode();
            case CLIENT_LOOPBACK  -> { runCatalogMode(); runClientLoopback(); }
            case CATALOG          -> runCatalogMode();
        }
    }

    private enum Mode { CATALOG, SERVE, CLIENT_LOOPBACK }

    // ------------------------------------------------------------------ catalog

    private void runCatalogMode() {
        // Catalog mode actually starts the MCP host (gated by the example's run.sh setting
        // swarmai.mcp.server.enabled=true) and INVOKES through the published spec. That way
        // a passing example proves the framework's BaseTool↔Tool and Agent↔Tool plumbing is
        // healthy — not just that the catalog text formats correctly.
        McpServerHost host = hostProvider.getIfAvailable();
        if (host == null) {
            System.out.println("\nERROR: McpServerHost bean is not available — the example's "
                    + "run.sh should have set swarmai.mcp.server.enabled=true");
            return;
        }

        line();
        System.out.println("  SwarmAI MCP Server — live catalog (read directly from the running host)");
        line();

        // ------------------------------------------------------------------ exposure summary
        System.out.println("\nIn-process beans that would be exposed:");
        System.out.println("  BaseTools: " + tools.size());
        for (BaseTool t : tools) {
            System.out.printf("    • %-20s — %s%n",
                    t.getFunctionName(), truncate(t.getDescription(), 90));
        }
        System.out.println("  Agents:   " + agents.size());
        for (Agent a : agents) {
            System.out.printf("    • %-20s   role=\"%s\"%n",
                    McpServerHost.agentMcpName(a), a.getRole());
        }

        // ------------------------------------------------------------------ live MCP catalog
        List<McpSchema.Tool> published = host.listExposedTools();
        System.out.println("\nMCP tools advertised by the live server: " + published.size());
        for (McpSchema.Tool t : published) {
            System.out.printf("    • %-25s %s%n", t.name(), truncate(t.description(), 80));
        }

        // ------------------------------------------------------------------ round-trip BaseTool calls
        // Calls go through exactly the same SyncToolSpecification.call() handler the stdio
        // transport uses — so a successful round-trip here proves the framework's MCP plumbing.
        System.out.println("\nBaseTool round-trips via McpServerHost.callTool:");
        McpSchema.CallToolResult utc = host.callTool("current_time", Map.of());
        printResult("  current_time {}", utc);
        McpSchema.CallToolResult athens = host.callTool("current_time", Map.of("zone", "Europe/Athens"));
        printResult("  current_time {zone:Europe/Athens}", athens);
        McpSchema.CallToolResult bad = host.callTool("current_time", Map.of("zone", "Not/A/Zone"));
        printResult("  current_time {zone:Not/A/Zone}", bad);
        McpSchema.CallToolResult notFound = host.callTool("totally_made_up", Map.of());
        printResult("  totally_made_up {}             (expect isError=true)", notFound);

        // ------------------------------------------------------------------ Agent round-trip (LLM!)
        // The docs_assistant agent has the current_time tool in its toolbox. We give it a
        // time-grounded prompt; if the framework's Agent-as-MCP-tool plumbing is healthy,
        // the agent should:
        //   1. notice the question is time-sensitive (LLM training data has no "now")
        //   2. call current_time itself (recursion through the agent's tool layer)
        //   3. answer with the actual time, not a hallucinated one
        // Watch the log: a TOOL_STARTED: current_time event between AGENT_STARTED and
        // AGENT_COMPLETED is proof the agent reasoned its way to the tool by itself.
        System.out.println("\nAgent round-trip via McpServerHost.callTool (LLM-driven):");
        System.out.println("  Task: \"What time is it in Athens right now? Answer in one short sentence.\"");
        System.out.println("  Watch the logs — you should see TOOL_STARTED: current_time fire");
        System.out.println("  between AGENT_STARTED and AGENT_COMPLETED. That is the agent");
        System.out.println("  picking the tool on its own (the LLM doesn't know the real time).");
        System.out.println();
        long t0 = System.currentTimeMillis();
        McpSchema.CallToolResult agentResp = host.callTool("docs_assistant", Map.of(
                "task", "What time is it in Athens right now? Answer in one short sentence."));
        long elapsedMs = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.printf("  docs_assistant returned in %dms  (isError=%s)%n",
                elapsedMs, agentResp.isError());
        System.out.println("  ┌─────────── agent response ───────────");
        String text = agentResp.content() == null || agentResp.content().isEmpty()
                ? "(no content)"
                : ((McpSchema.TextContent) agentResp.content().get(0)).text();
        for (String l : text.split("\n", -1)) System.out.println("  │ " + l);
        System.out.println("  └──────────────────────────────────────");

        // ------------------------------------------------------------------ wiring snippet
        line();
        System.out.println("To actually serve these to Claude Desktop / Cursor / Cline,");
        System.out.println("drop this into the MCP client's config");
        System.out.println("(Claude Desktop on macOS: ~/Library/Application Support/Claude/claude_desktop_config.json):");
        System.out.println();
        System.out.println("  {");
        System.out.println("    \"mcpServers\": {");
        System.out.println("      \"swarmai-example\": {");
        System.out.println("        \"command\": \"bash\",");
        System.out.println("        \"args\": [");
        System.out.println("          \"/absolute/path/to/swarm-ai-examples/mcp-server-host/run.sh\",");
        System.out.println("          \"--serve\"");
        System.out.println("        ]");
        System.out.println("      }");
        System.out.println("    }");
        System.out.println("  }");
        line();
    }

    private static void printResult(String label, McpSchema.CallToolResult r) {
        String body = r.content() == null || r.content().isEmpty()
                ? "(no content)"
                : ((McpSchema.TextContent) r.content().get(0)).text();
        System.out.printf("    %-55s isError=%s  body=%s%n",
                label, r.isError(), truncate(body, 60));
    }

    private static void line() { System.out.println("=".repeat(80)); }

    // ------------------------------------------------------------------ client loopback
    //
    // Spawns THIS example as a child JVM in --serve mode (real stdio MCP server)
    // and connects the framework's existing McpToolAdapter (the MCP CLIENT side)
    // to it over real stdio. This is the full external-client experience —
    // exactly the path Claude Desktop / Cursor / Cline take, just with a JVM
    // we control on each end so you can read both sides of the wire.
    //
    // Two boundaries get exercised that the in-process catalog mode cannot:
    //   1. JSON-RPC framing over stdio pipes
    //   2. Process lifecycle (child start, initialize handshake, graceful close)

    private void runClientLoopback() {
        System.out.println();
        line();
        System.out.println("  Client loopback — spawn child JVM in --serve mode, connect via real MCP stdio");
        line();

        String javaBin = locateJavaBinary();
        String[] childCmd = buildChildCommand(javaBin);
        if (childCmd == null) {
            System.out.println("  Skipping: couldn't determine how to relaunch the example. "
                    + "(empty java.class.path?)");
            return;
        }
        System.out.println("  Spawning: " + javaBin + " " + String.join(" ", childCmd).substring(javaBin.length()));
        System.out.println("  (the child redirects logs to stderr so its stdout stays a clean MCP wire)");
        System.out.println();

        // Pull command + args out of childCmd[0..n] for McpToolAdapter.fromServer signature.
        String[] adapterArgs = new String[childCmd.length - 1];
        System.arraycopy(childCmd, 1, adapterArgs, 0, adapterArgs.length);
        List<BaseTool> remote = McpToolAdapter.fromServer(childCmd[0], adapterArgs);

        try {
            if (remote.isEmpty()) {
                System.out.println("  ⚠ Remote returned 0 tools. The child likely failed to start.");
                System.out.println("    Check stderr above for the child JVM's logs.");
                return;
            }

            System.out.println("  Discovered " + remote.size() + " tools via the MCP wire:");
            int shown = 0;
            for (BaseTool t : remote) {
                if (shown++ >= 6) break;
                System.out.printf("    • %-25s %s%n",
                        t.getFunctionName(),
                        t.getDescription() != null && t.getDescription().length() > 70
                                ? t.getDescription().substring(0, 67) + "…"
                                : t.getDescription());
            }
            if (remote.size() > 6) {
                System.out.println("    … and " + (remote.size() - 6) + " more");
            }

            // Call current_time through the wire — proves args + result round-trip the JSON-RPC pipe.
            System.out.println();
            System.out.println("  Calling current_time over the wire (zone: Europe/Athens):");
            BaseTool remoteCurrentTime = remote.stream()
                    .filter(t -> "current_time".equals(t.getFunctionName()))
                    .findFirst().orElse(null);
            if (remoteCurrentTime != null) {
                Object result = remoteCurrentTime.execute(Map.of("zone", "Europe/Athens"));
                System.out.println("    → " + result);
            } else {
                System.out.println("    (current_time not found in remote tools list)");
            }

            // Call docs_assistant — proves the child's Agent→LLM→Tool inner loop runs as a child
            // of THIS process's MCP client. Skipped if no LLM credentials are configured.
            BaseTool remoteAgent = remote.stream()
                    .filter(t -> "docs_assistant".equals(t.getFunctionName()))
                    .findFirst().orElse(null);
            if (remoteAgent != null && hasLlmCredentials()) {
                System.out.println();
                System.out.println("  Calling docs_assistant agent over the wire (LLM round-trip happens INSIDE the child):");
                long t0 = System.currentTimeMillis();
                Object result = remoteAgent.execute(Map.of(
                        "task", "What time is it in Athens? Reply in one short sentence."));
                long elapsed = System.currentTimeMillis() - t0;
                System.out.printf("    → (took %dms) %s%n", elapsed, result);
            } else if (remoteAgent != null) {
                System.out.println();
                System.out.println("  Skipping docs_assistant agent call: no LLM creds detected ");
                System.out.println("  (set OPENAI_API_KEY + SPRING_PROFILES_ACTIVE=openai-mini to exercise it).");
            }
        } finally {
            McpToolAdapter.closeAll(remote);
            line();
        }
    }

    private static boolean hasLlmCredentials() {
        String openai = System.getenv("OPENAI_API_KEY");
        String anthropic = System.getenv("ANTHROPIC_API_KEY");
        return (openai != null && !openai.isBlank()) || (anthropic != null && !anthropic.isBlank());
    }

    private static String locateJavaBinary() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return home + (windows ? "/bin/java.exe" : "/bin/java");
    }

    /**
     * Builds the child-JVM command line, picking the right launch shape for the
     * environment the parent is running in:
     * <ul>
     *   <li><b>IDE / exploded classes</b>: {@code java.class.path} has many entries
     *       (target/classes + every dependency jar). We can launch with
     *       {@code java -cp <classpath> <MainClass>} and the dependencies are visible.</li>
     *   <li><b>Fat jar (Spring Boot uber-jar)</b>: {@code java.class.path} is just the
     *       single launcher jar — {@code BOOT-INF/lib/*} is not on the actual class
     *       loader's path. We must launch with {@code java -jar <fatjar>} so Spring
     *       Boot's {@code JarLauncher} unpacks the libs. The fat jar's main is the
     *       framework's workflow runner, which dispatches by first arg, so we pass
     *       {@code mcp-server --serve}.</li>
     * </ul>
     */
    private static String[] buildChildCommand(String javaBin) {
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) return null;

        String sep = System.getProperty("path.separator", ":");
        String[] entries = classpath.split(java.util.regex.Pattern.quote(sep));
        boolean singleJar = entries.length == 1 && entries[0].toLowerCase().endsWith(".jar");

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaBin);

        if (singleJar) {
            // Spring Boot fat-jar — let JarLauncher handle the BOOT-INF unpacking.
            cmd.add("-jar");
            cmd.add(entries[0]);
            cmd.add("mcp-server");           // the workflow name dispatched by SwarmAIWorkflowRunner
            cmd.add("--serve");
        } else {
            // Multi-entry classpath (IDE / target/classes) — direct main-class launch works.
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(McpServerHostExample.class.getName());
            cmd.add("--serve");
        }

        // Stdout has to stay a clean MCP wire — push everything else to stderr.
        cmd.add("--logging.console-output=stderr");
        cmd.add("--spring.main.banner-mode=off");
        cmd.add("--logging.level.root=OFF");
        // The fat-jar entry point is SwarmAIExamplesApplication.main, not THIS class's main —
        // so the self-enable inside our main() doesn't fire there. Pass the flag explicitly
        // so the McpServerAutoConfiguration's @ConditionalOnProperty trips on either path.
        cmd.add("--swarmai.mcp.server.enabled=true");
        cmd.add("--swarmai.mcp.server.name=swarmai-loopback");
        // Narrow the published surface for the loopback demo so initialize() is fast and
        // the tools/list response stays small — avoids initialize-timeout flakiness on
        // slower laptops. The full 38-tool catalog is still available in the parent's
        // own catalog mode that ran just before this section.
        cmd.add("--swarmai.mcp.server.includeTools=current_time");
        cmd.add("--swarmai.mcp.server.includeAgents=docs_assistant");
        return cmd.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ serve

    private void runServeMode() throws InterruptedException {
        // CRITICAL: in serve mode, stdio is the MCP wire — anything written to System.out
        // becomes part of the protocol stream and breaks the client. The bundled
        // run.sh hands logback a config that pins output to stderr.
        McpServerHost host = hostProvider.getIfAvailable();
        if (host == null) {
            // Logging to stderr because stdout is reserved for MCP traffic in this mode.
            System.err.println("McpServerHost is not active. Did you forget "
                    + "--swarmai.mcp.server.enabled=true ?  (run.sh sets this for you.)");
            return;
        }
        logger.info("MCP server is up. Reading from stdin, writing to stdout. "
                + "Send SIGINT/SIGTERM to stop.");

        // Block forever — the StdioServerTransportProvider runs in background threads
        // reading stdin. When the parent process closes our stdin (i.e. Claude Desktop
        // shuts the server down) those threads exit and the JVM follows.
        new CountDownLatch(1).await();
    }

    // ------------------------------------------------------------------ helpers

    private static String truncate(String s, int max) {
        if (s == null) return "(no description)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static void main(String[] args) {
        // When you right-click → Run this class in your IDE, IntelliJ doesn't pass the
        // CLI flag the run.sh would have. Self-enable so the MCP autoconfig actually
        // creates the McpServerHost bean. A user-set system property still wins over
        // this default, so explicit opt-out keeps working.
        if (System.getProperty("swarmai.mcp.server.enabled") == null) {
            System.setProperty("swarmai.mcp.server.enabled", "true");
        }
        if (System.getProperty("swarmai.mcp.server.name") == null) {
            System.setProperty("swarmai.mcp.server.name", "swarmai-example");
        }
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? prepend("mcp-server", args) : new String[]{"mcp-server"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
