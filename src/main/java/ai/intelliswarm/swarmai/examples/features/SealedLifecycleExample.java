package ai.intelliswarm.swarmai.examples.features;

import ai.intelliswarm.swarmai.state.*;

/**
 * Demonstrates Phase 1B: Sealed SwarmDefinition lifecycle.
 *
 * <p>Shows how SwarmGraph (mutable) → compile() → CompiledSwarm (immutable)
 * enforces a build-then-execute lifecycle with compile-time validation.
 */
public class SealedLifecycleExample {

    public static void main(String[] args) {
        System.out.println("=== Sealed Lifecycle Example ===\n");

        // 1. Compile-time validation catches ALL errors at once
        System.out.println("1. Attempting to compile an invalid graph...");
        CompilationResult result = SwarmGraph.create()
                .process(ai.intelliswarm.swarmai.process.ProcessType.HIERARCHICAL)
                // Missing: agents, tasks, manager agent
                .compile();

        if (!result.isSuccess()) {
            System.out.println("   Compilation failed with " + result.errors().size() + " errors:");
            result.errors().forEach(error ->
                    System.out.println("   - " + error.message()));
        }

        // 2. Pattern matching on sealed interface
        System.out.println("\n2. Pattern matching on SwarmDefinition:");
        SwarmDefinition def = SwarmGraph.create();
        String description = switch (def) {
            case SwarmGraph g -> "Mutable graph with " + g.agents().size() + " agents";
            case CompiledSwarm c -> "Compiled swarm: " + c.getId();
        };
        System.out.println("   " + description);

        // 3. CompilationError sealed hierarchy
        System.out.println("\n3. Error types (sealed hierarchy):");
        CompilationError err1 = new CompilationError.NoAgents();
        CompilationError err2 = new CompilationError.MissingManagerAgent("hierarchical");
        CompilationError err3 = new CompilationError.InvalidDependency("task-2", "ghost-task");

        for (CompilationError err : new CompilationError[]{err1, err2, err3}) {
            String detail = switch (err) {
                case CompilationError.NoAgents na -> "No agents defined";
                case CompilationError.NoTasks nt -> "No tasks defined";
                case CompilationError.MissingManagerAgent mma -> "Missing manager for: " + mma.processType();
                case CompilationError.InvalidDependency id -> "Bad dep: " + id.taskId() + " → " + id.missingDependency();
                case CompilationError.CyclicDependency cd -> "Cycle: " + cd.taskId();
                case CompilationError.TaskWithoutAgent twa -> "No agent: " + twa.taskId();
                case CompilationError.InvalidConfiguration ic -> "Config: " + ic.detail();
            };
            System.out.println("   " + err.getClass().getSimpleName() + ": " + detail);
        }

        System.out.println("\n=== Done ===");
    }
}
