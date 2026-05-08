package ai.intelliswarm.swarmai.examples.typedmessagehistory;

import ai.intelliswarm.swarmai.message.typed.ToolCall;
import ai.intelliswarm.swarmai.message.typed.TypedAssistantMessage;
import ai.intelliswarm.swarmai.message.typed.TypedMessageHistory;
import ai.intelliswarm.swarmai.message.typed.TypedSystemMessage;
import ai.intelliswarm.swarmai.message.typed.TypedToolResultMessage;
import ai.intelliswarm.swarmai.message.typed.TypedUserMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Showcases {@link TypedMessageHistory} (1.0.19+) end-to-end
 * with a real LLM. The example wraps two tool callbacks (calculator,
 * current_time) so every call and result is captured into a typed history,
 * then runs a multi-turn conversation that exercises both tools.
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>Tool calls and results are captured as structured records, not just
 *       strings — the harness can later say "show me every call to {@code calculator}"
 *       without parsing free-text content.</li>
 *   <li>Errors are flagged in the type system ({@code TypedToolResultMessage.isError()}),
 *       so observability dashboards can count failed-tool retries without
 *       string matching.</li>
 *   <li>The transcript renders cleanly for human inspection while the
 *       round-trip to {@code List<Message>} works for replay.</li>
 *   <li>Pattern-match-style queries make code readable: {@code h.lastAssistantMessage()}
 *       beats {@code h.stream().filter(m -> m instanceof AssistantMessage).reduce(...)}.</li>
 * </ol>
 *
 * <h2>Quality check</h2>
 * Verifies {@code (a)} ≥ 1 user / assistant / tool-call / tool-result message
 * was captured, {@code (b)} both registered tools were exercised at least once,
 * {@code (c)} the rendered transcript is well-formed.
 */
@Component
public class TypedMessageHistoryExample {

    private static final Logger logger = LoggerFactory.getLogger(TypedMessageHistoryExample.class);

    private final ChatClient.Builder chatClientBuilder;

    public TypedMessageHistoryExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        TypedMessageHistory history = new TypedMessageHistory();
        ObjectMapper mapper = new ObjectMapper();

        // ---- 1. System + user messages ----
        String systemPrompt = """
                You are a helpful assistant with two tools available:
                  calculator(expression)  — exact arithmetic
                  current_time(zone)      — current wall-clock time, e.g. zone="UTC"

                Use both tools when answering — invoke each one at least once.
                """;
        history.add(TypedSystemMessage.now(systemPrompt));

        String userPrompt = (args != null && args.length > 0)
                ? String.join(" ", args)
                : "What's 7 * 23, and what time is it in UTC right now?";
        history.add(TypedUserMessage.now(userPrompt));

        // ---- 2. Tool callbacks that record into typed history ----
        AtomicInteger calcCalls = new AtomicInteger();
        AtomicInteger timeCalls = new AtomicInteger();

        Function<CalculatorInput, String> calcFn = input -> {
            calcCalls.incrementAndGet();
            String callId = "call_calc_" + UUID.randomUUID().toString().substring(0, 8);
            String argsJson = toJson(mapper, input);
            ToolCall call = new ToolCall(callId, "calculator", argsJson);
            // Record the call as a standalone tool-call message
            history.add(ai.intelliswarm.swarmai.message.typed.TypedToolCallMessage.now(call));
            try {
                String result = evalExpression(input == null ? "" : input.expression);
                history.add(TypedToolResultMessage.success(callId, result));
                return result;
            } catch (RuntimeException e) {
                String err = "Error: " + e.getMessage();
                history.add(TypedToolResultMessage.error(callId, err));
                return err;
            }
        };
        Function<TimeInput, String> timeFn = input -> {
            timeCalls.incrementAndGet();
            String callId = "call_time_" + UUID.randomUUID().toString().substring(0, 8);
            String argsJson = toJson(mapper, input);
            ToolCall call = new ToolCall(callId, "current_time", argsJson);
            history.add(ai.intelliswarm.swarmai.message.typed.TypedToolCallMessage.now(call));
            try {
                String zone = (input == null || input.zone == null || input.zone.isBlank())
                        ? "UTC" : input.zone;
                java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of(zone));
                String result = now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                history.add(TypedToolResultMessage.success(callId, result));
                return result;
            } catch (RuntimeException e) {
                String err = "Error: " + e.getMessage();
                history.add(TypedToolResultMessage.error(callId, err));
                return err;
            }
        };

        ToolCallback calcCb = FunctionToolCallback.builder("calculator", calcFn)
                .description("Evaluate a basic math expression. args: expression (string).")
                .inputType(CalculatorInput.class)
                .build();
        ToolCallback timeCb = FunctionToolCallback.builder("current_time", timeFn)
                .description("Get the current wall-clock time. args: zone (e.g. UTC, Europe/London).")
                .inputType(TimeInput.class)
                .build();

        ChatClient chatClient = chatClientBuilder.build();

        printHeader(userPrompt);

        // ---- 3. Run the LLM call ----
        String response;
        try {
            response = chatClient.prompt()
                    .messages(history.toSpringMessages())
                    .toolCallbacks(calcCb, timeCb)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            logger.error("LLM call failed: {}", e.getMessage(), e);
            return;
        }
        history.add(TypedAssistantMessage.now(response));

        // ---- 4. Show the captured transcript ----
        nl();
        line("======================================================================");
        line("  Rendered transcript (TypedMessageHistory.renderTranscript)");
        line("======================================================================");
        line(history.renderTranscript());

        // ---- 5. Demonstrate the typed queries ----
        nl();
        line("======================================================================");
        line("  Typed-history queries");
        line("======================================================================");
        line(String.format("  size():                  %d", history.size()));
        line(String.format("  systemMessages():        %d", history.systemMessages().size()));
        line(String.format("  userMessages():          %d", history.userMessages().size()));
        line(String.format("  assistantMessages():     %d", history.assistantMessages().size()));
        line(String.format("  toolCallMessages():      %d", history.toolCallMessages().size()));
        line(String.format("  toolResultMessages():    %d", history.toolResultMessages().size()));
        line(String.format("  toolCallCount() (sum):   %d", history.toolCallCount()));
        line(String.format("  toolResultCount():       %d", history.toolResultCount()));
        line(String.format("  toolErrorCount():        %d", history.toolErrorCount()));

        history.firstUserMessage().ifPresent(u ->
                line("  firstUserMessage():      " + truncate(u.content(), 80)));
        history.lastAssistantMessage().ifPresent(a ->
                line("  lastAssistantMessage():  " + truncate(a.content(), 80)));

        // Filter: every call to 'calculator' specifically
        long calcInvocations = history.toolCallMessages().stream()
                .filter(c -> "calculator".equals(c.toolCall().name()))
                .count();
        long timeInvocations = history.toolCallMessages().stream()
                .filter(c -> "current_time".equals(c.toolCall().name()))
                .count();
        line(String.format("  filter(name=calculator): %d", calcInvocations));
        line(String.format("  filter(name=current_time): %d", timeInvocations));

        // ---- 6. Quality check ----
        runQualityCheck(history, calcCalls.get(), timeCalls.get(),
                calcInvocations, timeInvocations);
    }

    private void runQualityCheck(TypedMessageHistory history,
                                  int calcCallbackInvocations,
                                  int timeCallbackInvocations,
                                  long calcRecorded, long timeRecorded) {
        nl();
        line("======================================================================");
        line("  Quality check");
        line("======================================================================");

        int sys = history.systemMessages().size();
        int users = history.userMessages().size();
        int assistants = history.assistantMessages().size();
        int calls = history.toolCallMessages().size();
        int results = history.toolResultMessages().size();

        boolean coreRolesPresent = sys >= 1 && users >= 1 && assistants >= 1;
        boolean toolsExercised = calcCallbackInvocations >= 1 && timeCallbackInvocations >= 1;
        boolean callsAndResultsRecorded = calls >= 2 && results >= 2;
        boolean recordedCountsMatchInvocations =
                calcRecorded == calcCallbackInvocations
                && timeRecorded == timeCallbackInvocations;
        boolean transcriptWellFormed = !history.renderTranscript().isBlank()
                && history.renderTranscript().contains("[user]")
                && history.renderTranscript().contains("[assistant]");

        nl();
        line("  Checks:");
        line("    [" + check(coreRolesPresent)
                + "] core roles present (system=" + sys + " user=" + users
                + " assistant=" + assistants + ")");
        line("    [" + check(toolsExercised)
                + "] both tools exercised (calculator=" + calcCallbackInvocations
                + " current_time=" + timeCallbackInvocations + ")");
        line("    [" + check(callsAndResultsRecorded)
                + "] tool calls + results captured (calls=" + calls
                + " results=" + results + ")");
        line("    [" + check(recordedCountsMatchInvocations)
                + "] recorded counts match callback invocations "
                + "(callbacks: calc=" + calcCallbackInvocations
                + " time=" + timeCallbackInvocations + "; recorded: calc="
                + calcRecorded + " time=" + timeRecorded + ")");
        line("    [" + check(transcriptWellFormed)
                + "] rendered transcript is well-formed (contains roles)");

        nl();
        boolean allPass = coreRolesPresent && toolsExercised
                && callsAndResultsRecorded && recordedCountsMatchInvocations
                && transcriptWellFormed;
        if (allPass) {
            line("  QUALITY CHECK PASSED");
            line("  -> Typed history captured every interaction; queries return structured data.");
        } else {
            line("  QUALITY CHECK FAILED — inspect counts above.");
        }
    }

    // =================================================================
    // Helpers
    // =================================================================

    private static String evalExpression(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("expression is required");
        }
        // Tiny safe evaluator — supports +, -, *, /, parentheses, and decimals.
        return String.valueOf(new ShuntingYard().evaluate(expr.trim()));
    }

    private static String toJson(ObjectMapper mapper, Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String check(boolean ok) { return ok ? "PASS" : "FAIL"; }

    private static void printHeader(String userPrompt) {
        nl();
        line("======================================================================");
        line("  TypedMessageHistory — typed wrappers around the conversation");
        line("======================================================================");
        line("");
        line("  Two tool callbacks (calculator, current_time) record their calls and");
        line("  results into a TypedMessageHistory as structured records — NOT free");
        line("  text. After the run we query the history with type-safe accessors.");
        line("");
        line("  User goal:");
        line("    " + truncate(userPrompt, 200));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CalculatorInput {
        public String expression;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TimeInput {
        public String zone;
    }

    /**
     * Minimal infix-expression evaluator (Shunting-Yard). Supports +, -, *, /,
     * parentheses, integer + decimal literals. Throws on any other input.
     */
    private static final class ShuntingYard {
        private int pos;
        private String src;

        double evaluate(String expression) {
            this.src = expression;
            this.pos = 0;
            double result = parseExpression();
            skipWs();
            if (pos < src.length()) {
                throw new IllegalArgumentException(
                        "Unexpected character at " + pos + ": " + src.substring(pos));
            }
            return result;
        }

        private double parseExpression() {
            double v = parseTerm();
            while (true) {
                skipWs();
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    char op = src.charAt(pos++);
                    double r = parseTerm();
                    v = (op == '+') ? v + r : v - r;
                } else break;
            }
            return v;
        }

        private double parseTerm() {
            double v = parseFactor();
            while (true) {
                skipWs();
                if (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/')) {
                    char op = src.charAt(pos++);
                    double r = parseFactor();
                    v = (op == '*') ? v * r : v / r;
                } else break;
            }
            return v;
        }

        private double parseFactor() {
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '(') {
                pos++;
                double v = parseExpression();
                skipWs();
                if (pos >= src.length() || src.charAt(pos) != ')') {
                    throw new IllegalArgumentException("Missing closing paren at " + pos);
                }
                pos++;
                return v;
            }
            int start = pos;
            if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) pos++;
            while (pos < src.length()
                    && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            if (start == pos) {
                throw new IllegalArgumentException("Expected number at " + pos);
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }
}
