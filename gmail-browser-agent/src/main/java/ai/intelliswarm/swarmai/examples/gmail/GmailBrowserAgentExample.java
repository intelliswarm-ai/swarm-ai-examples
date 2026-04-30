package ai.intelliswarm.swarmai.examples.gmail;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import ai.intelliswarm.swarmai.tool.common.BrowserToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Drive the user's REAL Gmail through the framework's {@link BrowserTool}.
 *
 * <p>Two phases:
 * <ol>
 *   <li><b>Phase 1 — deterministic browser steps</b> (no LLM). Navigate, wait, scrape,
 *       count unread, screenshot. Always runs. Proves the BrowserTool wires to a real
 *       Chrome session correctly.</li>
 *   <li><b>Phase 2 — LLM-driven triage</b>. A {@code Gmail Triage Analyst} agent reads
 *       the scraped inbox text and produces a prioritized summary. Skipped if no LLM
 *       credentials are configured.</li>
 * </ol>
 *
 * <p>Connection mode is fixed to {@code attach} — Playwright's launch mode is reliably
 * blocked by Google's bot detection. By attaching to a Chrome you've already signed
 * into, we sidestep both bot-detection and 2FA.
 *
 * <p>If the Chrome debug port isn't reachable when the example starts, it prints
 * clear OS-specific setup instructions and exits cleanly — no stack trace.
 */
@Component
public class GmailBrowserAgentExample {

    private static final Logger logger = LoggerFactory.getLogger(GmailBrowserAgentExample.class);

    private final ObjectProvider<BrowserTool> browserToolProvider;
    private final ObjectProvider<BrowserToolProperties> browserPropsProvider;
    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public GmailBrowserAgentExample(ObjectProvider<BrowserTool> browserToolProvider,
                                    ObjectProvider<BrowserToolProperties> browserPropsProvider,
                                    ChatClient.Builder chatClientBuilder,
                                    ApplicationEventPublisher eventPublisher) {
        this.browserToolProvider = browserToolProvider;
        this.browserPropsProvider = browserPropsProvider;
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------------ entry

    public void run(String... args) {
        // ------------------------------------------------------------------ launch real Chrome
        // Google's signin flow flags Playwright's bundled Chromium as "not a secure browser"
        // and refuses to authenticate. Connecting to a real Chrome via CDP sidesteps that.
        // We auto-spawn a real Chrome with a dedicated --user-data-dir so it doesn't touch
        // your everyday profile — first run signs in once, subsequent runs reuse the cookies.
        Path profileDir = Paths.get(
                System.getProperty("swarmai.tools.browser.user-data-dir",
                        Paths.get(System.getProperty("user.home"), ".swarmai", "gmail-chrome-cdp").toString()));
        boolean firstRun = !profileDir.resolve("Default").toFile().exists();

        RealChromeLauncher.LaunchResult chrome;
        try {
            chrome = RealChromeLauncher.launch(profileDir, 9222);
        } catch (IllegalStateException e) {
            logger.error("Couldn't launch real Chrome: {}", e.getMessage());
            return;
        }

        BrowserTool browser = browserToolProvider.getIfAvailable();
        BrowserToolProperties browserProps = browserPropsProvider.getIfAvailable();
        if (browser == null || browserProps == null) {
            printActivationHelp();
            return;
        }

        // Reconfigure the SHARED props bean (BrowserTool reads it on first execute()).
        // System properties can't help here — Spring already finished binding @ConfigurationProperties
        // when the BrowserTool bean was constructed. Mutating the live props object is what
        // actually takes effect. Then close any cached Playwright state so the next call
        // re-runs ensurePage() with the new mode.
        browserProps.setMode("attach");
        browserProps.setCdpUrl(chrome.cdpUrl());
        browser.shutdown();

        logger.info("=".repeat(80));
        logger.info("Gmail Browser Agent — driving your real Chrome via CDP");
        logger.info("Chrome:       {} ({})", chrome.cdpUrl(),
                chrome.wasAlreadyRunning() ? "reusing already-running session" : "spawned by us");
        logger.info("Profile dir:  {}", profileDir);
        logger.info(firstRun
                ? "FIRST RUN — sign in to Gmail in the Chrome window that just opened."
                : "Profile already exists — should land directly in your inbox.");
        logger.info("=".repeat(80));

        // ------------------------------------------------------------------ Phase 1
        logger.info("\nPHASE 1 — Deterministic browser steps (no LLM)");

        report("navigate Gmail",
                browser.execute(Map.of(
                        "operation", "navigate",
                        "url", "https://mail.google.com/mail/u/0/#inbox")));

        if (firstRun) {
            // The user hasn't signed in yet — let them do it in the open Chrome window
            // BEFORE we try to wait for the inbox container. (Chrome — not Chromium —
            // means Google's signin flow proceeds without "not a secure browser" errors.)
            logger.info("");
            logger.info(">>> Sign in to Gmail in the Chrome window that just opened.");
            logger.info(">>> When you reach your inbox, return here and press ENTER.");
            waitForEnter();
        }

        report("wait for inbox container",
                browser.execute(Map.of(
                        "operation", "wait_for",
                        "selector", "[role=\"main\"]",
                        "timeout_ms", 60_000)));

        Object unreadObj = browser.execute(Map.of(
                "operation", "evaluate_js",
                "code", "document.querySelectorAll('tr.zE').length"));
        logger.info("[unread row count]  {}", unreadObj);

        Object screenshotPath = browser.execute(Map.of(
                "operation", "screenshot",
                "path", "output/gmail-inbox.png",
                "fullPage", false));
        logger.info("[screenshot]        {}", screenshotPath);

        Object inboxText = browser.execute(Map.of(
                "operation", "scrape",
                "selector", "[role=\"main\"]"));
        String inbox = String.valueOf(inboxText);
        logger.info("[inbox snippet]     {} chars", inbox.length());
        logger.info("                    {}", excerpt(inbox, 240));

        // ------------------------------------------------------------------ Phase 2
        // Triage runs against whichever LLM profile is active:
        //   • SPRING_PROFILES_ACTIVE=openai-mini + OPENAI_API_KEY  → gpt-4o-mini (cloud)
        //   • SPRING_PROFILES_ACTIVE=ollama (the default)          → local Mistral, no data leaves
        //   • no Ollama running and no API key                     → Phase 2 fails with a friendly message
        // For the privacy-paranoid: just run Ollama (`ollama run mistral`) and don't set any keys —
        // the entire pipeline stays on your machine.
        logger.info("");
        logger.info("=".repeat(80));
        logger.info("PHASE 2 — LLM-driven triage of the scraped inbox");
        logger.info("Provider: {} (set SPRING_PROFILES_ACTIVE to switch)",
                hasLlmCredentials() ? "cloud (OpenAI/Anthropic)" : "local (Ollama default)");
        logger.info("=".repeat(80));

        // Preflight: openai-mini is now the default profile, but it needs an API key.
        // Catching this here gives a single clear message instead of a Spring AI
        // 401/connection error stack three function-calls deep.
        String activeProfile = System.getProperty("spring.profiles.active",
                System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", ""));
        if (activeProfile.contains("openai") && !notBlank(System.getenv("OPENAI_API_KEY"))) {
            logger.warn("");
            logger.warn(">>> Phase 2 skipped: SPRING_PROFILES_ACTIVE='{}' requires OPENAI_API_KEY.",
                    activeProfile);
            logger.warn(">>> Either:  export OPENAI_API_KEY=sk-...");
            logger.warn(">>>     OR:  SPRING_PROFILES_ACTIVE=ollama (and have Ollama running locally)");
            return;
        }

        // Pass the full visible inbox slice. 6,000 chars covers ~50 inbox rows on Gmail's
        // current layout — enough for the triage agent to spot deadlines, sender clusters,
        // and phishing-looking subjects across the visible page.
        // Cloud LLMs (gpt-4o-mini default) handle this in 2–5 s. If you flip to local Ollama
        // and find triage too slow, drop this to 3000 — that's typically a 3× speedup on CPU.
        String inboxForLlm = truncate(inbox, 6000);

        ChatClient chatClient = chatClientBuilder.build();
        Agent triage = Agent.builder()
                .role("Gmail Triage Analyst")
                .goal("Read the user's inbox text and surface what deserves attention now. "
                        + "Be concise — bullet points, not paragraphs.")
                .backstory("You are a senior executive's assistant. You have minutes, not hours. "
                        + "You group by sender/topic, flag things that look time-sensitive (deadlines, "
                        + "meeting requests, security alerts), and explicitly call out anything that "
                        + "smells like phishing.")
                .chatClient(chatClient)
                .verbose(true)
                .temperature(0.2)
                // 5-minute LLM-call budget — generous for cold local Ollama on a CPU.
                // Cloud calls return in seconds; this only ever bites if the model genuinely
                // takes a long time. Beats the framework's 2-minute default which timed out
                // on first-run Ollama for a fresh user.
                .maxExecutionTime(300_000)
                .build();

        Task triageTask = Task.builder()
                .description("Below is the visible text of my Gmail inbox. Produce:\n"
                        + "1. **Top 5 messages** I should read first, with one-line reason each.\n"
                        + "2. **Deadlines / time-sensitive** items separately.\n"
                        + "3. **Anything suspicious or phishing-looking**.\n\n"
                        + "Don't invent senders or subjects — only use what's actually in the text.\n\n"
                        + "INBOX TEXT:\n" + inboxForLlm)
                .expectedOutput("Markdown with the three sections above")
                .agent(triage)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(triage)
                .task(triageTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .build();

        try {
            SwarmOutput result = swarm.kickoff(Map.of());
            logger.info("");
            logger.info("=".repeat(80));
            logger.info("Triage report:");
            logger.info("=".repeat(80));
            logger.info("\n{}", result.getFinalOutput());
        } catch (Exception e) {
            logger.warn("Phase 2 failed: {}", e.getMessage());
            logger.warn("");
            logger.warn("Common causes:");
            logger.warn("  • Ollama isn't running. Install it from https://ollama.com/, then:");
            logger.warn("        ollama pull mistral");
            logger.warn("        ollama serve   (usually started automatically)");
            logger.warn("  • No OPENAI_API_KEY set and SPRING_PROFILES_ACTIVE=openai-mini.");
            logger.warn("  • You're behind a corporate proxy that blocks the LLM endpoint.");
            logger.warn("");
            logger.warn("Phase 1 (the deterministic browser steps above) ran successfully.");
            logger.warn("If you only want Phase 1, this isn't an error — ignore.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void report(String label, Object value) {
        String s = value == null ? "(null)" : value.toString();
        if (s.startsWith("ERROR:")) {
            logger.error("[{}]  {}", label, s);
        } else {
            logger.info("[{}]  {}", label, excerpt(s, 100));
        }
    }

    private static void waitForEnter() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            br.readLine();
        } catch (Exception ignored) {
            // Stdin closed — fall through, we'll just retry the inbox-wait with a long timeout.
        }
    }

    private static boolean hasLlmCredentials() {
        return notBlank(System.getenv("OPENAI_API_KEY"))
                || notBlank(System.getenv("ANTHROPIC_API_KEY"));
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String excerpt(String s, int n) {
        if (s == null) return "";
        String trimmed = s.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= n ? trimmed : trimmed.substring(0, n - 1) + "…";
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "\n…[truncated]");
    }

    private void printActivationHelp() {
        logger.warn("");
        logger.warn("=".repeat(80));
        logger.warn("BrowserTool bean is not active. To enable:");
        logger.warn("=".repeat(80));
        logger.warn("  1. Add com.microsoft.playwright:playwright to your classpath (already on");
        logger.warn("     this example via swarm-ai-examples/pom.xml when you ran ./run.sh).");
        logger.warn("  2. Set swarmai.tools.browser.enabled=true in application.yml or as a");
        logger.warn("     CLI flag. The bundled gmail-browser-agent/run.sh does this for you.");
        logger.warn("=".repeat(80));
    }

    public static void main(String[] args) {
        // Self-enable the browser tool. The example's run() method auto-launches a real
        // Chrome (Google blocks Playwright's bundled Chromium) and hands the BrowserTool
        // its CDP URL. So no need to set mode/cdp-url here — run() does it once Chrome
        // is up. Just opting in to the tool itself is enough.
        Path defaultProfile = Paths.get(System.getProperty("user.home"), ".swarmai", "gmail-chrome-cdp");
        setIfAbsent("swarmai.tools.browser.enabled", "true");
        setIfAbsent("swarmai.tools.browser.user-data-dir", defaultProfile.toString());
        setIfAbsent("swarmai.tools.browser.allowed-hosts", "google.com,gmail.com");

        // Default to OpenAI's low-cost gpt-4o-mini for triage — fast, cheap, and produces
        // good enough triage reports without taking 2 minutes on Ollama for the first run.
        // Override by setting SPRING_PROFILES_ACTIVE=ollama (or anything else) before launch.
        setIfAbsent("spring.profiles.active", "openai-mini");

        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? prepend("gmail-browser", args) : new String[]{"gmail-browser"});
    }

    private static void setIfAbsent(String k, String v) {
        if (System.getProperty(k) == null) System.setProperty(k, v);
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
