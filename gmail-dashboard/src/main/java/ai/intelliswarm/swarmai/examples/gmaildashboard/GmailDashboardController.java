package ai.intelliswarm.swarmai.examples.gmaildashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the IntelliMail UI.
 *
 * <pre>
 *   GET  /api/inbox          → list of inbox rows from Gmail
 *   POST /api/triage/{id}    → open one email, scrape it, run LLM triage, return EmailTriage
 *   GET  /api/health         → quick "is the browser tool alive" probe
 * </pre>
 */
// Only register when EmailReader is available (i.e. when BrowserTool is on
// the classpath). Without this gate, this controller would force EmailReader
// to instantiate and break boot for every non-gmail workflow.
@RestController
@RequestMapping("/api")
@ConditionalOnBean(EmailReader.class)
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

    /**
     * Streaming variant of {@link #triageOne(String)} — the summary materialises
     * token-by-token in the UI instead of arriving in one block.
     *
     * <p>Event protocol (consumed by an {@code EventSource} on the frontend):
     * <pre>
     *   event: summary_delta
     *   data:  {"id":"5","text":"The email "}
     *
     *   event: summary_delta
     *   data:  {"id":"5","text":"proposes a meeting"}
     *
     *   event: triage_complete
     *   data:  {"item":{...},"triage":{"summary":"…","actions":[…],"urgency":"…",
     *           "phishingSuspected":false},"elapsedMs":12345}
     *
     *   event: error_event     (only on failure — name avoids collision with
     *                           EventSource.onerror which fires for transport
     *                           errors AND named "error" SSE events)
     *   data:  {"id":"5","message":"…"}
     * </pre>
     *
     * <p>The summary streams via {@link DashboardService#streamSummary(String)};
     * once it completes, the structured-fields call (actions / urgency / phishing)
     * runs on the elastic scheduler so the SSE thread isn't blocked. Final
     * {@code triage_complete} carries the same {@link EmailDtos.TriageResponse}
     * shape the POST endpoint returns — clients can swap endpoints freely.
     */
    @GetMapping(path = "/triage/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter triageStream(@PathVariable String id) {
        // No timeout — caller cancels by closing the EventSource.
        SseEmitter emitter = new SseEmitter(0L);
        long t0 = System.currentTimeMillis();

        // Same listing logic as the POST variant (see triageOne).
        List<EmailDtos.InboxItem> all = reader.listInbox();
        EmailDtos.InboxItem item = null;
        for (EmailDtos.InboxItem i : all) {
            if (i.id().equals(id)) { item = i; break; }
        }
        if (item == null) {
            logger.warn("/api/triage/{}/stream → no such item in current inbox listing", id);
            sendOrFail(emitter, "error_event", Map.of("id", id, "message", "id not in current inbox"));
            emitter.complete();
            return emitter;
        }

        String body = reader.readEmail(id);
        EmailDtos.InboxItem finalItem = item;
        StringBuilder accum = new StringBuilder();

        Disposable subscription = triage.streamSummary(body)
                .doOnNext(accum::append)
                .subscribe(
                        // Per-delta: forward to client as a named SSE event.
                        delta -> sendOrFail(emitter, "summary_delta",
                                Map.of("id", id, "text", delta)),
                        // Should never fire — streamSummary swallows errors. Defensive.
                        err -> {
                            sendOrFail(emitter, "error_event",
                                    Map.of("id", id, "message", err.getMessage() == null ? "" : err.getMessage()));
                            emitter.complete();
                        },
                        // Stream done: kick off the structured pass off-thread.
                        () -> Schedulers.boundedElastic().schedule(() -> {
                            try {
                                EmailDtos.EmailTriage t = triage.extractStructured(body, accum.toString());
                                long elapsed = System.currentTimeMillis() - t0;
                                logger.info("/api/triage/{}/stream → urgency={} actions={} in {}ms (streamed)",
                                        id, t.urgency, t.actions == null ? 0 : t.actions.size(), elapsed);
                                sendOrFail(emitter, "triage_complete",
                                        new EmailDtos.TriageResponse(finalItem, t, elapsed));
                                emitter.complete();
                            } catch (Throwable e) {
                                sendOrFail(emitter, "error_event",
                                        Map.of("id", id, "message", e.getMessage() == null ? "" : e.getMessage()));
                                emitter.complete();
                            }
                        }));

        // Tear down the upstream subscription if the client disconnects mid-stream.
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());
        return emitter;
    }

    /** Helper: send an SSE event, completing the emitter with the IOException on a write failure. */
    private static void sendOrFail(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(payload));
        } catch (IOException io) {
            // Most common cause: client disconnected (closed tab). Quiet teardown.
            emitter.completeWithError(io);
        }
    }

    @GetMapping("/health")
    public java.util.Map<String, Object> health() {
        // Cheap signal — just confirms the bean wiring succeeded.
        return java.util.Map.of(
                "browser", true,
                "triage", true);
    }
}
