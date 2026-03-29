package ai.intelliswarm.swarmai.examples.features;

import ai.intelliswarm.swarmai.state.*;

import java.util.Map;

/**
 * Demonstrates Phase 3: Checkpoint Persistence & Resumable Workflows.
 *
 * <p>Shows how checkpoints save workflow state so it can be resumed after
 * failure or interruption.
 */
public class CheckpointExample {

    public static void main(String[] args) {
        System.out.println("=== Checkpoint Persistence Example ===\n");

        InMemoryCheckpointSaver saver = new InMemoryCheckpointSaver();

        // 1. Save checkpoints at each step
        String workflowId = "research-workflow-001";
        AgentState state = AgentState.of(Map.of("topic", "AI agents", "step", 0));

        System.out.println("Simulating workflow with checkpoint saves...");

        // Step 1: Research
        state = state.withUpdate(Map.of("step", 1, "findings", "Found 5 papers"));
        saver.save(Checkpoint.create(workflowId, "research", "analyze", state));
        System.out.println("  [Checkpoint] After research: " + state.value("findings").orElse("?"));

        // Step 2: Analysis
        state = state.withUpdate(Map.of("step", 2, "analysis", "LLM agents are trending"));
        saver.save(Checkpoint.create(workflowId, "analyze", "write", state));
        System.out.println("  [Checkpoint] After analysis: " + state.value("analysis").orElse("?"));

        // Step 3: Writing
        state = state.withUpdate(Map.of("step", 3, "report", "Final report ready"));
        saver.save(Checkpoint.create(workflowId, "write", null, state,
                Map.of("status", "COMPLETED")));
        System.out.println("  [Checkpoint] After writing: " + state.value("report").orElse("?"));

        // 2. Query checkpoints
        System.out.println("\nCheckpoint history:");
        var allCheckpoints = saver.loadAll(workflowId);
        for (var cp : allCheckpoints) {
            System.out.println("  " + cp.completedTaskId() +
                    " → " + (cp.nextTaskId() != null ? cp.nextTaskId() : "END") +
                    " (step " + cp.state().value("step").orElse("?") + ")");
        }

        // 3. Resume from latest checkpoint
        System.out.println("\nResuming from latest checkpoint:");
        var latest = saver.loadLatest(workflowId);
        latest.ifPresent(cp -> {
            System.out.println("  Completed: " + cp.completedTaskId());
            System.out.println("  State keys: " + cp.state().data().keySet());
            System.out.println("  Report: " + cp.state().value("report").orElse("?"));
        });

        // 4. Total checkpoints stored
        System.out.println("\nTotal checkpoints: " + saver.size());

        System.out.println("\n=== Done ===");
    }
}
