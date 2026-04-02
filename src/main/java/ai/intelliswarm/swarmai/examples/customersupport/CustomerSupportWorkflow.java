package ai.intelliswarm.swarmai.examples.customersupport;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Customer Support Triage -- routing + handoff via SwarmGraph conditional edges.
 *
 * A Triage Agent classifies queries, routes to the right specialist, then a
 * quality gate either approves the response or escalates to a Senior Agent.
 *
 *   [START] -> [classify] --(conditional)--> [billing|technical|account|general]
 *                                                        |
 *                                                  [satisfaction]
 *                                                   /          \
 *                                                [END]      [escalate] -> [END]
 *
 * Usage: docker compose -f docker-compose.run.yml run --rm customer-support "I was charged twice"
 */
@Component
public class CustomerSupportWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSupportWorkflow.class);

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;

    public CustomerSupportWorkflow(ChatClient.Builder chatClientBuilder,
                                   ApplicationEventPublisher eventPublisher) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
    }

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public void run(String... args) throws Exception {
        String query = args.length > 0
                ? String.join(" ", args)
                : "I was charged twice for my subscription last month and I need a refund";

        logger.info("\n" + "=".repeat(80));
        logger.info("CUSTOMER SUPPORT TRIAGE WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Query:    {}", query);
        logger.info("Pattern:  Classification -> Routing -> Specialist Handoff");
        logger.info("Features: SwarmGraph | Conditional Edges | Quality Gate");
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("customer-support");
        metrics.start();

        // ---- State Schema ----

        StateSchema schema = StateSchema.builder()
                .channel("query",     Channels.<String>lastWriteWins())
                .channel("category",  Channels.<String>lastWriteWins())
                .channel("response",  Channels.<String>lastWriteWins())
                .channel("escalated", Channels.<Boolean>lastWriteWins())
                .allowUndeclaredKeys(true)
                .build();

        // ---- Build the Graph ----

        // A placeholder agent and task are required to pass SwarmGraph validation.
        // The actual work is done inside addNode lambdas using inline agents.
        Agent placeholderAgent = Agent.builder()
                .role("Support Router")
                .goal("Route customer queries to appropriate specialists")
                .backstory("You route support queries through a triage pipeline.")
                .chatClient(chatClient)
                .build();

        Task placeholderTask = Task.builder()
                .description("Process the customer support query through the triage pipeline")
                .expectedOutput("A resolved support response")
                .agent(placeholderAgent)
                .build();

        CompiledSwarm compiled = SwarmGraph.create()
                .addAgent(placeholderAgent)
                .addTask(placeholderTask)

                .addEdge(SwarmGraph.START, "classify")

                // Node: classify the incoming query
                .addNode("classify", state -> {
                    Agent classifier = Agent.builder()
                            .role("Customer Support Classifier")
                            .goal("Classify customer queries into exactly one category")
                            .backstory("You are a senior triage specialist who has processed over "
                                    + "50,000 support tickets. You always respond with a single "
                                    + "category keyword.")
                            .chatClient(chatClient).build();

                    Task task = Task.builder()
                            .description("Classify this query into one category.\n\nQuery: "
                                    + state.valueOrDefault("query", "") + "\n\nCategories:\n"
                                    + "  BILLING    - payments, charges, invoices, refunds, pricing\n"
                                    + "  TECHNICAL  - bugs, errors, outages, performance, integrations\n"
                                    + "  ACCOUNT    - login, password, settings, profile, permissions\n"
                                    + "  GENERAL    - everything else\n\n"
                                    + "Respond with ONLY the category name.")
                            .expectedOutput("One of: BILLING, TECHNICAL, ACCOUNT, GENERAL")
                            .agent(classifier).build();

                    TaskOutput output = classifier.executeTask(task, List.of());
                    String category = extractCategory(output.getRawOutput());
                    logger.info("  [classify] Detected category: {}", category);
                    return Map.of("category", category);
                })

                // Conditional edge: route by category
                .addConditionalEdge("classify", state -> {
                    String category = state.valueOrDefault("category", "GENERAL");
                    logger.info("  [route] classify -> {}", category.toLowerCase());
                    return switch (category) {
                        case "BILLING"   -> "billing";
                        case "TECHNICAL" -> "technical";
                        case "ACCOUNT"   -> "account";
                        default          -> "general";
                    };
                })

                // Node: billing specialist
                .addNode("billing", state -> runSpecialist(state, "Billing Specialist",
                        "Resolve billing and payment issues with empathy and precision",
                        "You are a billing specialist with 5 years in SaaS subscription management. "
                                + "You know refund policies inside out and always offer a concrete resolution.",
                        "Handle this billing query:\n\n" + state.valueOrDefault("query", "") + "\n\n"
                                + "1. Acknowledge the issue\n2. Explain what likely happened\n"
                                + "3. Offer a clear resolution\n4. Include relevant policy info"))

                // Node: technical support
                .addNode("technical", state -> runSpecialist(state, "Technical Support Engineer",
                        "Diagnose and resolve technical issues efficiently",
                        "You are a senior technical support engineer with deep knowledge of web apps, "
                                + "APIs, and cloud infrastructure. You provide step-by-step solutions.",
                        "Handle this technical query:\n\n" + state.valueOrDefault("query", "") + "\n\n"
                                + "1. Identify the likely root cause\n2. Provide troubleshooting steps\n"
                                + "3. Offer a workaround\n4. Mention when to escalate to engineering"))

                // Node: account manager
                .addNode("account", state -> runSpecialist(state, "Account Manager",
                        "Help customers with account access, settings, and management",
                        "You handle login issues, permission changes, and account security. "
                                + "You prioritize security while keeping the experience smooth.",
                        "Handle this account query:\n\n" + state.valueOrDefault("query", "") + "\n\n"
                                + "1. Address the concern\n2. Explain security verification needed\n"
                                + "3. Provide clear instructions\n4. Mention relevant policies"))

                // Node: general support
                .addNode("general", state -> runSpecialist(state, "General Support Representative",
                        "Handle general inquiries, feature requests, and feedback",
                        "You are a friendly support rep who handles how-to questions and feature "
                                + "requests. You point customers to relevant docs and resources.",
                        "Handle this query:\n\n" + state.valueOrDefault("query", "") + "\n\n"
                                + "1. Address the question\n2. Point to relevant resources\n"
                                + "3. Offer additional assistance\n4. Be warm and encouraging"))

                // All specialists converge to satisfaction check
                .addEdge("billing",   "satisfaction")
                .addEdge("technical", "satisfaction")
                .addEdge("account",   "satisfaction")
                .addEdge("general",   "satisfaction")

                // Node: satisfaction quality gate
                .addNode("satisfaction", state -> {
                    Agent qa = Agent.builder()
                            .role("Quality Assurance Analyst")
                            .goal("Evaluate whether a support response adequately addresses the query")
                            .backstory("You review support interactions for quality. You respond with "
                                    + "APPROVED if the response is good, or ESCALATE if it needs senior review.")
                            .chatClient(chatClient).build();

                    Task task = Task.builder()
                            .description("Evaluate this interaction:\n\n"
                                    + "QUERY: " + state.valueOrDefault("query", "") + "\n\n"
                                    + "RESPONSE: " + state.valueOrDefault("response", "") + "\n\n"
                                    + "Criteria: addresses issue, empathetic tone, concrete resolution, accurate.\n"
                                    + "Respond with APPROVED or ESCALATE followed by a brief reason.")
                            .expectedOutput("APPROVED or ESCALATE with reason")
                            .agent(qa).build();

                    TaskOutput output = qa.executeTask(task, List.of());
                    String raw = output.getRawOutput() != null ? output.getRawOutput() : "";
                    boolean needsEscalation = raw.toUpperCase().contains("ESCALATE");
                    logger.info("  [satisfaction] Verdict: {}", needsEscalation ? "ESCALATE" : "APPROVED");
                    return Map.of("escalated", needsEscalation);
                })

                // Conditional edge: approve or escalate
                .addConditionalEdge("satisfaction", state -> {
                    boolean escalated = state.valueOrDefault("escalated", false);
                    String next = escalated ? "escalate" : SwarmGraph.END;
                    logger.info("  [route] satisfaction -> {}", next);
                    return next;
                })

                // Node: senior escalation
                .addNode("escalate", state -> {
                    Agent senior = Agent.builder()
                            .role("Senior Support Director")
                            .goal("Provide escalated support with authority to make exceptions")
                            .backstory("You are a senior director with full authority to issue refunds, "
                                    + "grant credits, and override policies. You handle the cases that "
                                    + "frontline agents cannot resolve.")
                            .chatClient(chatClient).build();

                    Task task = Task.builder()
                            .description("Escalated query -- the initial response was insufficient.\n\n"
                                    + "QUERY: " + state.valueOrDefault("query", "") + "\n\n"
                                    + "PREVIOUS RESPONSE: " + state.valueOrDefault("response", "") + "\n\n"
                                    + "Provide a superior response:\n"
                                    + "1. Apologize for the experience\n2. Take ownership\n"
                                    + "3. Give a definitive resolution\n4. Offer goodwill compensation\n"
                                    + "5. Ensure the customer feels valued")
                            .expectedOutput("A senior-level escalated support response")
                            .agent(senior).build();

                    TaskOutput output = senior.executeTask(task, List.of());
                    String raw = output.getRawOutput() != null ? output.getRawOutput() : "";
                    logger.info("  [escalate] Senior response ({} chars)", raw.length());
                    return Map.of("response", raw, "escalated", true);
                })

                .addEdge("escalate", SwarmGraph.END)
                .stateSchema(schema)
                .compileOrThrow();

        // ---- Execute ----

        AgentState initialState = AgentState.of(schema, Map.of("query", query));

        logger.info("\n" + "-".repeat(60));
        logger.info("Executing support triage pipeline...");
        logger.info("-".repeat(60));

        long startTime = System.currentTimeMillis();
        SwarmOutput result = compiled.kickoff(initialState);
        long durationMs = System.currentTimeMillis() - startTime;

        metrics.stop();

        // ---- Results ----

        logger.info("\n" + "=".repeat(80));
        logger.info("CUSTOMER SUPPORT TRIAGE COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Query:      {}", query);
        logger.info("Successful: {}", result.isSuccessful());
        logger.info("Duration:   {} ms", durationMs);
        logger.info("Tasks:      {}", result.getTaskOutputs().size());
        logger.info("-".repeat(80));
        logger.info("Response:\n\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        metrics.report();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Creates a specialist agent inline, executes a task, and returns the response.
     * Shared by all four specialist nodes to eliminate repetition.
     */
    private Map<String, Object> runSpecialist(AgentState state,
                                              String role, String goal,
                                              String backstory, String description) {
        Agent agent = Agent.builder()
                .role(role).goal(goal).backstory(backstory)
                .chatClient(chatClient).build();

        Task task = Task.builder()
                .description(description)
                .expectedOutput("A professional support response")
                .agent(agent).build();

        TaskOutput output = agent.executeTask(task, List.of());
        String raw = output.getRawOutput() != null ? output.getRawOutput() : "";
        logger.info("  [{}] Response generated ({} chars)",
                role.toLowerCase().split(" ")[0], raw.length());
        return Map.of("response", raw);
    }

    /**
     * Extracts a category keyword from the classifier's raw output.
     * Handles variations like "BILLING", "Category: BILLING", "The category is billing."
     */
    private static String extractCategory(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return "GENERAL";
        String upper = rawOutput.toUpperCase().trim();
        if (upper.contains("BILLING"))   return "BILLING";
        if (upper.contains("TECHNICAL")) return "TECHNICAL";
        if (upper.contains("ACCOUNT"))   return "ACCOUNT";
        return "GENERAL";
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"customer-support"});
    }
}
