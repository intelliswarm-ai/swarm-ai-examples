package ai.intelliswarm.swarmai.examples.tasklist;

import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.TaskItem;
import ai.intelliswarm.swarmai.state.plan.TaskList;
import ai.intelliswarm.swarmai.state.plan.TaskListRenderer;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Showcases the {@link TaskList} primitive (1.0.19+) end-to-end with
 * a real LLM. The agent receives two tool callbacks — {@code task_create} and
 * {@code task_update} — bound to a per-run {@link TaskList} on top of an
 * {@link InMemoryPlanChannelAccessor}. As the LLM decomposes a multi-step
 * problem and walks through it, the channel listener renders the live todo list
 * after every transition.
 *
 * <p><b>What this proves:</b> the TaskList isn't just a Java helper — it is a
 * structured surface an LLM can drive directly through tool calls, exactly the
 * way agent self-organisation tools like TodoWrite work. Same channel, same status lifecycle,
 * same renderer. Zero new infrastructure: TaskList lives on the existing
 * {@code EvolvingPlan} channel as a different payload type.
 *
 * <h2>Quality check</h2>
 * After the LLM finishes, the example verifies:
 * <ul>
 *   <li>at least 3 tasks were created (the prompt requires a real breakdown)</li>
 *   <li>at least one task went pending → in-progress → done</li>
 *   <li>no orphaned in-progress tasks remain (everything reached a terminal state)</li>
 * </ul>
 * If any check fails, it prints a clear diagnostic; otherwise it prints
 * {@code QUALITY CHECK PASSED}.
 */
@Component
public class TaskListExample {

    private static final Logger logger = LoggerFactory.getLogger(TaskListExample.class);

    private final ChatClient.Builder chatClientBuilder;

    public TaskListExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        String userPrompt = (args != null && args.length > 0)
                ? String.join(" ", args)
                : "Plan a 3-day trip to Tokyo for a first-time visitor. "
                + "Break it into concrete actionable steps (research, booking, "
                + "itinerary, packing, on-the-ground logistics). Decompose first, "
                + "then walk through each step in order.";

        // ---- 1. Channel + TaskList per run ----
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        TaskList taskList = new TaskList(channel, "agent");
        TaskListRenderer renderer = new TaskListRenderer();

        AtomicInteger transitionCount = new AtomicInteger(0);
        AtomicInteger createCalls = new AtomicInteger(0);
        AtomicInteger updateCalls = new AtomicInteger(0);

        // ---- 2. Listener: print compactly after each transition ----
        channel.addListener((prev, curr) -> {
            int n = transitionCount.incrementAndGet();
            String tag = (prev == null) ? "[+]" : "[" + curr.status() + "]";
            String desc = (curr.payload() instanceof TaskItem ti)
                    ? ti.description()
                    : String.valueOf(curr.payload());
            line(String.format("  %2d %-12s %s", n, tag, truncate(desc, 80)));
        });

        // ---- 3. Tool callbacks bound to this TaskList ----
        Function<TaskCreateInput, String> createFn = input -> {
            createCalls.incrementAndGet();
            if (input == null || input.description == null || input.description.isBlank()) {
                return "Error: description is required.";
            }
            String id;
            String activeForm = blankToNull(input.activeForm);
            if (input.parentTaskId != null && !input.parentTaskId.isBlank()) {
                id = taskList.createSubTask(input.parentTaskId, input.description, activeForm);
            } else if (activeForm != null) {
                id = taskList.createTask(input.description, activeForm);
            } else {
                id = taskList.createTask(input.description);
            }
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
                            blankToFallback(input.resultNote, "cancelled by agent"));
                    default -> {
                        return "Error: status must be one of: start, complete, cancel. Got: '" + input.status + "'";
                    }
                }
            } catch (RuntimeException e) {
                return "Error: " + e.getMessage();
            }
            return "Task " + input.taskId + " -> " + status;
        };

        ToolCallback taskCreateCallback = FunctionToolCallback.builder("task_create", createFn)
                .description(
                        "Add a new pending task to your todo list. "
                                + "Use this to decompose a multi-step problem before working it. "
                                + "Required: description (imperative). "
                                + "Optional: activeForm (present-continuous label shown while in-progress, e.g. 'Researching X'), "
                                + "parentTaskId (id of a parent task for hierarchical breakdowns). "
                                + "Returns the new task id.")
                .inputType(TaskCreateInput.class)
                .build();

        ToolCallback taskUpdateCallback = FunctionToolCallback.builder("task_update", updateFn)
                .description(
                        "Update an existing task's status. "
                                + "Required: taskId (returned from task_create), status (one of 'start', 'complete', 'cancel'). "
                                + "Optional: resultNote (when completing, a short summary of the result; "
                                + "when cancelling, the reason). "
                                + "Lifecycle: pending --start--> in-progress --complete--> done. "
                                + "You may cancel a task from any active state.")
                .inputType(TaskUpdateInput.class)
                .build();

        // ---- 4. Build chat client + send the prompt ----
        ChatClient chatClient = chatClientBuilder.build();

        String systemPrompt = """
                You are an organised assistant that always uses the task_create and task_update
                tools to plan and execute multi-step work.

                Workflow you MUST follow:
                  1. First, call task_create for EACH step of your plan (top-level breakdown,
                     usually 4-7 tasks). Provide a clear description and an activeForm.
                  2. Then, for each task in order: call task_update(taskId, "start"),
                     produce the actual content / answer for that step, and finally call
                     task_update(taskId, "complete", resultNote).
                  3. Do NOT batch all task_creates and task_completes at the end — alternate
                     between starting a task, doing the work, and completing it.
                  4. The user can see the live todo list update in real time as you do this.

                Keep your final prose answer concise. The value is in the structured
                step-by-step execution visible through the tool calls.
                """;

        printHeader(userPrompt);

        long startMs = System.currentTimeMillis();
        String response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .toolCallbacks(taskCreateCallback, taskUpdateCallback)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            logger.error("LLM call failed: {}", e.getMessage(), e);
            return;
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        // ---- 5. Print final state ----
        nl();
        line("======================================================================");
        line("  Final response from the LLM");
        line("======================================================================");
        line(response == null ? "(empty)" : response);

        nl();
        line("======================================================================");
        line("  Final TaskList (rendered)");
        line("======================================================================");
        line(renderer.render(channel.get()));

        // ---- 6. Quality check ----
        nl();
        line("======================================================================");
        line("  Quality check");
        line("======================================================================");
        runQualityCheck(taskList, transitionCount.get(),
                createCalls.get(), updateCalls.get(), elapsedMs);
    }

    private void runQualityCheck(TaskList taskList, int transitions,
                                  int createCalls, int updateCalls, long elapsedMs) {
        List<PlanEntry> all = taskList.allTasks();
        List<PlanEntry> done = taskList.completedTasks();
        List<PlanEntry> inProgress = taskList.inProgressTasks();
        List<PlanEntry> pending = taskList.pendingTasks();
        List<PlanEntry> cancelled = taskList.cancelledTasks();

        line(String.format("  elapsed:           %.2fs", elapsedMs / 1000.0));
        line(String.format("  task_create calls: %d", createCalls));
        line(String.format("  task_update calls: %d", updateCalls));
        line(String.format("  channel transitions: %d", transitions));
        line(String.format("  total tasks:       %d", all.size()));
        line(String.format("  pending / in-progress / done / cancelled: %d / %d / %d / %d",
                pending.size(), inProgress.size(), done.size(), cancelled.size()));

        boolean enoughTasks = all.size() >= 3;
        boolean someCompleted = !done.isEmpty();
        boolean noOrphans = inProgress.isEmpty();

        nl();
        line("  Checks:");
        line("    [" + check(enoughTasks) + "] at least 3 tasks created   (got " + all.size() + ")");
        line("    [" + check(someCompleted) + "] at least 1 task completed   (got " + done.size() + ")");
        line("    [" + check(noOrphans) + "] no orphaned in-progress tasks (got " + inProgress.size() + ")");

        nl();
        if (enoughTasks && someCompleted && noOrphans) {
            line("  QUALITY CHECK PASSED");
            line("  -> The LLM successfully drove the TaskList primitive end-to-end.");
        } else {
            line("  QUALITY CHECK FAILED");
            line("  -> The LLM did not drive the full create -> start -> complete cycle.");
            line("  -> See counts above; consider re-running or strengthening the prompt.");
        }
    }

    private static String check(boolean ok) { return ok ? "PASS" : "FAIL"; }

    private static void printHeader(String userPrompt) {
        nl();
        line("======================================================================");
        line("  TaskList primitive — agent-driven todo list");
        line("======================================================================");
        line("");
        line("  The agent has two tools bound to a TaskList:");
        line("    task_create(description, activeForm?, parentTaskId?)");
        line("    task_update(taskId, status, resultNote?)   status: start|complete|cancel");
        line("");
        line("  Channel transitions stream below as the LLM decomposes the problem");
        line("  and walks the plan. Watch the [PROPOSED] -> [APPROVED] -> [EXECUTED]");
        line("  status flow in real time.");
        line("");
        line("  User goal:");
        line("    " + truncate(userPrompt, 200));
        line("");
        line("  Live channel transitions (n   status      description):");
        line("  -----------------------------------------------------------------");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String blankToFallback(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }

    // =================================================================
    // Tool input shapes — Jackson-deserialised by Spring AI from LLM JSON.
    // Public classes (not records) so Jackson tolerates missing optional
    // fields without strict canonical-constructor invocation.
    // =================================================================

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
