package ai.intelliswarm.swarmai.examples.gmaildashboard;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Runs an LLM over a single email's body and returns typed
 * {@link EmailDtos.EmailTriage} (summary + actions + urgency).
 *
 * <p>Uses {@code Task.outputType(EmailTriage.class)} so the model returns clean
 * JSON we never have to regex over. The agent is single-shot, no tools — pure
 * "given this text, return this structure."
 */
@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);

    private final Agent triageAgent;

    public DashboardService(ChatClient.Builder chatClientBuilder) {
        this.triageAgent = Agent.builder()
                .role("Email Triage Analyst")
                .goal("Read one email and produce: a 1-2 sentence summary, a list of concrete "
                        + "action items the recipient could take, an urgency rating, and a "
                        + "phishing flag if anything is suspicious.")
                .backstory("You are an executive assistant. You are concise, factual, and "
                        + "never invent details. If the email is purely informational with no "
                        + "action needed, return an empty actions list.")
                .chatClient(chatClientBuilder.build())
                .verbose(false)
                .temperature(0.1)
                // Generous timeout so cold-start local Ollama doesn't get killed at 2 min.
                .maxExecutionTime(300_000)
                .build();
    }

    /**
     * @param emailBody the visible body text of one email (sender, subject, content)
     * @return typed triage result, or null if the LLM call failed entirely
     */
    public EmailDtos.EmailTriage triage(String emailBody) {
        if (emailBody == null || emailBody.isBlank()) {
            EmailDtos.EmailTriage empty = new EmailDtos.EmailTriage();
            empty.summary = "(empty email — nothing to triage)";
            empty.actions = java.util.List.of();
            empty.urgency = "low";
            return empty;
        }
        Task task = Task.builder()
                .description("Triage this email. Return a 1-2 sentence summary, "
                        + "a list of concrete actions, an urgency level (low/medium/high), "
                        + "and a phishing-suspected boolean.\n\n"
                        + "Don't invent senders, dates, links, or amounts that aren't in "
                        + "the text. If the email is informational, return an empty actions list.\n\n"
                        + "EMAIL:\n" + truncate(emailBody, 4000))
                .expectedOutput("EmailTriage with summary, actions[], urgency, phishingSuspected")
                .outputType(EmailDtos.EmailTriage.class)
                .agent(triageAgent)
                .build();

        try {
            TaskOutput out = triageAgent.executeTask(task, java.util.List.of());
            EmailDtos.EmailTriage result = out.as(EmailDtos.EmailTriage.class);
            if (result == null) {
                // The LLM didn't return parseable JSON — wrap the raw output as a degraded summary.
                result = new EmailDtos.EmailTriage();
                result.summary = excerpt(out.getRawOutput(), 240);
                result.actions = java.util.List.of();
                result.urgency = "low";
            }
            if (result.actions == null) result.actions = java.util.List.of();
            if (result.urgency == null) result.urgency = "low";
            return result;
        } catch (Exception e) {
            logger.warn("DashboardService: LLM call failed: {}", e.getMessage());
            EmailDtos.EmailTriage err = new EmailDtos.EmailTriage();
            err.summary = "Triage failed — " + e.getClass().getSimpleName() + ": " + e.getMessage();
            err.actions = java.util.List.of();
            err.urgency = "low";
            return err;
        }
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "\n…[truncated]");
    }

    private static String excerpt(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
