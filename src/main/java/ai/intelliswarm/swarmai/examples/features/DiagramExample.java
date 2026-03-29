package ai.intelliswarm.swarmai.examples.features;

import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.state.*;
import ai.intelliswarm.swarmai.task.Task;

/**
 * Demonstrates Phase 5: Mermaid Diagram Generation.
 *
 * <p>Shows how to generate visual workflow diagrams from a CompiledSwarm.
 * The output can be pasted into GitHub, GitLab, or any Mermaid-compatible viewer.
 */
public class DiagramExample {

    public static void main(String[] args) {
        System.out.println("=== Mermaid Diagram Generation Example ===\n");

        // Build a multi-step workflow
        var agent = ai.intelliswarm.swarmai.agent.Agent.builder()
                .role("Analyst")
                .goal("Analyze market data")
                .backstory("Expert market analyst")
                .chatClient(org.mockito.Mockito.mock(
                        org.springframework.ai.chat.client.ChatClient.class))
                .build();

        Task research = Task.builder()
                .id("research")
                .description("Research AI market trends and competitors")
                .agent(agent)
                .build();

        Task analyze = Task.builder()
                .id("analyze")
                .description("Analyze competitive landscape and market positioning")
                .agent(agent)
                .dependsOn(research)
                .build();

        Task review = Task.builder()
                .id("review")
                .description("Human review checkpoint")
                .agent(agent)
                .dependsOn(analyze)
                .build();

        Task report = Task.builder()
                .id("report")
                .description("Generate final investment memo with recommendations")
                .agent(agent)
                .dependsOn(review)
                .build();

        // Compile with interrupt point
        CompiledSwarm swarm = SwarmGraph.create()
                .addAgent(agent)
                .addTask(research)
                .addTask(analyze)
                .addTask(review)
                .addTask(report)
                .process(ProcessType.SEQUENTIAL)
                .interruptBefore("review") // human-in-the-loop
                .compileOrThrow();

        // Generate Mermaid diagram
        String diagram = new MermaidDiagramGenerator().generate(swarm);

        System.out.println("Generated Mermaid diagram (paste into GitHub markdown):\n");
        System.out.println("```mermaid");
        System.out.println(diagram);
        System.out.println("```");

        System.out.println("\nNote: The 'review' node is highlighted with a red border");
        System.out.println("because it has an interruptBefore point configured.");

        System.out.println("\n=== Done ===");
    }
}
