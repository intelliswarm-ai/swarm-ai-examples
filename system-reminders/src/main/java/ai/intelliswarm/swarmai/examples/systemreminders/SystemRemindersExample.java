package ai.intelliswarm.swarmai.examples.systemreminders;

import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.TaskItem;
import ai.intelliswarm.swarmai.state.plan.TaskList;
import ai.intelliswarm.swarmai.state.plan.TaskListRenderer;
import ai.intelliswarm.swarmai.state.reminder.SystemReminder;
import ai.intelliswarm.swarmai.state.reminder.SystemReminderChannel;
import ai.intelliswarm.swarmai.state.reminder.SystemReminderPromptDecorator;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Showcases the {@link SystemReminderChannel} primitive (1.0.19+)
 * end-to-end with a real LLM. The harness posts reminders between turns based
 * on observed {@link TaskList} state, and the prompt-assembly path drains them
 * and prepends them to each next turn — the same pattern modern AI agent harnesses use to
 * inject runtime context the agent should be aware of.
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>Producers (the harness) can post reminders without the consumer
 *       (the LLM) being aware until the next prompt-assembly step.</li>
 *   <li>{@code drain-on-read} semantics ensure reminders fire exactly once.</li>
 *   <li>{@code postOnce} dedup means "first time the count exceeds N" alerts
 *       don't repeat across many turns.</li>
 *   <li>The agent visibly responds to the reminder content — the reminder
 *       changes the agent's behavior in the next turn.</li>
 * </ol>
 *
 * <h2>Three-turn flow</h2>
 * <pre>
 *   Turn 1: "Decompose this trip into tasks. Do NOT execute yet."
 *           Agent calls task_create x N.
 *           harnessTick(): posts "you've drafted N tasks; now execute them".
 *
 *   Turn 2: "Continue with your plan." (preceded by the drained reminder)
 *           Agent calls task_update(start) -> work -> task_update(complete) for each.
 *           harnessTick(): posts "all N tasks complete; summarise".
 *
 *   Turn 3: "Anything else?" (preceded by the drained reminder)
 *           Agent gives a wrap-up summary that explicitly references the reminder.
 * </pre>
 *
 * <h2>Quality check</h2>
 * Verifies that {@code (a)} ≥ 2 reminders were posted, {@code (b)} both turn 2
 * and turn 3 user messages contained a {@code <system-reminder>} block,
 * {@code (c)} all tasks reached terminal state, and {@code (d)} turn 3's
 * response mentions completion or summary keywords (a soft check that the
 * "all-done" reminder actually steered the agent).
 */
@Component
public class SystemRemindersExample {

    private static final Logger logger = LoggerFactory.getLogger(SystemRemindersExample.class);

    private final ChatClient.Builder chatClientBuilder;

    public SystemRemindersExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        String userGoal = (args != null && args.length > 0)
                ? String.join(" ", args)
                : "Plan a 3-day trip to Tokyo for a first-time visitor (research, "
                + "booking, itinerary, packing, on-the-ground logistics).";

        // ---- 1. Wire two channels: TaskList state + harness->agent reminders ----
        InMemoryPlanChannelAccessor planChannel = new InMemoryPlanChannelAccessor();
        TaskList taskList = new TaskList(planChannel, "agent");
        TaskListRenderer taskRenderer = new TaskListRenderer();

        SystemReminderChannel reminderChannel = new SystemReminderChannel();
        SystemReminderPromptDecorator decorator = new SystemReminderPromptDecorator(reminderChannel);

        AtomicInteger transitionCount = new AtomicInteger(0);
        AtomicInteger createCalls = new AtomicInteger(0);
        AtomicInteger updateCalls = new AtomicInteger(0);

        // ---- 2. Print every plan transition so the user can follow along ----
        planChannel.addListener((prev, curr) -> {
            int n = transitionCount.incrementAndGet();
            String tag = (prev == null) ? "[+]" : "[" + curr.status() + "]";
            String desc = (curr.payload() instanceof TaskItem ti)
                    ? ti.description() : String.valueOf(curr.payload());
            line(String.format("    plan %2d %-12s %s", n, tag, truncate(desc, 70)));
        });

        // ---- 3. Mirror reminder posts to stdout so the run is observable ----
        List<SystemReminder> reminderHistory = new ArrayList<>();
        reminderChannel.addListener(r -> {
            reminderHistory.add(r);
            line(String.format("    reminder posted: [%s%s] %s",
                    r.source(),
                    r.severity() == SystemReminder.Severity.WARN ? " WARN" : "",
                    truncate(r.message(), 80)));
        });

        // ---- 4. Tool callbacks (same shape as task-list example) ----
        Function<TaskCreateInput, String> createFn = input -> {
            createCalls.incrementAndGet();
            if (input == null || input.description == null || input.description.isBlank()) {
                return "Error: description is required.";
            }
            String activeForm = (input.activeForm == null || input.activeForm.isBlank())
                    ? null : input.activeForm;
            String id = (activeForm != null)
                    ? taskList.createTask(input.description, activeForm)
                    : taskList.createTask(input.description);
            return "Created task " + id;
        };
        Function<TaskUpdateInput, String> updateFn = input -> {
            updateCalls.incrementAndGet();
            if (input == null || input.taskId == null || input.taskId.isBlank()) {
                return "Error: taskId is required.";
            }
            String status = (input.status == null) ? "" : input.status.toLowerCase().trim();
            try {
                switch (status) {
                    case "start" -> taskList.startTask(input.taskId);
                    case "complete" -> {
                        if (input.resultNote != null && !input.resultNote.isBlank()) {
                            taskList.completeTask(input.taskId, input.resultNote);
                        } else {
                            taskList.completeTask(input.taskId);
                        }
                    }
                    case "cancel" -> taskList.cancelTask(input.taskId,
                            input.resultNote == null ? "cancelled" : input.resultNote);
                    default -> {
                        return "Error: status must be start|complete|cancel; got '" + input.status + "'";
                    }
                }
            } catch (RuntimeException e) {
                return "Error: " + e.getMessage();
            }
            return "Task " + input.taskId + " -> " + status;
        };
        ToolCallback createCb = FunctionToolCallback.builder("task_create", createFn)
                .description("Add a pending task. Required: description. Optional: activeForm. Returns task id.")
                .inputType(TaskCreateInput.class)
                .build();
        ToolCallback updateCb = FunctionToolCallback.builder("task_update", updateFn)
                .description("Update a task's status. Required: taskId, status (start|complete|cancel). Optional: resultNote.")
                .inputType(TaskUpdateInput.class)
                .build();

        ChatClient chatClient = chatClientBuilder.build();

        // ---- 5. Conversation history — manual so we can prepend reminders per-turn ----
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt()));

        printHeader(userGoal);

        // ---- TURN 1 — plan only ----
        line("--- Turn 1: decompose into tasks (do NOT execute yet) ---");
        String turn1User = "Goal: " + userGoal
                + "\n\nDecompose this into 5-7 actionable tasks using task_create. "
                + "Do NOT call task_update yet — you'll execute the plan in a follow-up turn.";
        history.add(new UserMessage(turn1User));
        String r1 = callLlm(chatClient, history, createCb, updateCb);
        history.add(new AssistantMessage(r1));
        line("\n  agent reply (turn 1): " + truncate(oneLine(r1), 200));

        harnessTick(taskList, reminderChannel, /*finalTurn=*/false);

        // ---- TURN 2 — execute (reminder prepended) ----
        line("\n--- Turn 2: continue (the harness has injected a reminder) ---");
        String turn2RawUser = "Continue with your plan now. For each task: task_update(start), "
                + "produce a one-paragraph result, then task_update(complete) with a short note.";
        String turn2User = decorator.prepend(turn2RawUser);
        boolean turn2HasReminder = turn2User.contains("<system-reminder>");
        line("    turn 2 user prompt contains <system-reminder>: " + turn2HasReminder);
        history.add(new UserMessage(turn2User));
        String r2 = callLlm(chatClient, history, createCb, updateCb);
        history.add(new AssistantMessage(r2));
        line("\n  agent reply (turn 2): " + truncate(oneLine(r2), 200));

        harnessTick(taskList, reminderChannel, /*finalTurn=*/true);

        // ---- TURN 3 — summary (reminder prepended) ----
        line("\n--- Turn 3: wrap up (another reminder prepended) ---");
        String turn3RawUser = "Anything else worth noting before we close out?";
        String turn3User = decorator.prepend(turn3RawUser);
        boolean turn3HasReminder = turn3User.contains("<system-reminder>");
        line("    turn 3 user prompt contains <system-reminder>: " + turn3HasReminder);
        history.add(new UserMessage(turn3User));
        String r3 = callLlm(chatClient, history, createCb, updateCb);
        history.add(new AssistantMessage(r3));
        line("\n  agent reply (turn 3): " + truncate(oneLine(r3), 200));

        // ---- 6. Final state + quality check ----
        nl();
        line("======================================================================");
        line("  Final TaskList (rendered)");
        line("======================================================================");
        line(taskRenderer.render(planChannel.get()));

        nl();
        line("======================================================================");
        line("  Reminder history (every reminder posted by the harness)");
        line("======================================================================");
        for (int i = 0; i < reminderHistory.size(); i++) {
            SystemReminder r = reminderHistory.get(i);
            line(String.format("  %d. [%s%s] %s",
                    i + 1,
                    r.source(),
                    r.severity() == SystemReminder.Severity.WARN ? " WARN" : "",
                    r.message()));
        }
        if (reminderHistory.isEmpty()) line("  (none — quality check will fail)");

        runQualityCheck(taskList, reminderHistory.size(),
                turn2HasReminder, turn3HasReminder, r3,
                createCalls.get(), updateCalls.get(),
                transitionCount.get());
    }

    /**
     * The harness's policy for when to post a reminder. Called between LLM turns.
     * It examines the current TaskList state and posts a directive nudge.
     */
    private void harnessTick(TaskList taskList, SystemReminderChannel channel, boolean finalTurn) {
        int pending = taskList.pendingTasks().size();
        int inProgress = taskList.inProgressTasks().size();
        int done = taskList.completedTasks().size();
        int cancelled = taskList.cancelledTasks().size();
        int total = pending + inProgress + done + cancelled;

        // Once-per-conversation: warn if the LLM drafted a lot of tasks.
        if (total >= 7) {
            channel.postOnce("task-list",
                    "You drafted " + total + " tasks — consider whether some can be combined.",
                    SystemReminder.Severity.WARN);
        }

        if (!finalTurn) {
            // Between turn 1 and 2: nudge into execution mode.
            if (pending > 0 && inProgress == 0 && done == 0) {
                channel.post("task-list",
                        "You have " + pending + " pending tasks and none in progress. "
                                + "Begin executing them one at a time using task_update.");
            } else if (inProgress > 1) {
                channel.postWarn("task-list",
                        "You have " + inProgress + " tasks in progress simultaneously — "
                                + "finish one before starting another for cleaner traces.");
            }
        } else {
            // Between turn 2 and 3: signal completion.
            if (done == total && total > 0) {
                channel.post("task-list",
                        "All " + total + " tasks are now complete. "
                                + "Summarise what you accomplished and call out any follow-ups.");
            } else if (pending > 0 || inProgress > 0) {
                channel.postWarn("task-list",
                        "You still have " + pending + " pending and " + inProgress + " in-progress tasks. "
                                + "Either finish them or explicitly cancel.");
            }
        }
    }

    private String callLlm(ChatClient chatClient, List<Message> history,
                            ToolCallback createCb, ToolCallback updateCb) {
        try {
            return chatClient.prompt()
                    .messages(history)
                    .toolCallbacks(createCb, updateCb)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            logger.error("LLM call failed: {}", e.getMessage(), e);
            return "(LLM call failed: " + e.getMessage() + ")";
        }
    }

    private void runQualityCheck(TaskList taskList, int reminderCount,
                                  boolean turn2HasReminder, boolean turn3HasReminder,
                                  String turn3Response,
                                  int createCalls, int updateCalls, int transitions) {
        List<PlanEntry> all = taskList.allTasks();
        List<PlanEntry> done = taskList.completedTasks();
        List<PlanEntry> stillActive = new ArrayList<>();
        stillActive.addAll(taskList.pendingTasks());
        stillActive.addAll(taskList.inProgressTasks());

        boolean enoughReminders = reminderCount >= 2;
        boolean bothLaterTurnsHaveReminders = turn2HasReminder && turn3HasReminder;
        boolean allTasksTerminal = stillActive.isEmpty() && !all.isEmpty();
        boolean turn3Wraps = mentionsCompletionOrSummary(turn3Response);

        nl();
        line("======================================================================");
        line("  Quality check");
        line("======================================================================");
        line(String.format("  task_create calls:  %d", createCalls));
        line(String.format("  task_update calls:  %d", updateCalls));
        line(String.format("  plan transitions:   %d", transitions));
        line(String.format("  reminders posted:   %d", reminderCount));
        line(String.format("  total tasks:        %d   (done %d, still-active %d)",
                all.size(), done.size(), stillActive.size()));

        nl();
        line("  Checks:");
        line("    [" + check(enoughReminders) + "] >= 2 reminders posted (got " + reminderCount + ")");
        line("    [" + check(bothLaterTurnsHaveReminders)
                + "] turn 2 + turn 3 prompts contained <system-reminder> "
                + "(turn2=" + turn2HasReminder + " turn3=" + turn3HasReminder + ")");
        line("    [" + check(allTasksTerminal)
                + "] all tasks reached a terminal state (still-active=" + stillActive.size() + ")");
        line("    [" + check(turn3Wraps)
                + "] turn 3 response references completion/summary "
                + "(soft check — the 'all-done' reminder steered the agent)");

        nl();
        boolean allPass = enoughReminders && bothLaterTurnsHaveReminders
                && allTasksTerminal && turn3Wraps;
        if (allPass) {
            line("  QUALITY CHECK PASSED");
            line("  -> Reminders fired between turns, the agent saw them, and behaviour changed.");
        } else {
            line("  QUALITY CHECK FAILED");
            line("  -> Inspect the counts above. The reminder mechanism may have worked");
            line("  -> even if the soft 'turn 3 wraps' check missed — the LLM phrasing varies.");
        }
    }

    private static boolean mentionsCompletionOrSummary(String text) {
        if (text == null) return false;
        String t = text.toLowerCase();
        return t.contains("complete") || t.contains("completed")
                || t.contains("summary") || t.contains("summarise") || t.contains("summarize")
                || t.contains("accomplish") || t.contains("wrap")
                || t.contains("finished") || t.contains("conclud");
    }

    private static String check(boolean ok) { return ok ? "PASS" : "FAIL"; }

    private static String systemPrompt() {
        return """
                You are an organised assistant with two tools available — task_create
                and task_update — bound to a task list the user can see.

                Important: messages may contain harness-injected <system-reminder>
                blocks. Treat them as authoritative runtime context: they describe
                state the user expects you to be aware of, and you should follow
                their guidance directly.

                Conventions:
                  * task_create(description, activeForm?) — adds a pending task
                  * task_update(taskId, "start"|"complete"|"cancel", resultNote?) — transitions

                Use the tools — do not just describe what you would do.
                Keep prose responses concise; the value is in the structured task flow.
                """;
    }

    private static void printHeader(String userGoal) {
        nl();
        line("======================================================================");
        line("  SystemReminderChannel — harness-injected runtime context");
        line("======================================================================");
        line("");
        line("  Two channels at play:");
        line("    PlanChannel             — TaskList state (created/started/completed)");
        line("    SystemReminderChannel   — harness->agent nudges, drained per-turn");
        line("");
        line("  The harness ticks between turns, examines TaskList state, and posts");
        line("  reminders. Reminders are drained and prepended to the next user msg.");
        line("");
        line("  User goal:");
        line("    " + truncate(userGoal, 200));
        line("");
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TaskCreateInput {
        public String description;
        public String activeForm;
        public String parentTaskId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TaskUpdateInput {
        public String taskId;
        public String status;
        public String resultNote;
    }
}
