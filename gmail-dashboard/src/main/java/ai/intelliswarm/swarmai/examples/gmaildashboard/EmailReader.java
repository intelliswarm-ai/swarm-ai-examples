package ai.intelliswarm.swarmai.examples.gmaildashboard;

import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around {@link BrowserTool} for the two Gmail interactions this
 * UI needs:
 * <ul>
 *   <li>{@link #listInbox()} — scrape the visible inbox rows into structured DTOs</li>
 *   <li>{@link #readEmail(String)} — click into a specific row, scrape the body, return to the list</li>
 * </ul>
 *
 * <p>All Gmail-CSS-selector knowledge lives here. If Google rotates their class
 * names (they do, occasionally) the rest of the example doesn't care.
 */
@Service
public class EmailReader {

    private static final Logger logger = LoggerFactory.getLogger(EmailReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BrowserTool browser;

    public EmailReader(BrowserTool browser) {
        this.browser = browser;
    }

    /**
     * Returns the visible inbox rows in their displayed order. Uses {@code evaluate_js}
     * so we get sender/subject/snippet/time as separate fields rather than a wall of text.
     */
    @SuppressWarnings("unchecked")
    public List<EmailDtos.InboxItem> listInbox() {
        // Make sure we're on the inbox; cheap if we already are.
        browser.execute(Map.of("operation", "navigate",
                "url", "https://mail.google.com/mail/u/0/#inbox"));
        browser.execute(Map.of("operation", "wait_for",
                "selector", "[role=\"main\"]",
                "timeout_ms", 30_000));

        // Gmail's inbox table — selector that's been stable for years (rows have role="row").
        // Pull the structured fields with a small, focused JS expression. This is much more
        // reliable than parsing innerText of [role="main"] which mashes everything together.
        String js = "(() => {\n" +
                "  const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]');\n" +
                "  const out = [];\n" +
                "  rows.forEach((r, idx) => {\n" +
                "    if (idx === 0) return; // header row in some layouts\n" +
                // sender, subject, snippet, time — Gmail uses spans with these data attrs.
                "    const sender  = r.querySelector('[email]')?.getAttribute('name')\n" +
                "                 ?? r.querySelector('[email]')?.getAttribute('email')\n" +
                "                 ?? r.querySelector('span.bA4')?.innerText\n" +
                "                 ?? '';\n" +
                "    const subject = r.querySelector('span.bog')?.innerText ?? '';\n" +
                "    const snippet = r.querySelector('span.y2')?.innerText  ?? '';\n" +
                "    const time    = r.querySelector('span.xW span')?.innerText\n" +
                "                 ?? r.querySelector('td[title]')?.getAttribute('title')\n" +
                "                 ?? '';\n" +
                "    if (sender || subject) out.push({sender, subject, snippet, time});\n" +
                "  });\n" +
                "  return JSON.stringify(out.slice(0, 30));\n" +  // cap to first 30 visible rows
                "})()";
        Object raw = browser.execute(Map.of("operation", "evaluate_js", "code", js));
        return parseInboxItems(String.valueOf(raw));
    }

    /**
     * Opens the email at {@code id} (which is the index from {@link #listInbox()}),
     * scrapes its body, and returns to the inbox list.
     *
     * @return the email's visible body text, or empty string if the click failed
     */
    public String readEmail(String id) {
        int idx;
        try { idx = Integer.parseInt(id); } catch (NumberFormatException e) { return ""; }

        // Click the Nth row. Skip the header (index 0).
        String clickJs = String.format(
                "(() => { const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]'); " +
                "  const target = rows[%d + 1]; if (!target) return false; target.click(); return true; })()",
                idx);
        Object clicked = browser.execute(Map.of("operation", "evaluate_js", "code", clickJs));
        if (!"true".equals(String.valueOf(clicked))) {
            logger.warn("EmailReader: couldn't click row idx={}", idx);
            return "";
        }

        // Wait for the message-view container, then scrape.
        browser.execute(Map.of("operation", "wait_for",
                "selector", "[role=\"main\"] [role=\"listitem\"], div.adn",
                "timeout_ms", 15_000));
        Object body = browser.execute(Map.of("operation", "scrape",
                "selector", "[role=\"main\"]"));
        String text = String.valueOf(body);

        // Click "Back to Inbox" so the next listInbox() picks up the same rows.
        // The CSS selector for the back button has rotated multiple times in Gmail's
        // history; the most stable signal is the inbox URL itself.
        browser.execute(Map.of("operation", "navigate",
                "url", "https://mail.google.com/mail/u/0/#inbox"));
        browser.execute(Map.of("operation", "wait_for",
                "selector", "[role=\"main\"]",
                "timeout_ms", 15_000));

        return text;
    }

    private List<EmailDtos.InboxItem> parseInboxItems(String json) {
        if (json == null || json.isBlank() || json.equals("null")) return List.of();
        try {
            // The JS returns a JSON-encoded string (so it goes through the CDP wire as a
            // single value). Strip wrapping quotes if Playwright wrapped it as a literal string.
            String body = json.startsWith("\"") ? JSON.readValue(json, String.class) : json;
            List<Map<String, Object>> raw = JSON.readValue(body, new TypeReference<>() {});
            List<EmailDtos.InboxItem> out = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                Map<String, Object> r = raw.get(i);
                out.add(new EmailDtos.InboxItem(
                        String.valueOf(i),
                        str(r.get("sender")),
                        str(r.get("subject")),
                        str(r.get("snippet")),
                        str(r.get("time"))));
            }
            return out;
        } catch (Exception e) {
            logger.warn("EmailReader: failed to parse inbox JSON ({}). Raw was: {}",
                    e.getMessage(), excerpt(json, 200));
            return List.of();
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private static String excerpt(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
