package ai.intelliswarm.swarmai.examples.visualization;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.Channels;
import ai.intelliswarm.swarmai.state.CompiledSwarm;
import ai.intelliswarm.swarmai.state.MermaidDiagramGenerator;
import ai.intelliswarm.swarmai.state.StateSchema;
import ai.intelliswarm.swarmai.state.SwarmGraph;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.common.FileWriteTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow Visualization Example -- build multiple SwarmGraph topologies and
 * generate Mermaid diagrams for each, without executing the agents.
 *
 * This example demonstrates how to use SwarmGraph purely for structural
 * visualization. Four common workflow patterns are constructed:
 *
 *   1. SEQUENTIAL   -- START -> research -> analyze -> write -> END
 *   2. PARALLEL     -- START -> [analyst1, analyst2, analyst3] -> synthesize -> END
 *   3. CONDITIONAL  -- START -> classify -> (billing|technical|general) -> respond -> END
 *   4. LOOP         -- START -> draft -> review -> (approve->END | revise->draft)
 *
 * Each graph is compiled, its Mermaid diagram is generated via
 * MermaidDiagramGenerator, and all diagrams are written to a single
 * output/workflow_diagrams.md file. The sequential workflow is also
 * executed to prove the graph is functional.
 *
 * Usage: java -jar swarmai-framework.jar visualization
 */
@Component
public class WorkflowVisualizationExample {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowVisualizationExample.class);

    @Autowired private LLMJudge judge;

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;
    private final FileWriteTool fileWriteTool;

    public WorkflowVisualizationExample(ChatClient.Builder chatClientBuilder,
                                        ApplicationEventPublisher eventPublisher,
                                        FileWriteTool fileWriteTool) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
        this.fileWriteTool = fileWriteTool;
    }

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public void run(String... args) throws Exception {
        logger.info("\n" + "=".repeat(80));
        logger.info("WORKFLOW VISUALIZATION EXAMPLE");
        logger.info("=".repeat(80));
        logger.info("Pattern:  Build graphs -> Generate Mermaid diagrams -> Write to file");
        logger.info("Features: SwarmGraph | MermaidDiagramGenerator | FileWriteTool");
        logger.info("=".repeat(80));

        MermaidDiagramGenerator mermaid = new MermaidDiagramGenerator();

        // Collect all diagrams in insertion order
        Map<String, String> diagrams = new LinkedHashMap<>();

        // ---- 1. Sequential Pipeline ----
        logger.info("\n--- Building Sequential Pipeline ---");
        CompiledSwarm sequential = buildSequentialGraph();
        String seqDiagram = mermaid.generate(sequential);
        diagrams.put("Sequential Pipeline", seqDiagram);
        logger.info("Sequential diagram:\n```mermaid\n{}\n```", seqDiagram);

        // ---- 2. Parallel (Diamond) Pipeline ----
        logger.info("\n--- Building Parallel (Diamond) Pipeline ---");
        CompiledSwarm parallel = buildParallelGraph();
        String parDiagram = mermaid.generate(parallel);
        diagrams.put("Parallel (Diamond) Pipeline", parDiagram);
        logger.info("Parallel diagram:\n```mermaid\n{}\n```", parDiagram);

        // ---- 3. Conditional (Router) Pipeline ----
        logger.info("\n--- Building Conditional (Router) Pipeline ---");
        CompiledSwarm conditional = buildConditionalGraph();
        String condDiagram = mermaid.generate(conditional);
        diagrams.put("Conditional (Router) Pipeline", condDiagram);
        logger.info("Conditional diagram:\n```mermaid\n{}\n```", condDiagram);

        // ---- 4. Loop (Iterative) Pipeline ----
        logger.info("\n--- Building Loop (Iterative) Pipeline ---");
        CompiledSwarm loop = buildLoopGraph();
        String loopDiagram = mermaid.generate(loop);
        diagrams.put("Loop (Iterative) Pipeline", loopDiagram);
        logger.info("Loop diagram:\n```mermaid\n{}\n```", loopDiagram);

        // ---- Write all diagrams to a single markdown file ----
        writeDiagramsToFile(diagrams);

        // ---- Execute the sequential graph to prove it works ----
        logger.info("\n" + "-".repeat(60));
        logger.info("Executing Sequential Pipeline to verify functionality...");
        logger.info("-".repeat(60));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("visualization");
        metrics.start();

        AgentState initialState = AgentState.of(
                sequential.getStateSchema(),
                Map.of("topic", "AI workflow orchestration patterns"));

        long t0 = System.currentTimeMillis();
        SwarmOutput result = sequential.kickoff(initialState);
        long durationMs = System.currentTimeMillis() - t0;

        metrics.stop();

        logger.info("\n" + "=".repeat(80));
        logger.info("SEQUENTIAL EXECUTION COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Successful: {}", result.isSuccessful());
        logger.info("Duration:   {} ms", durationMs);
        logger.info("Tasks:      {}", result.getTaskOutputs().size());
        logger.info("-".repeat(80));
        logger.info("Output:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("visualization", "Build graph topologies and generate Mermaid workflow diagrams", result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - t0,
                1, 4, "SEQUENTIAL", "workflow-visualization-mermaid");
        }

        metrics.report();
    }

    // =========================================================================
    // GRAPH BUILDERS
    // =========================================================================

    /**
     * Sequential: START -> research -> analyze -> write -> END
     */
    private CompiledSwarm buildSequentialGraph() {
        StateSchema schema = StateSchema.builder()
                .channel("topic",    Channels.<String>lastWriteWins())
                .channel("research", Channels.<String>lastWriteWins())
                .channel("analysis", Channels.<String>lastWriteWins())
                .channel("article",  Channels.<String>lastWriteWins())
                .allowUndeclaredKeys(true)
                .build();

        Agent agent = minimalAgent("Pipeline Agent",
                "Execute sequential research-to-writing pipeline");

        Task task = Task.builder()
                .description("Process topic through sequential pipeline")
                .expectedOutput("A written article")
                .agent(agent).build();

        return SwarmGraph.create()
                .addAgent(agent).addTask(task)
                .addEdge(SwarmGraph.START, "research")
                .addNode("research", state -> {
                    TaskOutput out = agent.executeTask(Task.builder()
                            .description("Research the topic: " + state.valueOrDefault("topic", ""))
                            .expectedOutput("Key findings in 2-3 paragraphs")
                            .agent(agent).build(), List.of());
                    return Map.of("research", out.getRawOutput());
                })
                .addEdge("research", "analyze")
                .addNode("analyze", state -> {
                    TaskOutput out = agent.executeTask(Task.builder()
                            .description("Analyze these findings:\n" + state.valueOrDefault("research", ""))
                            .expectedOutput("Analytical summary with insights")
                            .agent(agent).build(), List.of());
                    return Map.of("analysis", out.getRawOutput());
                })
                .addEdge("analyze", "write")
                .addNode("write", state -> {
                    TaskOutput out = agent.executeTask(Task.builder()
                            .description("Write an article based on:\n" + state.valueOrDefault("analysis", ""))
                            .expectedOutput("A polished article in markdown")
                            .agent(agent).build(), List.of());
                    return Map.of("article", out.getRawOutput());
                })
                .addEdge("write", SwarmGraph.END)
                .stateSchema(schema)
                .compileOrThrow();
    }

    /**
     * Parallel (Diamond): START -> [analyst1, analyst2, analyst3] -> synthesize -> END
     */
    private CompiledSwarm buildParallelGraph() {
        StateSchema schema = StateSchema.builder()
                .channel("topic",     Channels.<String>lastWriteWins())
                .channel("market",    Channels.<String>lastWriteWins())
                .channel("technical", Channels.<String>lastWriteWins())
                .channel("financial", Channels.<String>lastWriteWins())
                .channel("synthesis", Channels.<String>lastWriteWins())
                .allowUndeclaredKeys(true)
                .build();

        Agent agent = minimalAgent("Parallel Analyst",
                "Perform multi-perspective analysis");

        Task task = Task.builder()
                .description("Analyze topic from multiple perspectives")
                .expectedOutput("Synthesized multi-perspective report")
                .agent(agent).build();

        return SwarmGraph.create()
                .addAgent(agent).addTask(task)
                // Fan-out: START -> three parallel analysts
                .addEdge(SwarmGraph.START, "analyst_market")
                .addEdge(SwarmGraph.START, "analyst_technical")
                .addEdge(SwarmGraph.START, "analyst_financial")
                .addNode("analyst_market", state ->
                        Map.of("market", "Market analysis placeholder"))
                .addNode("analyst_technical", state ->
                        Map.of("technical", "Technical analysis placeholder"))
                .addNode("analyst_financial", state ->
                        Map.of("financial", "Financial analysis placeholder"))
                // Fan-in: all three -> synthesize
                .addEdge("analyst_market", "synthesize")
                .addEdge("analyst_technical", "synthesize")
                .addEdge("analyst_financial", "synthesize")
                .addNode("synthesize", state ->
                        Map.of("synthesis", "Synthesized report placeholder"))
                .addEdge("synthesize", SwarmGraph.END)
                .stateSchema(schema)
                .compileOrThrow();
    }

    /**
     * Conditional (Router): START -> classify -> (billing|technical|general) -> respond -> END
     */
    private CompiledSwarm buildConditionalGraph() {
        StateSchema schema = StateSchema.builder()
                .channel("query",    Channels.<String>lastWriteWins())
                .channel("category", Channels.<String>lastWriteWins())
                .channel("response", Channels.<String>lastWriteWins())
                .allowUndeclaredKeys(true)
                .build();

        Agent agent = minimalAgent("Support Router",
                "Route and handle customer queries");

        Task task = Task.builder()
                .description("Route query to appropriate handler")
                .expectedOutput("Resolved response")
                .agent(agent).build();

        return SwarmGraph.create()
                .addAgent(agent).addTask(task)
                .addEdge(SwarmGraph.START, "classify")
                .addNode("classify", state ->
                        Map.of("category", "BILLING"))
                .addConditionalEdge("classify", state -> {
                    String cat = state.valueOrDefault("category", "GENERAL");
                    return switch (cat) {
                        case "BILLING"   -> "billing";
                        case "TECHNICAL" -> "technical";
                        default          -> "general";
                    };
                })
                .addNode("billing", state ->
                        Map.of("response", "Billing response placeholder"))
                .addNode("technical", state ->
                        Map.of("response", "Technical response placeholder"))
                .addNode("general", state ->
                        Map.of("response", "General response placeholder"))
                .addEdge("billing",   "respond")
                .addEdge("technical", "respond")
                .addEdge("general",   "respond")
                .addNode("respond", state ->
                        Map.of("response", "Final response: " + state.valueOrDefault("response", "")))
                .addEdge("respond", SwarmGraph.END)
                .stateSchema(schema)
                .compileOrThrow();
    }

    /**
     * Loop (Iterative): START -> draft -> review -> (approve->END | revise->draft)
     */
    private CompiledSwarm buildLoopGraph() {
        StateSchema schema = StateSchema.builder()
                .channel("content",   Channels.<String>lastWriteWins())
                .channel("feedback",  Channels.<String>lastWriteWins())
                .channel("approved",  Channels.<Boolean>lastWriteWins())
                .channel("iteration", Channels.counter())
                .allowUndeclaredKeys(true)
                .build();

        Agent agent = minimalAgent("Iterative Writer",
                "Draft and revise content through review loops");

        Task task = Task.builder()
                .description("Create content through iterative refinement")
                .expectedOutput("Approved final content")
                .agent(agent).build();

        return SwarmGraph.create()
                .addAgent(agent).addTask(task)
                .addEdge(SwarmGraph.START, "draft")
                .addNode("draft", state ->
                        Map.of("content", "Draft content iteration " + state.valueOrDefault("iteration", 0),
                               "iteration", 1L))
                .addEdge("draft", "review")
                .addNode("review", state -> {
                    int iter = state.valueOrDefault("iteration", 1);
                    boolean approve = iter >= 3;
                    return Map.of("approved", approve,
                                  "feedback", approve ? "Approved" : "Needs revision");
                })
                .addConditionalEdge("review", state -> {
                    boolean approved = state.valueOrDefault("approved", false);
                    return approved ? SwarmGraph.END : "revise";
                })
                .addNode("revise", state ->
                        Map.of("content", "Revised: " + state.valueOrDefault("feedback", ""),
                               "iteration", 1L))
                .addEdge("revise", "review")
                .stateSchema(schema)
                .compileOrThrow();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /** Creates a minimal agent with just enough fields to pass compilation. */
    private Agent minimalAgent(String role, String goal) {
        return Agent.builder()
                .role(role)
                .goal(goal)
                .backstory("You are a specialized agent in the " + role + " role.")
                .chatClient(chatClient)
                .build();
    }

    /** Writes all diagrams to output/workflow_diagrams.md as a single markdown file. */
    private void writeDiagramsToFile(Map<String, String> diagrams) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# SwarmAI Workflow Diagrams\n\n");
        sb.append("Generated by WorkflowVisualizationExample. Each section shows a different\n");
        sb.append("workflow topology built with SwarmGraph and rendered as a Mermaid diagram.\n\n");

        int index = 1;
        for (Map.Entry<String, String> entry : diagrams.entrySet()) {
            sb.append("## ").append(index++).append(". ").append(entry.getKey()).append("\n\n");
            sb.append("```mermaid\n");
            sb.append(entry.getValue()).append("\n");
            sb.append("```\n\n");
        }

        sb.append("---\n\n");
        sb.append("Paste any diagram into https://mermaid.live to render it interactively.\n");

        new File("output").mkdirs();
        Path outputPath = Path.of("output", "workflow_diagrams.md");
        Files.writeString(outputPath, sb.toString());
        logger.info("\nAll diagrams written to: {}", outputPath.toAbsolutePath());
    }

    /** IDE entry point. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"visualization"});
    }
}
