package ai.intelliswarm.swarmai.examples.features;

import ai.intelliswarm.swarmai.state.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates Phase 4: Hook System.
 *
 * <p>Shows how SwarmHook provides a unified mechanism for cross-cutting
 * concerns like logging, metrics, and governance — replacing fragmented
 * approaches (Spring AOP, decorators, inline code).
 */
public class HookSystemExample {

    public static void main(String[] args) {
        System.out.println("=== Hook System Example ===\n");

        List<String> auditLog = new ArrayList<>();

        // 1. Logging hook — traces workflow lifecycle
        SwarmHook<AgentState> loggingHook = ctx -> {
            String msg = String.format("[%s] %s workflow=%s task=%s",
                    Instant.now(),
                    ctx.hookPoint(),
                    ctx.workflowId(),
                    ctx.getTaskId().orElse("n/a"));
            auditLog.add(msg);
            System.out.println("  LOG: " + msg);
            return ctx.state();
        };

        // 2. Metrics hook — counts executions
        SwarmHook<AgentState> metricsHook = ctx -> {
            long count = ctx.state().valueOrDefault("hookInvocations", 0L);
            return ctx.state().withValue("hookInvocations", count + 1);
        };

        // 3. Error hook — captures failures
        SwarmHook<AgentState> errorHook = ctx -> {
            ctx.getError().ifPresent(err ->
                    System.out.println("  ERROR HOOK: " + err.getMessage()));
            return ctx.state();
        };

        // 4. Simulate hook execution
        System.out.println("Executing hooks at different lifecycle points:\n");

        AgentState state = AgentState.of(Map.of("topic", "AI"));

        // BEFORE_WORKFLOW
        HookContext<AgentState> beforeCtx = HookContext.forWorkflow(
                HookPoint.BEFORE_WORKFLOW, state, "wf-001");
        state = loggingHook.apply(beforeCtx);
        state = metricsHook.apply(new HookContext<>(
                beforeCtx.hookPoint(), state, beforeCtx.workflowId(),
                null, null, Map.of(), null));

        // BEFORE_TASK
        HookContext<AgentState> taskCtx = HookContext.forTask(
                HookPoint.BEFORE_TASK, state, "wf-001", "research-task");
        state = loggingHook.apply(taskCtx);
        state = metricsHook.apply(new HookContext<>(
                taskCtx.hookPoint(), state, taskCtx.workflowId(),
                taskCtx.taskId(), null, Map.of(), null));

        // AFTER_TASK
        HookContext<AgentState> afterTaskCtx = HookContext.forTask(
                HookPoint.AFTER_TASK, state, "wf-001", "research-task");
        state = loggingHook.apply(afterTaskCtx);
        state = metricsHook.apply(new HookContext<>(
                afterTaskCtx.hookPoint(), state, afterTaskCtx.workflowId(),
                afterTaskCtx.taskId(), null, Map.of(), null));

        // ON_ERROR
        HookContext<AgentState> errorCtx = HookContext.forError(
                state, "wf-001", new RuntimeException("LLM timeout"));
        state = errorHook.apply(errorCtx);

        // AFTER_WORKFLOW
        HookContext<AgentState> afterCtx = HookContext.forWorkflow(
                HookPoint.AFTER_WORKFLOW, state, "wf-001");
        state = loggingHook.apply(afterCtx);
        state = metricsHook.apply(new HookContext<>(
                afterCtx.hookPoint(), state, afterCtx.workflowId(),
                null, null, Map.of(), null));

        System.out.println("\nHook invocations: " + state.valueOrDefault("hookInvocations", 0L));
        System.out.println("Audit log entries: " + auditLog.size());

        System.out.println("\nAvailable hook points:");
        for (HookPoint point : HookPoint.values()) {
            System.out.println("  - " + point);
        }

        System.out.println("\n=== Done ===");
    }
}
