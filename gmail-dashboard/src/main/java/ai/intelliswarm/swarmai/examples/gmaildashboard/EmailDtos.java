package ai.intelliswarm.swarmai.examples.gmaildashboard;

import java.util.List;

/**
 * DTOs shared between {@link GmailDashboardController}, {@link EmailReader},
 * and {@link DashboardService}.
 *
 * Records — no Lombok, no boilerplate. Spring Boot's Jackson handles them
 * for both the JSON response shape and Spring AI's {@code BeanOutputConverter}
 * (which is what {@link DashboardService} uses to get typed LLM output).
 */
public final class EmailDtos {

    private EmailDtos() { }

    /** A single inbox row as scraped from Gmail. */
    public record InboxItem(
            String id,           // synthetic — index in the list ("0", "1", …) so the UI can reference it
            String sender,
            String subject,
            String snippet,      // the ~80-char preview Gmail shows in the inbox list
            String time          // the timestamp Gmail renders ("12:34 PM", "Apr 30", etc.)
    ) {}

    /**
     * Typed output produced by the local LLM for one email.
     *
     * <p>{@link DashboardService} declares this as the {@code outputType} on the
     * task so the model returns matching JSON. The framework auto-parses; we
     * never pattern-match against free-form prose.
     */
    public static class EmailTriage {
        public String summary;            // 1–2 sentences max
        public List<String> actions;      // concrete action items the user could take
        public String urgency;            // "low" | "medium" | "high"
        public boolean phishingSuspected;
        public EmailTriage() {}
    }

    /** Response shape for {@code POST /api/triage/{id}}. */
    public record TriageResponse(
            InboxItem item,
            EmailTriage triage,
            long elapsedMs
    ) {}
}
