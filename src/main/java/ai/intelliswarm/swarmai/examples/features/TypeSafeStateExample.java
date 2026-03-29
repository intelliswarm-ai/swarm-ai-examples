package ai.intelliswarm.swarmai.examples.features;

import ai.intelliswarm.swarmai.state.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Demonstrates Phase 1: Type-Safe State with Channel/Reducer system.
 *
 * <p>Shows how AgentState replaces raw Map<String, Object> with typed access,
 * and how Channels define merge semantics for concurrent state updates.
 */
public class TypeSafeStateExample {

    public static void main(String[] args) {
        System.out.println("=== Type-Safe State Example ===\n");

        // 1. Define a schema with typed channels
        StateSchema schema = StateSchema.builder()
                .channel("messages", Channels.<String>appender())        // accumulates, never overwrites
                .channel("tokenCount", Channels.counter())               // sums values
                .channel("status", Channels.lastWriteWins("PENDING"))    // last write wins, default "PENDING"
                .channel("log", Channels.stringAppender())               // concatenates strings
                .allowUndeclaredKeys(true)                               // accept ad-hoc keys too
                .build();

        // 2. Create state with defaults applied
        AgentState state = AgentState.of(schema, Map.of("topic", "AI agents"));

        System.out.println("Initial state:");
        System.out.println("  topic:      " + state.value("topic").orElse("?"));
        System.out.println("  status:     " + state.value("status").orElse("?"));      // default from schema
        System.out.println("  tokenCount: " + state.value("tokenCount").orElse("?"));  // default 0
        System.out.println("  messages:   " + state.value("messages").orElse("?"));    // default []

        // 3. Simulate concurrent agent updates using channel reducers
        System.out.println("\nAgent 1 writes messages and tokens...");
        state = state.withUpdate(Map.of(
                "messages", List.of("Found 3 papers on LLM agents"),
                "tokenCount", 1500L,
                "status", "RESEARCHING"
        ));

        System.out.println("Agent 2 writes more messages and tokens...");
        state = state.withUpdate(Map.of(
                "messages", List.of("Analyzed competitive landscape"),
                "tokenCount", 2300L,
                "log", "Research phase complete"
        ));

        System.out.println("\nAfter both agents:");
        Optional<List<String>> messages = state.value("messages");
        System.out.println("  messages:   " + messages.orElse(List.of()));  // both accumulated!
        System.out.println("  tokenCount: " + state.valueOrDefault("tokenCount", 0L));  // summed: 3800
        System.out.println("  status:     " + state.value("status").orElse("?"));       // last write wins
        System.out.println("  log:        " + state.value("log").orElse("?"));

        // 4. Type-safe access — no ClassCastException
        long tokens = state.valueOrDefault("tokenCount", 0L);
        String status = state.valueOrDefault("status", "UNKNOWN");
        System.out.println("\nTyped access:");
        System.out.println("  tokens (long): " + tokens);
        System.out.println("  status (String): " + status);

        // 5. Immutability — original state unchanged
        AgentState updated = state.withValue("status", "COMPLETE");
        System.out.println("\nImmutability check:");
        System.out.println("  original status: " + state.value("status").orElse("?"));
        System.out.println("  updated status:  " + updated.value("status").orElse("?"));

        System.out.println("\n=== Done ===");
    }
}
