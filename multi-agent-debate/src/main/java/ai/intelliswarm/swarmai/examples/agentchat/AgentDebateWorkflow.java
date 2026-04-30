package ai.intelliswarm.swarmai.examples.agentchat;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
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
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Agent-to-Agent Debate Workflow -- two agents debate, a judge declares the winner.
 *
 * <p>Demonstrates agent-to-agent "chat" plus <b>token-level streaming</b>: each
 * argument and the judge's verdict materialize live in the console as the
 * model emits tokens, instead of appearing as one block at the end of each
 * round. Watching opposing agents argue token-by-token is the killer demo of
 * the streaming feature, so this workflow leans into that.
 *
 * <p>Streaming uses {@link Agent#executeTaskStreaming(Task, List)} per node,
 * subscribes to the {@link AgentEvent.TextDelta} stream, and prints each delta
 * to {@code System.out} with a colored role prefix. The terminal
 * {@link AgentEvent.AgentFinished} carries the full {@link TaskOutput} we
 * persist back into graph state.
 *
 * <p>Phase-1 streaming is single-turn and does not invoke tools, so the
 * web-search fact-checking from the non-streaming variant is intentionally
 * dropped here. Once Phase-2 streaming (tool-call events) lands, restore the
 * tool and the demo will show {@link AgentEvent.ToolCallStart}/{@code End}
 * banners interleaved with the deltas.
 *
 * Graph topology:
 *   [START] -> [proponent] -> [opponent] --(round &lt; 3)--> [proponent]  (loop)
 *                                         --(round &gt;= 3)--> [judge] -> [END]
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
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    // ANSI color codes for the live console demo. Keep these scoped here — not
    // worth pulling in jansi just for this. They no-op on terminals that don't
    // support them.
    private static final String C_RESET    = "[0m";
    private static final String C_PROPONENT = "[32m"; // green
    private static final String C_OPPONENT  = "[31m"; // red
    private static final String C_JUDGE     = "[33m"; // yellow

    @Autowired private LLMJudge judge;

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
        logger.info("AGENT-TO-AGENT DEBATE WORKFLOW (token streaming)");
        logger.info("=".repeat(80));
        logger.info("Proposition: {}", proposition);
        logger.info("Rounds:      {}", MAX_ROUNDS);
        logger.info("Pattern:     Proponent -> Opponent -> [loop or Judge] -> Verdict");
        logger.info("Streaming:   Agent.executeTaskStreaming -> live token output");
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

                    Agent proponent = buildDebaterAgent(
                            "Debate Proponent",
                            "Argue persuasively FOR the proposition using evidence, logic, and rhetorical skill.",
                            "You are a champion debater who always argues in favor of the given "
                                    + "proposition. You build on previous points and directly rebut "
                                    + "your opponent's arguments. Cite specific statistics and quote "
                                    + "recent studies from your training. Keep each argument to 150-250 words.",
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

                    String header = String.format("\n\n%s>>> Round %d - PROPONENT (FOR) <<<%s\n",
                            C_PROPONENT, currentRound, C_RESET);
                    String argument = streamAndCollect(
                            proponent, taskDesc,
                            "A compelling argument FOR the proposition",
                            header, C_PROPONENT);

                    String logEntry = String.format("=== Round %d - PROPONENT (FOR) ===\n%s",
                            currentRound, argument);
                    logger.info("\n  [proponent] Round {} argument complete ({} words)",
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

                    Agent opponent = buildDebaterAgent(
                            "Debate Opponent",
                            "Argue persuasively AGAINST the proposition using evidence, logic, and rhetorical skill.",
                            "You are a champion debater who always argues against the given "
                                    + "proposition. You systematically dismantle your opponent's "
                                    + "arguments and present counter-evidence drawn from your training. "
                                    + "Keep each argument to 150-250 words.",
                            0.7, metrics);

                    String taskDesc = String.format(
                            "Round %d of %d. You are arguing AGAINST the proposition:\n\n"
                            + "\"%s\"\n\n"
                            + "Your opponent's argument FOR the proposition was:\n"
                            + "---\n%s\n---\n\n"
                            + "Rebut their points directly, then advance a new counter-argument.",
                            currentRound, MAX_ROUNDS, prop, proponentArg);

                    String header = String.format("\n\n%s>>> Round %d - OPPONENT (AGAINST) <<<%s\n",
                            C_OPPONENT, currentRound, C_RESET);
                    String argument = streamAndCollect(
                            opponent, taskDesc,
                            "A compelling argument AGAINST the proposition",
                            header, C_OPPONENT);

                    String logEntry = String.format("=== Round %d - OPPONENT (AGAINST) ===\n%s",
                            currentRound, argument);
                    logger.info("\n  [opponent] Round {} argument complete ({} words)",
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

                    Agent judgeAgent = buildAgent(
                            "Debate Judge",
                            "Evaluate debate arguments impartially and declare a winner with clear reasoning",
                            "You are a distinguished debate judge with decades of experience in "
                                    + "competitive debate. You evaluate arguments on the basis of "
                                    + "evidence quality, logical coherence, rebuttal effectiveness, "
                                    + "and rhetorical persuasion. You are strictly neutral and never "
                                    + "let personal views influence your ruling.",
                            0.3, metrics);

                    String taskDesc = String.format(
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
                            MAX_ROUNDS, prop, transcript);

                    String header = String.format("\n\n%s>>> JUDGE'S VERDICT <<<%s\n", C_JUDGE, C_RESET);
                    String verdict = streamAndCollect(
                            judgeAgent, taskDesc,
                            "SCORES, WINNER, and REASONING",
                            header, C_JUDGE);

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

        logger.info("\nStarting debate (tokens will stream live below)...\n");

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

        String finalOutput = result.getFinalOutput();
        if (finalOutput != null && !finalOutput.isEmpty()) {
            logger.info("\nFINAL VERDICT:\n{}", finalOutput);
        }

        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("agent-debate", "Multi-agent debate with judge declaring winner via SwarmGraph", result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - t0,
                3, 3, "SWARM_GRAPH", "multi-agent-debate");
        }

        metrics.report();
    }

    // =========================================================================
    // STREAMING EXECUTION
    // =========================================================================

    /**
     * Run a single-turn task on {@code agent} via {@link Agent#executeTaskStreaming},
     * print each {@link AgentEvent.TextDelta} to {@code System.out} as tokens
     * arrive, and return the fully-accumulated text from the terminal
     * {@link AgentEvent.AgentFinished} (or the partial accumulation in the
     * error case).
     *
     * <p>{@code blockLast(timeout)} is the right primitive here even though
     * we're nominally going reactive — we run inside a SwarmGraph node which
     * expects a synchronous return value, and the per-token side-effect
     * (printing) already happens off the calling thread inside the {@code doOnNext}.
     */
    private String streamAndCollect(Agent agent, String description, String expectedOutput,
                                    String header, String color) {
        Task task = Task.builder()
                .description(description)
                .expectedOutput(expectedOutput)
                .agent(agent).build();

        // Emitting the role banner up-front means even a slow TTFT (time to first
        // token) shows the user *something* — they know which agent is "thinking".
        System.out.print(header);
        System.out.print(color);
        System.out.flush();

        StringBuilder collected = new StringBuilder();

        agent.executeTaskStreaming(task, List.of())
                .doOnNext(evt -> {
                    if (evt instanceof AgentEvent.TextDelta d) {
                        System.out.print(d.text());
                        System.out.flush();
                        collected.append(d.text());
                    } else if (evt instanceof AgentEvent.AgentFinished f) {
                        // Prefer the AgentFinished payload over the local accumulator
                        // since it's authoritative — but they're identical in Phase 1.
                        String raw = f.taskOutput().getRawOutput();
                        if (raw != null && !raw.isEmpty() && collected.length() == 0) {
                            collected.append(raw);
                        }
                    } else if (evt instanceof AgentEvent.AgentError e) {
                        // Errors are surfaced via on-success channel by Agent.executeTaskStreaming.
                        // Print so the demo doesn't go silently wrong.
                        System.out.print(C_RESET);
                        System.out.println();
                        logger.error("  [stream] Agent error: {} - {}", e.exceptionType(), e.message());
                    }
                })
                .blockLast(STREAM_TIMEOUT);

        // Reset color and ensure newline so the next agent's banner starts clean.
        System.out.print(C_RESET);
        System.out.println();
        System.out.flush();

        return collected.toString();
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
                .verbose(false).build();
    }

    /**
     * Build a debater agent. Phase-1 streaming is single-turn and tool-less, so
     * unlike the non-streaming variant of this workflow, no web_search tool is
     * attached. The trade-off is documented at the class level.
     */
    private Agent buildDebaterAgent(String role, String goal, String backstory,
                                    double temp, WorkflowMetricsCollector metrics) {
        return Agent.builder()
                .role(role).goal(goal).backstory(backstory)
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(temp)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(false).build();
    }

    /** IDE entry point. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"agent-debate"});
    }
}
