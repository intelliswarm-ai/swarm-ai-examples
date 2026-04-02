package ai.intelliswarm.swarmai.examples.agentchat;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.Channels;
import ai.intelliswarm.swarmai.state.CompiledSwarm;
import ai.intelliswarm.swarmai.state.StateSchema;
import ai.intelliswarm.swarmai.state.SwarmGraph;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agent-to-Agent Debate Workflow -- two agents debate, a judge declares the winner.
 *
 * Demonstrates agent-to-agent "chat" where autonomous agents read each other's
 * arguments from shared state and produce rebuttals across multiple rounds.
 * After three rounds a neutral judge evaluates the full transcript and renders
 * a verdict.
 *
 * Graph topology:
 *   [START] -> [proponent] -> [opponent] --(round < 3)--> [proponent]  (loop)
 *                                         --(round >= 3)--> [judge] -> [END]
 *
 * State channels:
 *   proposition   (lastWriteWins String) -- the debate topic
 *   debate_log    (appender String)      -- accumulates all arguments
 *   round         (counter Long)         -- tracks completed rounds
 *   proponent_arg (lastWriteWins String) -- latest proponent argument
 *   opponent_arg  (lastWriteWins String) -- latest opponent argument
 *   verdict       (lastWriteWins String) -- final judge ruling
 *
 * Usage: java -jar swarmai-framework.jar agent-debate "AI will create more jobs than it eliminates"
 */
@Component
public class AgentDebateWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(AgentDebateWorkflow.class);
    private static final int MAX_ROUNDS = 3;

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;

    public AgentDebateWorkflow(ChatClient.Builder chatClientBuilder,
                               ApplicationEventPublisher eventPublisher) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
    }

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public void run(String... args) throws Exception {
        String proposition = args.length > 0
                ? String.join(" ", args)
                : "AI will create more jobs than it eliminates";

        logger.info("\n" + "=".repeat(80));
        logger.info("AGENT-TO-AGENT DEBATE WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Proposition: {}", proposition);
        logger.info("Rounds:      {}", MAX_ROUNDS);
        logger.info("Pattern:     Proponent -> Opponent -> [loop or Judge] -> Verdict");
        logger.info("Features:    SwarmGraph | Conditional Edges | Appender Log | Counter");
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("agent-debate");
        metrics.start();

        // ---- State Schema ----

        StateSchema schema = StateSchema.builder()
                .channel("proposition",   Channels.<String>lastWriteWins())
                .channel("debate_log",    Channels.<String>appender())
                .channel("round",         Channels.counter())
                .channel("proponent_arg", Channels.<String>lastWriteWins())
                .channel("opponent_arg",  Channels.<String>lastWriteWins())
                .channel("verdict",       Channels.<String>lastWriteWins())
                .allowUndeclaredKeys(true)
                .build();

        // ---- Build the Graph ----

        Agent placeholderAgent = Agent.builder()
                .role("Debate Coordinator")
                .goal("Coordinate a structured debate between agents")
                .backstory("You orchestrate multi-round debates between autonomous agents.")
                .chatClient(chatClient).build();

        Task placeholderTask = Task.builder()
                .description("Run a structured debate on the given proposition")
                .expectedOutput("A complete debate transcript with a verdict")
                .agent(placeholderAgent).build();

        CompiledSwarm compiled = SwarmGraph.create()
                .addAgent(placeholderAgent)
                .addTask(placeholderTask)
                .addEdge(SwarmGraph.START, "proponent")

                // ---- PROPONENT: argues FOR the proposition ----
                .addNode("proponent", state -> {
                    long currentRound = state.valueOrDefault("round", 0L) + 1;
                    String prop = state.valueOrDefault("proposition", "");
                    String opponentPrev = state.valueOrDefault("opponent_arg", "");

                    Agent proponent = buildAgent(
                            "Debate Proponent",
                            "Argue persuasively FOR the proposition using evidence, logic, and rhetorical skill",
                            "You are a champion debater who always argues in favor of the given "
                                    + "proposition. You build on previous points and directly rebut "
                                    + "your opponent's arguments. You use data, examples, and logical "
                                    + "reasoning. Keep each argument to 150-250 words.",
                            0.7, metrics);

                    String taskDesc;
                    if (opponentPrev.isEmpty()) {
                        taskDesc = String.format(
                                "Round %d of %d. You are arguing FOR the proposition:\n\n"
                                + "\"%s\"\n\n"
                                + "Present your opening argument. Include at least two strong "
                                + "supporting points backed by evidence or reasoning.",
                                currentRound, MAX_ROUNDS, prop);
                    } else {
                        taskDesc = String.format(
                                "Round %d of %d. You are arguing FOR the proposition:\n\n"
                                + "\"%s\"\n\n"
                                + "Your opponent's last argument AGAINST the proposition was:\n"
                                + "---\n%s\n---\n\n"
                                + "Rebut their points directly, then advance a new supporting argument.",
                                currentRound, MAX_ROUNDS, prop, opponentPrev);
                    }

                    TaskOutput out = proponent.executeTask(Task.builder()
                            .description(taskDesc)
                            .expectedOutput("A compelling argument FOR the proposition")
                            .agent(proponent).build(), List.of());

                    String argument = out.getRawOutput();
                    String logEntry = String.format("=== Round %d - PROPONENT (FOR) ===\n%s", currentRound, argument);
                    logger.info("\n  [proponent] Round {} argument ({} words)",
                            currentRound, argument.split("\\s+").length);

                    return Map.of(
                            "proponent_arg", argument,
                            "debate_log", List.of(logEntry),
                            "round", 1L
                    );
                })
                .addEdge("proponent", "opponent")

                // ---- OPPONENT: argues AGAINST the proposition ----
                .addNode("opponent", state -> {
                    long currentRound = state.valueOrDefault("round", 1L);
                    String prop = state.valueOrDefault("proposition", "");
                    String proponentArg = state.valueOrDefault("proponent_arg", "");

                    Agent opponent = buildAgent(
                            "Debate Opponent",
                            "Argue persuasively AGAINST the proposition using evidence, logic, and rhetorical skill",
                            "You are a champion debater who always argues against the given "
                                    + "proposition. You systematically dismantle your opponent's "
                                    + "arguments and present counter-evidence. You use data, examples, "
                                    + "and logical reasoning. Keep each argument to 150-250 words.",
                            0.7, metrics);

                    String taskDesc = String.format(
                            "Round %d of %d. You are arguing AGAINST the proposition:\n\n"
                            + "\"%s\"\n\n"
                            + "Your opponent's argument FOR the proposition was:\n"
                            + "---\n%s\n---\n\n"
                            + "Rebut their points directly, then advance a new counter-argument.",
                            currentRound, MAX_ROUNDS, prop, proponentArg);

                    TaskOutput out = opponent.executeTask(Task.builder()
                            .description(taskDesc)
                            .expectedOutput("A compelling argument AGAINST the proposition")
                            .agent(opponent).build(), List.of());

                    String argument = out.getRawOutput();
                    String logEntry = String.format("=== Round %d - OPPONENT (AGAINST) ===\n%s", currentRound, argument);
                    logger.info("\n  [opponent] Round {} argument ({} words)",
                            currentRound, argument.split("\\s+").length);

                    return Map.of(
                            "opponent_arg", argument,
                            "debate_log", List.of(logEntry),
                            "round", 1L
                    );
                })

                // ---- CONDITIONAL: loop back or proceed to judge ----
                .addConditionalEdge("opponent", state -> {
                    long round = state.valueOrDefault("round", 1L);
                    if (round < MAX_ROUNDS) {
                        logger.info("  [route] Round {}/{} complete -> next round (proponent)",
                                round, MAX_ROUNDS);
                        return "proponent";
                    }
                    logger.info("  [route] All {} rounds complete -> judge", MAX_ROUNDS);
                    return "judge";
                })

                // ---- JUDGE: evaluates the full debate and declares a winner ----
                .addNode("judge", state -> {
                    @SuppressWarnings("unchecked")
                    List<String> fullLog = state.<List<String>>value("debate_log").orElse(List.of());
                    String transcript = String.join("\n\n", fullLog);
                    String prop = state.valueOrDefault("proposition", "");

                    Agent judge = buildAgent(
                            "Debate Judge",
                            "Evaluate debate arguments impartially and declare a winner with clear reasoning",
                            "You are a distinguished debate judge with decades of experience in "
                                    + "competitive debate. You evaluate arguments on the basis of "
                                    + "evidence quality, logical coherence, rebuttal effectiveness, "
                                    + "and rhetorical persuasion. You are strictly neutral and never "
                                    + "let personal views influence your ruling.",
                            0.3, metrics);

                    TaskOutput out = judge.executeTask(Task.builder()
                            .description(String.format(
                                    "You are judging a %d-round debate on the proposition:\n\n"
                                    + "\"%s\"\n\n"
                                    + "=== FULL DEBATE TRANSCRIPT ===\n%s\n"
                                    + "=== END TRANSCRIPT ===\n\n"
                                    + "Evaluate both sides and render your verdict. Your response MUST "
                                    + "follow this exact format:\n\n"
                                    + "SCORES:\n"
                                    + "  Proponent (FOR): [0-100]\n"
                                    + "  Opponent (AGAINST): [0-100]\n\n"
                                    + "WINNER: [PROPONENT or OPPONENT]\n\n"
                                    + "REASONING:\n"
                                    + "[2-3 paragraphs explaining your decision, citing specific "
                                    + "arguments from each side]",
                                    MAX_ROUNDS, prop, transcript))
                            .expectedOutput("SCORES, WINNER, and REASONING")
                            .agent(judge).build(), List.of());

                    String verdict = out.getRawOutput();
                    logger.info("\n  [judge] Verdict rendered ({} words)",
                            verdict.split("\\s+").length);

                    return Map.of(
                            "verdict", verdict,
                            "debate_log", List.of("=== JUDGE'S VERDICT ===\n" + verdict)
                    );
                })
                .addEdge("judge", SwarmGraph.END)
                .stateSchema(schema)
                .compileOrThrow();

        // ---- Execute ----

        AgentState initialState = AgentState.of(schema, Map.of("proposition", proposition));

        logger.info("\nStarting debate...\n");

        long t0 = System.currentTimeMillis();
        SwarmOutput result = compiled.kickoff(initialState);
        long durationMs = System.currentTimeMillis() - t0;

        metrics.stop();

        // ---- Print Results ----

        logger.info("\n" + "=".repeat(80));
        logger.info("DEBATE COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Proposition: {}", proposition);
        logger.info("Rounds:      {}", MAX_ROUNDS);
        logger.info("Successful:  {}", result.isSuccessful());
        logger.info("Duration:    {} ms ({} sec)", durationMs, durationMs / 1000);
        logger.info("Tasks:       {}", result.getTaskOutputs().size());
        logger.info("-".repeat(80));

        // Print the final output which contains the judge's verdict
        String finalOutput = result.getFinalOutput();
        if (finalOutput != null && !finalOutput.isEmpty()) {
            logger.info("\nFINAL VERDICT:\n{}", finalOutput);
        }

        logger.info("-".repeat(80));
        logger.info("FINAL VERDICT:\n\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        metrics.report();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private Agent buildAgent(String role, String goal, String backstory,
                             double temp, WorkflowMetricsCollector metrics) {
        return Agent.builder()
                .role(role).goal(goal).backstory(backstory)
                .chatClient(chatClient).maxTurns(1).temperature(temp)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true).build();
    }

    /** IDE entry point. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"agent-debate"});
    }
}
