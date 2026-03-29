package ai.intelliswarm.swarmai.examples.features;

import ai.intelliswarm.swarmai.state.*;

import java.util.Map;

/**
 * Demonstrates Phase 6: Functional Graph API.
 *
 * <p>Shows the lambda-based addNode()/addEdge() API as an alternative to
 * the Agent/Task builder pattern. Also demonstrates conditional routing
 * and the MermaidDiagramGenerator.
 */
public class FunctionalGraphExample {

    public static void main(String[] args) {
        System.out.println("=== Functional Graph API Example ===\n");

        // 1. Build a graph using lambda-based nodes
        SwarmGraph graph = SwarmGraph.create()
                .addNode("research", state -> {
                    String topic = state.valueOrDefault("topic", "AI");
                    System.out.println("  [research] Researching: " + topic);
                    return Map.of(
                            "findings", "Found 5 key trends in " + topic,
                            "iteration", state.valueOrDefault("iteration", 0) + 1
                    );
                })
                .addNode("analyze", state -> {
                    String findings = state.valueOrDefault("findings", "none");
                    System.out.println("  [analyze] Analyzing: " + findings);
                    int iteration = state.valueOrDefault("iteration", 0);
                    boolean qualityOk = iteration >= 2; // needs 2 rounds
                    return Map.of(
                            "analysis", "Trend analysis complete (iteration " + iteration + ")",
                            "quality_ok", qualityOk
                    );
                })
                .addNode("write", state -> {
                    String analysis = state.valueOrDefault("analysis", "none");
                    System.out.println("  [write] Writing report based on: " + analysis);
                    return Map.of("report", "Final report: " + analysis);
                })

                // Define edges
                .addEdge(SwarmGraph.START, "research")
                .addEdge("research", "analyze")
                .addConditionalEdge("analyze", state -> {
                    boolean done = state.valueOrDefault("quality_ok", false);
                    return done ? "write" : "research"; // loop until quality is ok
                })
                .addEdge("write", SwarmGraph.END);

        // 2. Show the graph structure
        System.out.println("Graph structure:");
        System.out.println("  Nodes: " + graph.getNodeActions().keySet());
        System.out.println("  Edges: " + graph.getEdges().size() + " static");
        System.out.println("  Conditional edges: " + graph.getConditionalEdges().size());
        System.out.println("  Has functional nodes: " + graph.hasFunctionalNodes());

        // 3. Simulate execution manually
        System.out.println("\nSimulating execution:");
        AgentState state = AgentState.of(Map.of("topic", "Autonomous AI Agents"));

        try {
            // research → analyze → (loop) → research → analyze → write
            state = executeNode(graph, "research", state);
            state = executeNode(graph, "analyze", state);

            // Check conditional edge
            EdgeAction<AgentState> condition = graph.getConditionalEdges().get("analyze");
            String next = condition.apply(state);
            System.out.println("  [route] analyze → " + next);

            if (!"write".equals(next)) {
                state = executeNode(graph, "research", state);
                state = executeNode(graph, "analyze", state);
                next = condition.apply(state);
                System.out.println("  [route] analyze → " + next);
            }

            state = executeNode(graph, "write", state);
        } catch (Exception e) {
            System.err.println("Execution error: " + e.getMessage());
        }

        System.out.println("\nFinal state:");
        System.out.println("  report: " + state.valueOrDefault("report", "none"));
        System.out.println("  iterations: " + state.valueOrDefault("iteration", 0));
        System.out.println("  quality_ok: " + state.valueOrDefault("quality_ok", false));

        System.out.println("\n=== Done ===");
    }

    private static AgentState executeNode(SwarmGraph graph, String nodeId, AgentState state) throws Exception {
        NodeAction<AgentState> action = graph.getNodeActions().get(nodeId);
        Map<String, Object> update = action.apply(state);
        return state.withUpdate(update);
    }
}
