package ai.intelliswarm.swarmai.examples.askuserquestion;

import ai.intelliswarm.swarmai.state.question.AskUserQuestionTool;
import ai.intelliswarm.swarmai.state.question.RecordingUserQuestionResolver;
import ai.intelliswarm.swarmai.state.question.ScriptedUserQuestionResolver;
import ai.intelliswarm.swarmai.state.question.UserAnswer;
import ai.intelliswarm.swarmai.state.question.UserQuestion;
import ai.intelliswarm.swarmai.state.question.UserQuestionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Showcases the {@link AskUserQuestionTool} primitive (1.0.19+)
 * end-to-end with a real LLM. The agent receives a single tool —
 * {@code ask_user_question} — bound to a {@link ScriptedUserQuestionResolver}
 * pre-loaded with deterministic answers, all wrapped in a
 * {@link RecordingUserQuestionResolver} for audit. The example asks the LLM
 * to plan a deployment and gates several decisions on user input the LLM
 * cannot infer from prior context.
 *
 * <h2>Why scripted (not console)?</h2>
 * Console input doesn't survive automation. The {@link ScriptedUserQuestionResolver}
 * makes the run deterministic and lets the quality check assert exactly which
 * questions were asked and which answers came back. Real interactive use
 * substitutes {@code new ConsoleUserQuestionResolver()}.
 *
 * <h2>Quality check</h2>
 * <ol>
 *   <li>The LLM called the tool at least 3 times</li>
 *   <li>Every scripted answer was consumed in order</li>
 *   <li>Both option-style and freeform answers reached the LLM</li>
 *   <li>The final response references at least one of the answers
 *       (soft check — proves the user input visibly steered the agent)</li>
 * </ol>
 */
@Component
public class AskUserQuestionExample {

    private static final Logger logger = LoggerFactory.getLogger(AskUserQuestionExample.class);

    private final ChatClient.Builder chatClientBuilder;

    public AskUserQuestionExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        // ---- 1. Scripted answers — exactly what a user would type, in order ----
        // The system prompt below directs the LLM to ask for: env, branch,
        // approval, and notification preference. We script answers in the
        // expected order. The agent asking unexpected questions still works
        // (Scripted just consumes the next answer) but the quality check
        // confirms the script wasn't exhausted prematurely.
        ScriptedUserQuestionResolver scripted = new ScriptedUserQuestionResolver(
                "production",                                  // env (option)
                "release/v2.0",                                // branch (freeform)
                "yes",                                         // approval (option, lowercase match)
                "notify slack channel #releases on success"    // notification (freeform)
        );
        RecordingUserQuestionResolver resolver =
                new RecordingUserQuestionResolver(scripted);

        // ---- 2. Build agent-callable tool ----
        ToolCallback askCallback = AskUserQuestionTool.callback(resolver);

        // ---- 3. ChatClient ----
        ChatClient chatClient = chatClientBuilder.build();

        String systemPrompt = """
                You are a deployment-planning assistant. The user is offline and
                CANNOT see any plain text you send. You can only communicate with
                the user by calling the ask_user_question tool.

                CRITICAL RULES:
                  * Every question for the user MUST be a tool call to
                    ask_user_question. Never write the question as plain text.
                  * Do NOT introduce yourself, summarise, or ask "shall we begin" —
                    just start asking questions via the tool.
                  * After ALL four answers are collected, produce ONE final plan
                    as plain text that explicitly references every answer.

                You must collect, in this order, by calling ask_user_question:
                  1. Target environment    — options: ["staging", "production"], allowFreeform=false
                  2. Source branch name    — no options, allowFreeform=true
                  3. Final approval        — options: ["yes", "no"], allowFreeform=false
                  4. Notification prefs    — no options, allowFreeform=true

                Each ask_user_question call returns either "User selected: X" or
                "User answered (freeform): X". Use the X verbatim in your final plan.
                """;

        String userPrompt = (args != null && args.length > 0)
                ? String.join(" ", args)
                : "Plan a release. Begin by calling ask_user_question for the first decision.";

        printHeader(userPrompt);

        long startMs = System.currentTimeMillis();
        String response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .toolCallbacks(askCallback)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            logger.error("LLM call failed: {}", e.getMessage(), e);
            return;
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        // ---- 4. Print results ----
        nl();
        line("======================================================================");
        line("  Question/answer transcript (every interaction the agent had)");
        line("======================================================================");
        List<RecordingUserQuestionResolver.Entry> hist = resolver.history();
        for (int i = 0; i < hist.size(); i++) {
            RecordingUserQuestionResolver.Entry e = hist.get(i);
            UserQuestion q = e.question();
            UserAnswer a = e.answer();
            line(String.format("%n  Q%d: %s", i + 1, q.question()));
            if (!q.options().isEmpty()) {
                line("      options: " + q.options() + (q.allowFreeform() ? " (or freeform)" : ""));
            }
            if (a == null) {
                line("      A:  (no answer — resolver threw)");
            } else if (a.isFreeform()) {
                line("      A:  [freeform] " + a.value());
            } else {
                line("      A:  [option]   " + a.value());
            }
        }

        nl();
        line("======================================================================");
        line("  Final response from the LLM");
        line("======================================================================");
        line(response == null ? "(empty)" : response);

        // ---- 5. Quality check ----
        nl();
        line("======================================================================");
        line("  Quality check");
        line("======================================================================");
        runQualityCheck(scripted, resolver, response, elapsedMs);
    }

    private void runQualityCheck(ScriptedUserQuestionResolver scripted,
                                  RecordingUserQuestionResolver resolver,
                                  String response, long elapsedMs) {
        List<UserAnswer> answers = resolver.receivedAnswers();
        long optionAnswers = answers.stream().filter(a -> !a.isFreeform()).count();
        long freeformAnswers = answers.stream().filter(UserAnswer::isFreeform).count();

        line(String.format("  elapsed:           %.2fs", elapsedMs / 1000.0));
        line(String.format("  questions asked:   %d", resolver.askedQuestions().size()));
        line(String.format("  answers received:  %d  (%d option, %d freeform)",
                answers.size(), optionAnswers, freeformAnswers));
        line(String.format("  scripted leftover: %d", scripted.remaining()));

        boolean atLeastThreeQuestions = resolver.askedQuestions().size() >= 3;
        boolean scriptConsumed = scripted.remaining() == 0;
        boolean bothAnswerKinds = optionAnswers > 0 && freeformAnswers > 0;
        boolean responseReferencesAnAnswer = referencesAnyAnswer(response, answers);

        nl();
        line("  Checks:");
        line("    [" + check(atLeastThreeQuestions)
                + "] >= 3 questions asked   (got " + resolver.askedQuestions().size() + ")");
        line("    [" + check(scriptConsumed)
                + "] script fully consumed (no leftover scripted answers)");
        line("    [" + check(bothAnswerKinds)
                + "] both option and freeform answers exercised "
                + "(option=" + optionAnswers + " freeform=" + freeformAnswers + ")");
        line("    [" + check(responseReferencesAnAnswer)
                + "] final response references at least one user answer "
                + "(soft check — proves the input steered the agent)");

        nl();
        boolean allPass = atLeastThreeQuestions && scriptConsumed
                && bothAnswerKinds && responseReferencesAnAnswer;
        if (allPass) {
            line("  QUALITY CHECK PASSED");
            line("  -> The LLM paused to ask, the resolver answered, the agent acted.");
        } else {
            line("  QUALITY CHECK FAILED");
            line("  -> See counts above. LLM phrasing varies — the soft 'references answer'");
            line("  -> check may miss without affecting the underlying mechanism.");
        }
    }

    private static boolean referencesAnyAnswer(String response, List<UserAnswer> answers) {
        if (response == null) return false;
        String lower = response.toLowerCase();
        for (UserAnswer a : answers) {
            String value = a.value();
            if (value == null) continue;
            // Match if any token-length-3+ substring of the answer appears in the response.
            // (Keeps the check robust to LLM paraphrasing while still requiring an actual reference.)
            for (String token : value.toLowerCase().split("[^a-zA-Z0-9_/#]+")) {
                if (token.length() >= 3 && lower.contains(token)) return true;
            }
        }
        return false;
    }

    private static String check(boolean ok) { return ok ? "PASS" : "FAIL"; }

    private static void printHeader(String userPrompt) {
        nl();
        line("======================================================================");
        line("  AskUserQuestion — agent-driven user prompts");
        line("======================================================================");
        line("");
        line("  The agent has one tool: ask_user_question(question, options?, allowFreeform?).");
        line("  A ScriptedUserQuestionResolver returns deterministic answers in FIFO");
        line("  order, wrapped in a RecordingUserQuestionResolver so we can audit");
        line("  every Q/A pair after the run.");
        line("");
        line("  The system prompt directs the LLM to gather:");
        line("    1. Target environment   (option-style)");
        line("    2. Source branch        (freeform)");
        line("    3. Final approval       (option-style)");
        line("    4. Notification prefs   (freeform)");
        line("");
        line("  User goal:");
        line("    " + truncate(userPrompt, 200));
        line("");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }
}
