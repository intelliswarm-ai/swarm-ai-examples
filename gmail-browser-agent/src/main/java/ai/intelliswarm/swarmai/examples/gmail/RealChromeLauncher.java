package ai.intelliswarm.swarmai.examples.gmail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * Launches the user's REAL Chrome installation in a separate process with the CDP
 * debug port open, then waits for that port to become reachable.
 *
 * <p>Why we need this: Google's signin flow flags Playwright's bundled Chromium
 * as "not a secure browser" and refuses to authenticate. Connecting to a real
 * Chrome via CDP sidesteps that — Chrome IS a Chrome, no fingerprint mismatch.
 *
 * <p>Spawned Chrome runs with a dedicated {@code --user-data-dir} so it doesn't
 * collide with the user's everyday browsing profile. Cookies persist between
 * runs, so signin happens at most once.
 */
public class RealChromeLauncher {

    private static final Logger logger = LoggerFactory.getLogger(RealChromeLauncher.class);

    /**
     * Result of a successful launch.
     *
     * @param cdpUrl    e.g. {@code http://localhost:9222} — pass to BrowserTool's CDP attach mode
     * @param wasAlreadyRunning  true if Chrome was already listening on the port; we didn't spawn anything
     * @param process   the launched Process, or null if Chrome was already running
     */
    public record LaunchResult(String cdpUrl, boolean wasAlreadyRunning, Process process) {}

    /**
     * @param userDataDir  profile dir, e.g. {@code ~/.swarmai/gmail-chrome-cdp}
     * @param port         CDP port, default 9222
     * @return a {@link LaunchResult} on success
     * @throws IllegalStateException if Chrome can't be found or doesn't open the port within the timeout
     */
    public static LaunchResult launch(Path userDataDir, int port) {
        String cdpUrl = "http://localhost:" + port;

        // 1. If a Chrome with the right port is ALREADY running (e.g. from a previous run
        // of this example, or a manual launch), reuse it. Saves a Chrome process.
        if (cdpReachable(cdpUrl)) {
            logger.info("Found Chrome already listening on {}", cdpUrl);
            return new LaunchResult(cdpUrl, true, null);
        }

        // 2. Locate the real Chrome (or Chromium-family) executable.
        String chromePath = findChromeExecutable()
                .orElseThrow(() -> new IllegalStateException(
                        "Couldn't find Chrome / Chromium / Edge / Brave on this machine.\n"
                                + "Searched: standard install paths per OS, your PATH, and registry where applicable.\n"
                                + "Fixes:\n"
                                + "  • install Google Chrome from https://www.google.com/chrome/, OR\n"
                                + "  • set swarmai.tools.browser.chrome-path=/full/path/to/chrome.exe (or msedge.exe)\n"
                                + "    e.g. -Dswarmai.tools.browser.chrome-path=\"C:\\\\…\\\\chrome.exe\"\n"
                                + "  • ensure the binary is executable / present at one of the well-known paths"));

        // 3. Make sure the user-data-dir exists.
        try { Files.createDirectories(userDataDir); } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot create user-data-dir " + userDataDir + ": " + e.getMessage(), e);
        }

        // 4. Spawn it.
        ProcessBuilder pb = new ProcessBuilder(
                chromePath,
                "--remote-debugging-port=" + port,
                "--user-data-dir=" + userDataDir.toAbsolutePath(),
                // No first-run wizard, no default-browser nag — we want a clean window
                // that goes straight to whatever URL we navigate to next.
                "--no-first-run",
                "--no-default-browser-check"
        );
        // Inherit IO so any Chrome stderr complaints surface in our logs (rare but useful).
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to launch Chrome: " + chromePath + " — " + e.getMessage(), e);
        }

        // 5. Wait up to 15s for the debug port to become reachable. Chrome takes a few
        // seconds to fully initialize — polling avoids guessing a fixed sleep.
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (cdpReachable(cdpUrl)) {
                logger.info("Chrome ready on {} (PID={})", cdpUrl, process.pid());
                return new LaunchResult(cdpUrl, false, process);
            }
            try { Thread.sleep(250); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Failed to come up — clean up the orphan Chrome and bail.
        process.destroy();
        throw new IllegalStateException(
                "Chrome started but never opened the CDP port at " + cdpUrl
                        + ". This usually means Chrome detected a conflicting profile. "
                        + "Try deleting " + userDataDir + " and rerunning, or close all "
                        + "running chrome.exe processes.");
    }

    /**
     * Probe known install paths per OS, then fall back to PATH lookup and (on Windows)
     * the registry. Order is "most likely first."
     *
     * <p>Override with system property {@code swarmai.tools.browser.chrome-path}
     * if you have a non-standard install (Chrome Beta, custom dir, …).
     *
     * <p>Falls back to Microsoft Edge, Brave, and Chromium — all Chromium-based and
     * support the same CDP protocol, all accepted by Google as "real browsers."
     */
    public static java.util.Optional<String> findChromeExecutable() {
        String override = System.getProperty("swarmai.tools.browser.chrome-path");
        if (override != null && !override.isBlank() && new File(override).canExecute()) {
            return java.util.Optional.of(override);
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        List<String> candidates;
        if (osName.contains("win")) {
            String programFiles  = System.getenv("ProgramFiles");
            String programFilesX = System.getenv("ProgramFiles(x86)");
            String localAppData  = System.getenv("LOCALAPPDATA");
            candidates = new java.util.ArrayList<>();
            // Google Chrome — stable, beta, canary, dev variants
            for (String pf : List.of(orDefault(programFiles, "C:\\Program Files"),
                                      orDefault(programFilesX, "C:\\Program Files (x86)"))) {
                candidates.add(pf + "\\Google\\Chrome\\Application\\chrome.exe");
                candidates.add(pf + "\\Google\\Chrome Beta\\Application\\chrome.exe");
                candidates.add(pf + "\\Google\\Chrome Dev\\Application\\chrome.exe");
            }
            if (localAppData != null) {
                // Per-user install (most common for non-admin Windows users)
                candidates.add(localAppData + "\\Google\\Chrome\\Application\\chrome.exe");
                candidates.add(localAppData + "\\Google\\Chrome SxS\\Application\\chrome.exe"); // canary
                candidates.add(localAppData + "\\Chromium\\Application\\chrome.exe");
                candidates.add(localAppData + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
            }
            // Microsoft Edge — Chromium-based, ships with every modern Windows
            for (String pf : List.of(orDefault(programFiles, "C:\\Program Files"),
                                      orDefault(programFilesX, "C:\\Program Files (x86)"))) {
                candidates.add(pf + "\\Microsoft\\Edge\\Application\\msedge.exe");
            }
        } else if (osName.contains("mac")) {
            candidates = List.of(
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    System.getProperty("user.home")
                            + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Google Chrome Beta.app/Contents/MacOS/Google Chrome Beta",
                    "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
                    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium");
        } else {
            candidates = List.of(
                    "/usr/bin/google-chrome",
                    "/usr/bin/google-chrome-stable",
                    "/usr/bin/chromium",
                    "/usr/bin/chromium-browser",
                    "/snap/bin/chromium",
                    "/opt/google/chrome/google-chrome",
                    "/usr/bin/microsoft-edge",
                    "/usr/bin/microsoft-edge-stable",
                    "/usr/bin/brave-browser");
        }
        for (String c : candidates) {
            if (new File(c).canExecute()) return java.util.Optional.of(c);
        }

        // Last-ditch: PATH lookup ('where' on Windows, 'which' elsewhere).
        for (String name : List.of("chrome.exe", "chrome", "chromium", "msedge.exe", "msedge")) {
            String found = lookupOnPath(name, osName.contains("win"));
            if (found != null) return java.util.Optional.of(found);
        }

        return java.util.Optional.empty();
    }

    private static String orDefault(String s, String def) { return s == null || s.isBlank() ? def : s; }

    private static String lookupOnPath(String name, boolean windows) {
        try {
            ProcessBuilder pb = new ProcessBuilder(windows ? "where" : "which", name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            if (p.exitValue() != 0) return null;
            String first = new String(out).split("\\R", 2)[0].trim();
            return (!first.isEmpty() && new File(first).canExecute()) ? first : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Quick CDP reachability check — 2-second timeout. */
    private static boolean cdpReachable(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/json/version"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("webSocketDebuggerUrl");
        } catch (Exception e) {
            return false;
        }
    }

    private RealChromeLauncher() { /* static utility */ }
}
