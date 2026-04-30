package ai.intelliswarm.swarmai.examples.gmaildashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for the IntelliMail UI.
 *
 * <pre>
 *   GET  /api/inbox          → list of inbox rows from Gmail
 *   POST /api/triage/{id}    → open one email, scrape it, run LLM triage, return EmailTriage
 *   GET  /api/health         → quick "is the browser tool alive" probe
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class GmailDashboardController {

    private static final Logger logger = LoggerFactory.getLogger(GmailDashboardController.class);

    private final EmailReader reader;
    private final DashboardService triage;

    public GmailDashboardController(EmailReader reader, DashboardService triage) {
        this.reader = reader;
        this.triage = triage;
    }

    @GetMapping("/inbox")
    public List<EmailDtos.InboxItem> inbox() {
        long t0 = System.currentTimeMillis();
        List<EmailDtos.InboxItem> items = reader.listInbox();
        logger.info("/api/inbox → {} items in {}ms", items.size(), System.currentTimeMillis() - t0);
        return items;
    }

    @PostMapping("/triage/{id}")
    public EmailDtos.TriageResponse triageOne(@PathVariable String id) {
        long t0 = System.currentTimeMillis();
        // Fresh inbox snapshot — id is a positional index into the current inbox listing,
        // not a stable Gmail message id, so we always re-list to get the click target right.
        List<EmailDtos.InboxItem> all = reader.listInbox();
        EmailDtos.InboxItem item = null;
        for (EmailDtos.InboxItem i : all) {
            if (i.id().equals(id)) { item = i; break; }
        }
        if (item == null) {
            logger.warn("/api/triage/{} → no such item in current inbox listing", id);
            return null;
        }
        String body = reader.readEmail(id);
        EmailDtos.EmailTriage t = triage.triage(body);
        long elapsed = System.currentTimeMillis() - t0;
        logger.info("/api/triage/{} → urgency={} actions={} in {}ms",
                id, t.urgency, t.actions == null ? 0 : t.actions.size(), elapsed);
        return new EmailDtos.TriageResponse(item, t, elapsed);
    }

    @GetMapping("/health")
    public java.util.Map<String, Object> health() {
        // Cheap signal — just confirms the bean wiring succeeded.
        return java.util.Map.of(
                "browser", true,
                "triage", true);
    }
}
