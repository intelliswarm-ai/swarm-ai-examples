package ai.intelliswarm.swarmai.examples.gmaildashboard;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.examples.gmail.RealChromeLauncher;
import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import ai.intelliswarm.swarmai.tool.common.BrowserToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * IntelliMail — a small web UI that reads your Gmail through the framework's
 * {@link BrowserTool}, summarises each message via a local LLM, and extracts
 * concrete action items.
 *
 * <p>Boots Spring Boot in web mode, launches your real Chrome with CDP, and
 * serves the static IntelliMail UI on http://localhost:8090.
 *
 * <p>Triage runs against whichever LLM profile is active. {@code main()} below
 * defaults to {@code ollama} (local-only, nothing leaves your machine) — flip
 * to {@code openai-mini} via {@code SPRING_PROFILES_ACTIVE} if you want
 * sharper triage at $0.0001/email.
 */
@Component
public class GmailDashboardExample {

    private static final Logger logger = LoggerFactory.getLogger(GmailDashboardExample.class);

    private final ObjectProvider<BrowserTool> browserToolProvider;
    private final ObjectProvider<BrowserToolProperties> browserPropsProvider;

    public GmailDashboardExample(ObjectProvider<BrowserTool> browserToolProvider,
                                ObjectProvider<BrowserToolProperties> browserPropsProvider) {
        this.browserToolProvider = browserToolProvider;
        this.browserPropsProvider = browserPropsProvider;
    }

    /**
     * Called by the workflow runner when the user picks {@code intellimail}.
     * Auto-launches real Chrome, wires the BrowserTool to attach to it, then
     * blocks the JVM (the embedded Tomcat keeps running) until the user kills
     * the process.
     */
    public void run(String... args) {
        // Same port + profile as gmail-browser-agent so the two examples share ONE Chrome
        // window when you run them side-by-side for a demo. RealChromeLauncher detects
        // an already-running Chrome on the port and reuses it (wasAlreadyRunning=true).
        Path profileDir = Paths.get(
                System.getProperty("swarmai.tools.browser.user-data-dir",
                        Paths.get(System.getProperty("user.home"), ".swarmai", "gmail-chrome-cdp").toString()));

        RealChromeLauncher.LaunchResult chrome;
        try {
            chrome = RealChromeLauncher.launch(profileDir, 9222);
        } catch (IllegalStateException e) {
            logger.error("IntelliMail couldn't launch real Chrome: {}", e.getMessage());
            return;
        }

        BrowserTool browser = browserToolProvider.getIfAvailable();
        BrowserToolProperties browserProps = browserPropsProvider.getIfAvailable();
        if (browser == null || browserProps == null) {
            logger.error("IntelliMail: BrowserTool not active. Set swarmai.tools.browser.enabled=true.");
            return;
        }
        // Reconfigure the SHARED props bean so the BrowserTool attaches to the Chrome we
        // just launched instead of spinning up its own Playwright Chromium.
        browserProps.setMode("attach");
        browserProps.setCdpUrl(chrome.cdpUrl());
        browser.shutdown();   // discard any cached state so the next call re-inits with attach mode

        // Pre-warm the inbox in a daemon thread — if the user hasn't signed in yet, the
        // navigate call will sit on Gmail's auth page for the full 30s timeout. We don't
        // want that blocking the UI startup banner.
        Thread prewarm = new Thread(() -> {
            try {
                browser.execute(java.util.Map.of("operation", "navigate",
                        "url", "https://mail.google.com/mail/u/0/#inbox"));
            } catch (Exception e) {
                logger.debug("IntelliMail pre-warm navigate didn't complete: {}", e.getMessage());
            }
        }, "intellimail-prewarm");
        prewarm.setDaemon(true);
        prewarm.start();

        logger.info("");
        logger.info("=".repeat(80));
        logger.info("  IntelliMail UI is up");
        logger.info("=".repeat(80));
        logger.info("  Open: http://localhost:8090/");
        logger.info("  Chrome (attached): {}", chrome.cdpUrl());
        logger.info("  Profile dir:       {}", profileDir);
        logger.info("");
        logger.info("  Make sure you're signed in to Gmail in the Chrome window that opened.");
        logger.info("  Then click 'Refresh inbox' in the IntelliMail UI.");
        logger.info("=".repeat(80));

        // Open the IntelliMail UI as a new tab in the SAME Chrome we just launched —
        // that window is already in front of the user and signed in to Gmail. Falls back
        // to the OS default browser if the CDP call fails for any reason.
        openInAttachedChrome("http://localhost:8090/", 9222);

        // Tomcat (embedded) keeps the JVM alive — we don't need to block the main thread.
    }

    /**
     * Ask the already-attached Chrome to open {@code url} in a new tab via its CDP
     * HTTP endpoint ({@code PUT /json/new?<url>}). This is the most reliable way
     * to auto-open the UI on WSL/Linux/headless setups where Java's Desktop API
     * has no graphical default browser to delegate to.
     */
    private static void openInAttachedChrome(String url, int cdpPort) {
        java.net.URI cdpEndpoint = java.net.URI.create(
                "http://localhost:" + cdpPort + "/json/new?" + url);
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
        try {
            // Newer Chrome (>=111) requires PUT; older versions accept GET. Try PUT first.
            java.net.http.HttpRequest put = java.net.http.HttpRequest.newBuilder()
                    .uri(cdpEndpoint)
                    .method("PUT", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .timeout(java.time.Duration.ofSeconds(3))
                    .build();
            java.net.http.HttpResponse<Void> resp = client.send(put,
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 == 2) {
                logger.info("IntelliMail: opened new tab in attached Chrome at {}", url);
                return;
            }
            if (resp.statusCode() == 405) {
                java.net.http.HttpRequest get = java.net.http.HttpRequest.newBuilder()
                        .uri(cdpEndpoint).GET()
                        .timeout(java.time.Duration.ofSeconds(3))
                        .build();
                java.net.http.HttpResponse<Void> resp2 = client.send(get,
                        java.net.http.HttpResponse.BodyHandlers.discarding());
                if (resp2.statusCode() / 100 == 2) {
                    logger.info("IntelliMail: opened new tab in attached Chrome at {}", url);
                    return;
                }
            }
            logger.debug("IntelliMail: CDP /json/new returned HTTP {} — falling back to default browser", resp.statusCode());
        } catch (Exception e) {
            logger.debug("IntelliMail: CDP new-tab failed ({}) — falling back to default browser", e.getMessage());
        }
        openInDefaultBrowser(url);
    }

    /**
     * Open the given URL in the user's default browser. Tries java.awt.Desktop first
     * (works on Win/Mac/most Linux DEs), then falls back to platform-specific shell
     * commands. On WSL we hand off to {@code cmd.exe /c start} so the URL opens in
     * the Windows host's default browser. Best-effort — never throws.
     */
    private static void openInDefaultBrowser(String url) {
        try {
            if (isWsl()) {
                // From WSL2: route through cmd.exe so the Windows default browser handles it.
                new ProcessBuilder("cmd.exe", "/c", "start", "", url)
                        .redirectErrorStream(true).start();
                return;
            }
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(java.net.URI.create(url));
                    return;
                }
            }
            shellOpen(url);
        } catch (Throwable t) {
            logger.debug("IntelliMail: couldn't auto-open browser: {} — open {} manually", t.getMessage(), url);
        }
    }

    private static boolean isWsl() {
        if (System.getenv("WSL_DISTRO_NAME") != null) return true;
        try {
            String v = java.nio.file.Files.readString(java.nio.file.Paths.get("/proc/version"))
                    .toLowerCase();
            return v.contains("microsoft") || v.contains("wsl");
        } catch (Exception e) {
            return false;
        }
    }

    private static void shellOpen(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            logger.debug("IntelliMail: shell-open fallback failed: {}", e.getMessage());
        }
    }

    /** Marker config so component-scan picks up the controller / services in this package. */
    @Configuration
    static class GmailDashboardConfig { }

    public static void main(String[] args) {
        // Share profile + port with gmail-browser-agent so both examples can run side-by-side
        // against the SAME Chrome window — perfect for a demo.
        Path defaultProfile = Paths.get(System.getProperty("user.home"), ".swarmai", "gmail-chrome-cdp");
        setIfAbsent("swarmai.tools.browser.enabled", "true");
        setIfAbsent("swarmai.tools.browser.user-data-dir", defaultProfile.toString());
        setIfAbsent("swarmai.tools.browser.allowed-hosts", "google.com,gmail.com");

        // IntelliMail is a web UI, not a CLI workflow — bring up an embedded server.
        setIfAbsent("spring.main.web-application-type", "servlet");
        setIfAbsent("server.port", "8090");

        // Activate the / → /intellimail/ forward so we win against the customer-support
        // example's static/index.html that also lands on the classpath.
        setIfAbsent("swarmai.intellimail.enabled", "true");

        // Local Ollama by default — privacy-by-default for an email-triage UI.
        // Switch with SPRING_PROFILES_ACTIVE=openai-mini if you'd rather use gpt-4o-mini.
        setIfAbsent("spring.profiles.active", "ollama");

        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? prepend("intellimail", args) : new String[]{"intellimail"});
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
