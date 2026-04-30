package ai.intelliswarm.swarmai.examples.customersupportapp;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.Channels;
import ai.intelliswarm.swarmai.state.CompiledSwarm;
import ai.intelliswarm.swarmai.state.StateSchema;
import ai.intelliswarm.swarmai.state.SwarmGraph;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Core application class for the SwarmAI-powered customer support web service.
 *
 * <p>Builds a {@link SwarmGraph}-based routing pipeline at initialization that
 * classifies incoming customer queries and routes them to the appropriate
 * specialist agent (billing, technical, orders, account, or general).
 *
 * <p>Manages per-session conversation history so that multi-turn interactions
 * maintain context.  Custom tools (product search, order management, ticket
 * creation) are provided as inner classes that implement {@link BaseTool} and
 * operate against in-memory data stores.
 *
 * <p>The public {@link #chat(String, String)} method is the single entry point
 * called by {@link CustomerSupportController}.
 */
@Component
@ConditionalOnProperty(prefix = "swarmai.customer-support", name = "enabled", havingValue = "true")
public class CustomerSupportApp {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSupportApp.class);

    // =========================================================================
    // Public records (used by the REST controller)
    // =========================================================================

    public record ChatResult(String response, String category) {}

    public record ConversationMessage(String role, String content, String timestamp) {}

    public record Product(String id, String name, String category,
                          double price, String description, int stock) {}

    public record Order(String orderId, String customerId, String productId,
                        int quantity, double total, String status, Instant createdAt) {}

    public record OrderResult(String orderId, String customerId, String productId,
                              int quantity, double total, String status, String createdAt) {}

    public record Ticket(String ticketId, String customerId, String category,
                         String subject, String description, String status, String createdAt) {}

    // =========================================================================
    // In-memory data stores (static, shared across all tool instances)
    // =========================================================================

    private static final List<Product> PRODUCT_CATALOG = List.of(
            new Product("SWARM-PRO",   "SwarmAI Pro",          "software",  299.00,
                    "Professional multi-agent orchestration platform with up to 10 concurrent agents", 500),
            new Product("SWARM-ENT",   "SwarmAI Enterprise",   "software", 1299.00,
                    "Enterprise-grade platform with unlimited agents, SSO, audit logging, and dedicated support", 200),
            new Product("SWARM-STD",   "SwarmAI Studio",       "software",  149.00,
                    "Visual workflow builder and debugger for SwarmAI pipelines", 800),
            new Product("TRAIN-WKS",   "SwarmAI Workshop",     "training",  499.00,
                    "Two-day hands-on workshop covering agent design, tool creation, and deployment", 50),
            new Product("TRAIN-CERT",  "SwarmAI Certification","training",  799.00,
                    "Certification program with exam covering advanced multi-agent patterns", 100),
            new Product("SUP-BASIC",   "Basic Support",        "support",    99.00,
                    "Email support with 48-hour response time and access to knowledge base", 999),
            new Product("SUP-PREM",    "Premium Support",      "support",   499.00,
                    "Priority email and chat support with 4-hour response SLA and quarterly reviews", 300),
            new Product("SUP-DED",     "Dedicated Engineer",   "support",  2499.00,
                    "Named support engineer dedicated to your account with 1-hour response SLA", 20),
            new Product("SWARM-TEAM",  "SwarmAI Team",         "software",  599.00,
                    "Team collaboration features with shared agent libraries and workflow templates", 400)
    );

    private static final ConcurrentHashMap<String, Order> ORDER_STORE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Ticket> TICKET_STORE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<ConversationMessage>> CONVERSATION_STORE =
            new ConcurrentHashMap<>();

    private static final AtomicLong ORDER_SEQUENCE = new AtomicLong(1000);
    private static final AtomicLong TICKET_SEQUENCE = new AtomicLong(5000);

    // Seed sample orders
    static {
        Instant now = Instant.now();
        ORDER_STORE.put("ORD-1001", new Order("ORD-1001", "CUST-100", "SWARM-PRO",
                2, 598.00, "DELIVERED", now.minusSeconds(86400 * 7)));
        ORDER_STORE.put("ORD-1002", new Order("ORD-1002", "CUST-101", "SWARM-ENT",
                1, 1299.00, "PROCESSING", now.minusSeconds(86400 * 2)));
        ORDER_STORE.put("ORD-1003", new Order("ORD-1003", "CUST-100", "SUP-PREM",
                1, 499.00, "DELIVERED", now.minusSeconds(86400 * 14)));
    }

    // =========================================================================
    // Instance fields
    // =========================================================================

    private final ChatClient chatClient;
    private final CompiledSwarm compiledSwarm;
    private final StateSchema stateSchema;

    // =========================================================================
    // Constructor -- builds the SwarmGraph pipeline
    // =========================================================================

    public CustomerSupportApp(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        logger.info("Initializing CustomerSupportApp -- building SwarmGraph pipeline");

        // -- State schema --
        this.stateSchema = StateSchema.builder()
                .channel("query",                Channels.<String>lastWriteWins())
                .channel("session_id",           Channels.<String>lastWriteWins())
                .channel("category",             Channels.<String>lastWriteWins())
                .channel("response",             Channels.<String>lastWriteWins())
                .channel("conversation_context", Channels.<String>lastWriteWins())
                .allowUndeclaredKeys(true)
                .build();

        // -- Tools --
        ProductSearchTool  productSearch  = new ProductSearchTool();
        ProductDetailsTool productDetails = new ProductDetailsTool();
        OrderLookupTool    orderLookup    = new OrderLookupTool();
        OrderCreateTool    orderCreate    = new OrderCreateTool();
        TicketCreateTool   ticketCreate   = new TicketCreateTool();

        List<BaseTool> orderTools = List.of(productSearch, productDetails, orderLookup, orderCreate);
        List<BaseTool> supportTools = List.of(productSearch, ticketCreate);

        // -- Placeholder agent & task (required for SwarmGraph validation) --
        Agent placeholderAgent = Agent.builder()
                .role("Customer Support Router")
                .goal("Route customer queries to the appropriate specialist")
                .backstory("You route support queries through a classification and specialist pipeline.")
                .chatClient(chatClient)
                .build();

        Task placeholderTask = Task.builder()
                .description("Process customer support query through the routing pipeline")
                .expectedOutput("A resolved support response")
                .agent(placeholderAgent)
                .build();

        // -- Build the graph --
        this.compiledSwarm = SwarmGraph.create()
                .addAgent(placeholderAgent)
                .addTask(placeholderTask)

                .addEdge(SwarmGraph.START, "classify")

                // --- Node: classify the incoming query ---
                .addNode("classify", state -> {
                    String query = state.valueOrDefault("query", "");
                    String context = state.valueOrDefault("conversation_context", "");

                    Agent classifier = Agent.builder()
                            .role("Customer Support Classifier")
                            .goal("Classify customer queries into exactly one support category")
                            .backstory("You are a senior triage specialist who has processed over "
                                    + "50,000 support tickets. You understand the nuances of customer "
                                    + "requests and always respond with a single category keyword. "
                                    + "You consider conversation history when classifying follow-up messages.")
                            .chatClient(chatClient)
                            .build();

                    String description = "Classify the following customer query into exactly one category.\n\n"
                            + "Query: " + query + "\n\n";
                    if (!context.isBlank()) {
                        description += "Conversation context (recent messages):\n" + context + "\n\n";
                    }
                    description += "Categories:\n"
                            + "  BILLING    - payments, charges, invoices, refunds, pricing, subscriptions\n"
                            + "  TECHNICAL  - bugs, errors, outages, performance, integrations, API issues\n"
                            + "  ORDERS     - product inquiries, purchasing, order status, order creation\n"
                            + "  ACCOUNT    - login, password, settings, profile, permissions, access\n"
                            + "  GENERAL    - everything else, general questions, feedback, feature requests\n\n"
                            + "Respond with ONLY the category name, nothing else.";

                    Task task = Task.builder()
                            .description(description)
                            .expectedOutput("One of: BILLING, TECHNICAL, ORDERS, ACCOUNT, GENERAL")
                            .agent(classifier)
                            .build();

                    TaskOutput output = classifier.executeTask(task, List.of());
                    String raw = output.getRawOutput() != null ? output.getRawOutput() : "GENERAL";
                    String category = extractCategory(raw);
                    logger.info("  [classify] Detected category: {} (raw: {})", category, raw.trim());
                    return Map.of("category", category);
                })

                // --- Conditional edge: route by category ---
                .addConditionalEdge("classify", state -> {
                    String category = state.valueOrDefault("category", "GENERAL");
                    String target = switch (category) {
                        case "BILLING"   -> "billing";
                        case "TECHNICAL" -> "technical";
                        case "ORDERS"    -> "orders";
                        case "ACCOUNT"   -> "account";
                        default          -> "general";
                    };
                    logger.info("  [route] classify -> {}", target);
                    return target;
                })

                // --- Node: billing specialist ---
                .addNode("billing", state -> runSpecialist(state,
                        "Billing Specialist",
                        "Resolve billing, payment, and subscription issues with empathy and precision",
                        "You are a billing specialist with 5 years of experience in SaaS subscription "
                                + "management. You know refund policies inside out and always offer a "
                                + "concrete resolution. You work for SwarmAI, a multi-agent AI platform company.",
                        "Handle this billing query:\n\n%s\n\n"
                                + "Conversation context:\n%s\n\n"
                                + "Guidelines:\n"
                                + "1. Acknowledge the customer's concern\n"
                                + "2. Explain what likely happened\n"
                                + "3. Offer a clear resolution or next steps\n"
                                + "4. Include relevant policy information\n"
                                + "5. Be empathetic and professional",
                        List.of()))

                // --- Node: technical support ---
                .addNode("technical", state -> runSpecialist(state,
                        "Technical Support Engineer",
                        "Diagnose and resolve technical issues with SwarmAI products efficiently",
                        "You are a senior technical support engineer with deep knowledge of the SwarmAI "
                                + "platform, including agent orchestration, tool integration, workflow "
                                + "pipelines, and API connectivity. You provide step-by-step solutions.",
                        "Handle this technical query:\n\n%s\n\n"
                                + "Conversation context:\n%s\n\n"
                                + "Guidelines:\n"
                                + "1. Identify the likely root cause\n"
                                + "2. Provide clear troubleshooting steps\n"
                                + "3. Offer a workaround if available\n"
                                + "4. Mention when to escalate to the engineering team\n"
                                + "5. Reference relevant documentation where applicable",
                        supportTools))

                // --- Node: order specialist ---
                .addNode("orders", state -> runSpecialist(state,
                        "Order Specialist",
                        "Help customers browse products, check order status, and place new orders",
                        "You are an order specialist for SwarmAI. You have access to the full product "
                                + "catalog and can look up orders and create new ones. Always use the "
                                + "available tools to look up real data rather than making assumptions. "
                                + "When a customer wants to buy something, use product_search to find "
                                + "the right product, then order_create to place the order.",
                        "Handle this order/product query:\n\n%s\n\n"
                                + "Conversation context:\n%s\n\n"
                                + "Guidelines:\n"
                                + "1. Use product_search to find relevant products\n"
                                + "2. Use product_details for specific product information\n"
                                + "3. Use order_lookup to check existing order status\n"
                                + "4. Use order_create to place new orders when requested\n"
                                + "5. Always confirm details before creating an order",
                        orderTools))

                // --- Node: account manager ---
                .addNode("account", state -> runSpecialist(state,
                        "Account Manager",
                        "Help customers with account access, settings, and security concerns",
                        "You handle login issues, permission changes, account security, and profile "
                                + "management for the SwarmAI platform. You prioritize security while "
                                + "keeping the experience smooth.",
                        "Handle this account query:\n\n%s\n\n"
                                + "Conversation context:\n%s\n\n"
                                + "Guidelines:\n"
                                + "1. Address the customer's concern directly\n"
                                + "2. Explain any security verification steps needed\n"
                                + "3. Provide clear instructions for resolution\n"
                                + "4. Mention relevant security policies\n"
                                + "5. Offer to escalate if needed",
                        List.of()))

                // --- Node: general support ---
                .addNode("general", state -> runSpecialist(state,
                        "General Support Representative",
                        "Handle general inquiries, feature requests, and feedback about SwarmAI",
                        "You are a friendly support representative who handles how-to questions, "
                                + "feature requests, and general feedback for the SwarmAI platform. "
                                + "You point customers to relevant docs and resources.",
                        "Handle this query:\n\n%s\n\n"
                                + "Conversation context:\n%s\n\n"
                                + "Guidelines:\n"
                                + "1. Address the question directly\n"
                                + "2. Point to relevant resources or documentation\n"
                                + "3. Offer additional assistance\n"
                                + "4. Be warm and encouraging\n"
                                + "5. Suggest contacting a specialist if the topic is complex",
                        supportTools))

                // --- All specialists -> END ---
                .addEdge("billing",   SwarmGraph.END)
                .addEdge("technical", SwarmGraph.END)
                .addEdge("orders",    SwarmGraph.END)
                .addEdge("account",   SwarmGraph.END)
                .addEdge("general",   SwarmGraph.END)

                .stateSchema(stateSchema)
                .compileOrThrow();

        logger.info("CustomerSupportApp initialized -- SwarmGraph compiled successfully");
        logger.info("  Product catalog: {} products", PRODUCT_CATALOG.size());
        logger.info("  Seed orders:     {}", ORDER_STORE.size());
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Process a customer message within a conversation session.
     *
     * <ol>
     *   <li>Retrieves or creates conversation history for the session</li>
     *   <li>Builds a context string from the last 10 messages</li>
     *   <li>Executes the SwarmGraph pipeline (classify then specialist)</li>
     *   <li>Stores both user and AI messages in conversation history</li>
     *   <li>Returns the response and detected category</li>
     * </ol>
     */
    public ChatResult chat(String sessionId, String message) {
        logger.info("chat() sessionId={} messageLength={}", sessionId, message.length());

        // 1. Get or create conversation history
        List<ConversationMessage> history = CONVERSATION_STORE.computeIfAbsent(
                sessionId, k -> Collections.synchronizedList(new ArrayList<>()));

        // 2. Build conversation context from last 10 messages
        String conversationContext = buildConversationContext(history);

        // 3. Create initial state and execute the pipeline
        AgentState initialState = AgentState.of(stateSchema, Map.of(
                "query", message,
                "session_id", sessionId,
                "conversation_context", conversationContext
        ));

        SwarmOutput result = compiledSwarm.kickoff(initialState);

        // 4. Extract response and category from the final state
        String response = result.getFinalOutput();
        if (response == null || response.isBlank()) {
            response = "I apologize, but I was unable to process your request. "
                    + "Please try again or contact us directly for assistance.";
        }

        // Extract category from the classify node's output (first short task output)
        String category = "GENERAL";
        if (!result.getTaskOutputs().isEmpty()) {
            // The first task output is from the classifier node
            String classifierOutput = result.getTaskOutputs().get(0).getRawOutput();
            if (classifierOutput != null) {
                category = extractCategory(classifierOutput);
            }
        }

        // 5. Store messages in conversation history
        String now = Instant.now().toString();
        history.add(new ConversationMessage("user", message, now));
        history.add(new ConversationMessage("assistant", response, now));

        logger.info("chat() complete -- category={} responseLength={}", category, response.length());
        return new ChatResult(response, category);
    }

    /**
     * Streaming variant of {@link #chat(String, String)}.
     *
     * <p>Classification still runs synchronously (it returns a single keyword,
     * so streaming buys nothing). The specialist's answer streams token-by-token
     * via {@link Agent#executeTaskStreaming} so an SSE consumer can render the
     * reply as it arrives — exactly like ChatGPT's UX.
     *
     * <p><b>Tool trade-off:</b> Phase-1 streaming is single-turn and does not
     * invoke tools, so the streaming specialist has no access to product /
     * order / ticket tools. Callers that need tool-backed answers (e.g. an
     * order specialist calling {@code order_create}) should keep using the
     * non-streaming {@link #chat(String, String)} endpoint.
     *
     * <p>Conversation history is updated once {@link AgentEvent.AgentFinished}
     * fires, so the persisted assistant message contains the full text — not a
     * partial buffer if the consumer disconnects mid-stream.
     */
    public Flux<AgentEvent> streamChat(String sessionId, String message) {
        logger.info("streamChat() sessionId={} messageLength={}", sessionId, message.length());

        List<ConversationMessage> history = CONVERSATION_STORE.computeIfAbsent(
                sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        String conversationContext = buildConversationContext(history);

        // 1. Synchronous classification — single keyword, not worth streaming.
        String category = classifySync(message, conversationContext);
        logger.info("streamChat() classified -> {}", category);

        // 2. Build a tool-less specialist for the resolved category.
        SpecialistSpec spec = specialistFor(category);
        Agent specialist = Agent.builder()
                .role(spec.role)
                .goal(spec.goal)
                .backstory(spec.backstory)
                .chatClient(chatClient)
                .maxTurns(1)
                .build();

        Task task = Task.builder()
                .description(String.format(spec.descriptionTemplate, message, conversationContext))
                .expectedOutput("A professional, helpful customer support response")
                .agent(specialist)
                .build();

        // 3. Persist the user message immediately. The assistant message is
        //    appended in the doOnNext below once AgentFinished arrives.
        String now = Instant.now().toString();
        history.add(new ConversationMessage("user", message, now));

        return specialist.executeTaskStreaming(task, List.of())
                .doOnNext(evt -> {
                    if (evt instanceof AgentEvent.AgentFinished f) {
                        String raw = f.taskOutput() != null && f.taskOutput().getRawOutput() != null
                                ? f.taskOutput().getRawOutput() : "";
                        history.add(new ConversationMessage(
                                "assistant", raw, Instant.now().toString()));
                        logger.info("streamChat() complete -- category={} responseLength={}",
                                category, raw.length());
                    }
                });
    }

    /**
     * Synchronous keyword classification. Same prompt as the {@code classify}
     * graph node, but inline so the streaming flow doesn't need to round-trip
     * through {@link CompiledSwarm#kickoff}.
     */
    private String classifySync(String query, String context) {
        Agent classifier = Agent.builder()
                .role("Customer Support Classifier")
                .goal("Classify customer queries into exactly one support category")
                .backstory("You are a senior triage specialist who has processed over "
                        + "50,000 support tickets. You always respond with a single "
                        + "category keyword.")
                .chatClient(chatClient)
                .build();

        StringBuilder description = new StringBuilder()
                .append("Classify the following customer query into exactly one category.\n\n")
                .append("Query: ").append(query).append("\n\n");
        if (!context.isBlank()) {
            description.append("Conversation context (recent messages):\n").append(context).append("\n\n");
        }
        description.append("Categories:\n")
                .append("  BILLING    - payments, charges, invoices, refunds, pricing, subscriptions\n")
                .append("  TECHNICAL  - bugs, errors, outages, performance, integrations, API issues\n")
                .append("  ORDERS     - product inquiries, purchasing, order status, order creation\n")
                .append("  ACCOUNT    - login, password, settings, profile, permissions, access\n")
                .append("  GENERAL    - everything else, general questions, feedback, feature requests\n\n")
                .append("Respond with ONLY the category name, nothing else.");

        Task task = Task.builder()
                .description(description.toString())
                .expectedOutput("One of: BILLING, TECHNICAL, ORDERS, ACCOUNT, GENERAL")
                .agent(classifier)
                .build();

        TaskOutput out = classifier.executeTask(task, List.of());
        return extractCategory(out.getRawOutput() != null ? out.getRawOutput() : "GENERAL");
    }

    /** Specialist persona + prompt template, keyed off the classified category. */
    private record SpecialistSpec(String role, String goal, String backstory, String descriptionTemplate) {}

    private SpecialistSpec specialistFor(String category) {
        return switch (category) {
            case "BILLING" -> new SpecialistSpec(
                    "Billing Specialist",
                    "Resolve billing, payment, and subscription issues with empathy and precision",
                    "You are a billing specialist with 5 years of experience in SaaS subscription "
                            + "management. You know refund policies inside out and always offer a "
                            + "concrete resolution. You work for SwarmAI, a multi-agent AI platform company.",
                    "Handle this billing query:\n\n%s\n\nConversation context:\n%s\n\n"
                            + "Guidelines:\n1. Acknowledge the customer's concern\n"
                            + "2. Explain what likely happened\n"
                            + "3. Offer a clear resolution or next steps\n"
                            + "4. Include relevant policy information\n"
                            + "5. Be empathetic and professional");
            case "TECHNICAL" -> new SpecialistSpec(
                    "Technical Support Engineer",
                    "Diagnose and resolve technical issues with SwarmAI products efficiently",
                    "You are a senior technical support engineer with deep knowledge of the SwarmAI "
                            + "platform, including agent orchestration, tool integration, workflow "
                            + "pipelines, and API connectivity. You provide step-by-step solutions.",
                    "Handle this technical query:\n\n%s\n\nConversation context:\n%s\n\n"
                            + "Guidelines:\n1. Identify the likely root cause\n"
                            + "2. Provide clear troubleshooting steps\n"
                            + "3. Offer a workaround if available\n"
                            + "4. Mention when to escalate to the engineering team\n"
                            + "5. Reference relevant documentation where applicable");
            case "ORDERS" -> new SpecialistSpec(
                    "Order Specialist",
                    "Help customers browse products, check order status, and place new orders",
                    "You are an order specialist for SwarmAI. You answer order, product, and "
                            + "purchasing questions clearly and concisely. (Streaming mode: tools "
                            + "are unavailable, so reply with general guidance and refer the "
                            + "customer to the non-streaming endpoint for live order operations.)",
                    "Handle this order/product query:\n\n%s\n\nConversation context:\n%s\n\n"
                            + "Guidelines:\n1. Answer the customer's question directly\n"
                            + "2. Provide relevant product information from your knowledge\n"
                            + "3. If a real-time order operation is needed, advise the customer "
                            + "to call the standard /api/support/chat endpoint\n"
                            + "4. Keep replies concise and structured");
            case "ACCOUNT" -> new SpecialistSpec(
                    "Account Manager",
                    "Help customers with account access, settings, and security concerns",
                    "You handle login issues, permission changes, account security, and profile "
                            + "management for the SwarmAI platform. You prioritize security while "
                            + "keeping the experience smooth.",
                    "Handle this account query:\n\n%s\n\nConversation context:\n%s\n\n"
                            + "Guidelines:\n1. Address the customer's concern directly\n"
                            + "2. Explain any security verification steps needed\n"
                            + "3. Provide clear instructions for resolution\n"
                            + "4. Mention relevant security policies\n"
                            + "5. Offer to escalate if needed");
            default -> new SpecialistSpec(
                    "General Support Representative",
                    "Handle general inquiries, feature requests, and feedback about SwarmAI",
                    "You are a friendly support representative who handles how-to questions, "
                            + "feature requests, and general feedback for the SwarmAI platform. "
                            + "You point customers to relevant docs and resources.",
                    "Handle this query:\n\n%s\n\nConversation context:\n%s\n\n"
                            + "Guidelines:\n1. Address the question directly\n"
                            + "2. Point to relevant resources or documentation\n"
                            + "3. Offer additional assistance\n"
                            + "4. Be warm and encouraging\n"
                            + "5. Suggest contacting a specialist if the topic is complex");
        };
    }

    /**
     * Create an order directly (called by the REST controller for the POST /orders endpoint).
     */
    public OrderResult createOrder(String customerId, String productId, int quantity) {
        Product product = PRODUCT_CATALOG.stream()
                .filter(p -> p.id().equals(productId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        if (quantity > product.stock()) {
            throw new IllegalArgumentException(
                    "Insufficient stock for " + productId + ": requested " + quantity + ", available " + product.stock());
        }

        String orderId = "ORD-" + ORDER_SEQUENCE.incrementAndGet();
        double total = product.price() * quantity;
        Instant now = Instant.now();

        Order order = new Order(orderId, customerId, productId, quantity, total, "CONFIRMED", now);
        ORDER_STORE.put(orderId, order);

        logger.info("Order created: {} -- {} x {} = ${}", orderId, productId, quantity, total);
        return new OrderResult(orderId, customerId, productId, quantity, total, "CONFIRMED", now.toString());
    }

    /**
     * Retrieve conversation history for a session.
     */
    public List<ConversationMessage> getConversation(String sessionId) {
        return CONVERSATION_STORE.getOrDefault(sessionId, List.of());
    }

    /**
     * Retrieve the product catalog.
     */
    public List<Product> getProducts() {
        return PRODUCT_CATALOG;
    }

    /**
     * Retrieve orders for a customer, or all orders if customerId is null.
     */
    public List<Order> getOrders(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return new ArrayList<>(ORDER_STORE.values());
        }
        return ORDER_STORE.values().stream()
                .filter(o -> o.customerId().equals(customerId))
                .collect(Collectors.toList());
    }

    /**
     * Retrieve tickets, optionally filtered by customer ID.
     */
    public List<Ticket> getTickets(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return new ArrayList<>(TICKET_STORE.values());
        }
        return TICKET_STORE.values().stream()
                .filter(t -> t.customerId().equals(customerId))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Builds a conversation context string from the most recent messages.
     * Returns at most the last 10 messages formatted as "role: content".
     */
    private String buildConversationContext(List<ConversationMessage> history) {
        if (history.isEmpty()) {
            return "";
        }
        int start = Math.max(0, history.size() - 10);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < history.size(); i++) {
            ConversationMessage msg = history.get(i);
            sb.append(msg.role()).append(": ").append(msg.content()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Shared helper that creates a specialist agent inline, executes a task,
     * and returns the response as a state update map.
     */
    private Map<String, Object> runSpecialist(AgentState state,
                                              String role, String goal, String backstory,
                                              String descriptionTemplate,
                                              List<BaseTool> tools) {
        String query = state.valueOrDefault("query", "");
        String context = state.valueOrDefault("conversation_context", "");

        Agent.Builder agentBuilder = Agent.builder()
                .role(role)
                .goal(goal)
                .backstory(backstory)
                .chatClient(chatClient);

        if (tools != null && !tools.isEmpty()) {
            agentBuilder.tools(tools);
        }

        Agent agent = agentBuilder.build();

        String description = String.format(descriptionTemplate, query, context);

        Task task = Task.builder()
                .description(description)
                .expectedOutput("A professional, helpful customer support response")
                .agent(agent)
                .build();

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
        if (upper.contains("ORDERS"))    return "ORDERS";
        if (upper.contains("ACCOUNT"))   return "ACCOUNT";
        return "GENERAL";
    }

    // =========================================================================
    // Custom Tools (inner classes implementing BaseTool)
    // =========================================================================

    // ---- 1. ProductSearchTool -----------------------------------------------

    /**
     * Searches the product catalog by keyword query.
     * Matches against product name, category, and description.
     */
    static class ProductSearchTool implements BaseTool {

        @Override
        public String getFunctionName() {
            return "product_search";
        }

        @Override
        public String getDescription() {
            return "Search the SwarmAI product catalog by keyword. Returns matching products "
                    + "with name, price, and description. Use this to help customers find products.";
        }

        @Override
        public Object execute(Map<String, Object> parameters) {
            String query = (String) parameters.getOrDefault("query", "");
            logger.info("  [tool] product_search query=\"{}\"", query);

            if (query.isBlank()) {
                return formatProductList("All Products", PRODUCT_CATALOG);
            }

            String lowerQuery = query.toLowerCase();
            List<Product> matches = PRODUCT_CATALOG.stream()
                    .filter(p -> p.name().toLowerCase().contains(lowerQuery)
                            || p.category().toLowerCase().contains(lowerQuery)
                            || p.description().toLowerCase().contains(lowerQuery)
                            || p.id().toLowerCase().contains(lowerQuery))
                    .collect(Collectors.toList());

            if (matches.isEmpty()) {
                return "No products found matching \"" + query + "\". "
                        + "Available categories: software, training, support.";
            }
            return formatProductList("Search Results for \"" + query + "\"", matches);
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of(
                                    "type", "string",
                                    "description", "Search query to match against product names, categories, and descriptions"
                            )
                    ),
                    "required", List.of("query")
            );
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public String getTriggerWhen() {
            return "Customer asks about products, pricing, what is available, or wants to browse the catalog";
        }

        @Override
        public String getAvoidWhen() {
            return "Customer is asking about an existing order, account settings, or a technical issue";
        }

        @Override
        public String getCategory() {
            return "catalog";
        }

        @Override
        public List<String> getTags() {
            return List.of("product", "search", "catalog", "pricing");
        }

        private String formatProductList(String title, List<Product> products) {
            StringBuilder sb = new StringBuilder(title).append(" (").append(products.size()).append(" items):\n\n");
            for (Product p : products) {
                sb.append("  ID: ").append(p.id())
                        .append(" | ").append(p.name())
                        .append(" | $").append(String.format("%.2f", p.price()))
                        .append(" | Category: ").append(p.category())
                        .append(" | Stock: ").append(p.stock())
                        .append("\n    ").append(p.description())
                        .append("\n\n");
            }
            return sb.toString();
        }
    }

    // ---- 2. ProductDetailsTool ----------------------------------------------

    /**
     * Retrieves full details for a specific product by its ID.
     */
    static class ProductDetailsTool implements BaseTool {

        @Override
        public String getFunctionName() {
            return "product_details";
        }

        @Override
        public String getDescription() {
            return "Get full details for a specific product by its product ID. "
                    + "Returns name, category, price, description, and current stock level.";
        }

        @Override
        public Object execute(Map<String, Object> parameters) {
            String productId = (String) parameters.getOrDefault("product_id", "");
            logger.info("  [tool] product_details productId=\"{}\"", productId);

            Optional<Product> match = PRODUCT_CATALOG.stream()
                    .filter(p -> p.id().equalsIgnoreCase(productId))
                    .findFirst();

            if (match.isEmpty()) {
                return "Product not found: \"" + productId + "\". "
                        + "Use product_search to find valid product IDs.";
            }

            Product p = match.get();
            return String.format("Product Details:\n"
                            + "  ID:          %s\n"
                            + "  Name:        %s\n"
                            + "  Category:    %s\n"
                            + "  Price:       $%.2f\n"
                            + "  Description: %s\n"
                            + "  In Stock:    %d units",
                    p.id(), p.name(), p.category(), p.price(), p.description(), p.stock());
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "product_id", Map.of(
                                    "type", "string",
                                    "description", "The product ID to look up (e.g., SWARM-PRO, SUP-PREM)"
                            )
                    ),
                    "required", List.of("product_id")
            );
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public String getTriggerWhen() {
            return "Customer asks for details about a specific product by name or ID";
        }

        @Override
        public String getAvoidWhen() {
            return "Customer is doing a broad search or asking about orders, not a specific product lookup";
        }

        @Override
        public String getCategory() {
            return "catalog";
        }

        @Override
        public List<String> getTags() {
            return List.of("product", "details", "catalog", "lookup");
        }
    }

    // ---- 3. OrderLookupTool -------------------------------------------------

    /**
     * Looks up all orders for a given customer ID.
     */
    static class OrderLookupTool implements BaseTool {

        @Override
        public String getFunctionName() {
            return "order_lookup";
        }

        @Override
        public String getDescription() {
            return "Look up orders by customer ID. Returns all orders for that customer "
                    + "including order ID, product, quantity, total, status, and creation date.";
        }

        @Override
        public Object execute(Map<String, Object> parameters) {
            String customerId = (String) parameters.getOrDefault("customer_id", "");
            logger.info("  [tool] order_lookup customerId=\"{}\"", customerId);

            List<Order> orders = ORDER_STORE.values().stream()
                    .filter(o -> o.customerId().equalsIgnoreCase(customerId))
                    .sorted(Comparator.comparing(Order::createdAt).reversed())
                    .collect(Collectors.toList());

            if (orders.isEmpty()) {
                return "No orders found for customer \"" + customerId + "\".";
            }

            StringBuilder sb = new StringBuilder("Orders for customer ").append(customerId)
                    .append(" (").append(orders.size()).append(" orders):\n\n");
            for (Order o : orders) {
                sb.append("  Order ID: ").append(o.orderId())
                        .append(" | Product: ").append(o.productId())
                        .append(" | Qty: ").append(o.quantity())
                        .append(" | Total: $").append(String.format("%.2f", o.total()))
                        .append(" | Status: ").append(o.status())
                        .append(" | Date: ").append(o.createdAt())
                        .append("\n");
            }
            return sb.toString();
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "customer_id", Map.of(
                                    "type", "string",
                                    "description", "The customer ID to look up orders for (e.g., CUST-100)"
                            )
                    ),
                    "required", List.of("customer_id")
            );
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public String getTriggerWhen() {
            return "Customer asks about their order status, order history, or a specific order";
        }

        @Override
        public String getAvoidWhen() {
            return "Customer is asking about products or wants to place a new order";
        }

        @Override
        public String getCategory() {
            return "orders";
        }

        @Override
        public List<String> getTags() {
            return List.of("order", "lookup", "status", "history");
        }
    }

    // ---- 4. OrderCreateTool -------------------------------------------------

    /**
     * Creates a new order for a customer.
     * Requires WORKSPACE_WRITE permission since it modifies state.
     */
    static class OrderCreateTool implements BaseTool {

        @Override
        public String getFunctionName() {
            return "order_create";
        }

        @Override
        public String getDescription() {
            return "Create a new order for a customer. Requires customer ID, product ID, and quantity. "
                    + "Validates the product exists and has sufficient stock before creating the order.";
        }

        @Override
        public Object execute(Map<String, Object> parameters) {
            String customerId = (String) parameters.getOrDefault("customer_id", "");
            String productId = (String) parameters.getOrDefault("product_id", "");
            int quantity;
            try {
                Object qtyObj = parameters.getOrDefault("quantity", 1);
                quantity = (qtyObj instanceof Number) ? ((Number) qtyObj).intValue() : Integer.parseInt(qtyObj.toString());
            } catch (NumberFormatException e) {
                return "Error: 'quantity' must be a valid integer.";
            }

            logger.info("  [tool] order_create customerId=\"{}\" productId=\"{}\" qty={}",
                    customerId, productId, quantity);

            if (customerId.isBlank()) {
                return "Error: customer_id is required.";
            }
            if (productId.isBlank()) {
                return "Error: product_id is required.";
            }
            if (quantity <= 0) {
                return "Error: quantity must be greater than zero.";
            }

            Optional<Product> match = PRODUCT_CATALOG.stream()
                    .filter(p -> p.id().equalsIgnoreCase(productId))
                    .findFirst();

            if (match.isEmpty()) {
                return "Error: Product \"" + productId + "\" not found. Use product_search to find valid product IDs.";
            }

            Product product = match.get();
            if (quantity > product.stock()) {
                return "Error: Insufficient stock for " + product.name()
                        + ". Requested: " + quantity + ", Available: " + product.stock() + ".";
            }

            String orderId = "ORD-" + ORDER_SEQUENCE.incrementAndGet();
            double total = product.price() * quantity;
            Instant now = Instant.now();

            Order order = new Order(orderId, customerId, productId, quantity, total, "CONFIRMED", now);
            ORDER_STORE.put(orderId, order);

            return String.format("Order created successfully!\n"
                            + "  Order ID:   %s\n"
                            + "  Customer:   %s\n"
                            + "  Product:    %s (%s)\n"
                            + "  Quantity:   %d\n"
                            + "  Total:      $%.2f\n"
                            + "  Status:     CONFIRMED\n"
                            + "  Created:    %s",
                    orderId, customerId, product.name(), productId, quantity, total, now);
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "customer_id", Map.of(
                                    "type", "string",
                                    "description", "The customer ID placing the order"
                            ),
                            "product_id", Map.of(
                                    "type", "string",
                                    "description", "The product ID to order (e.g., SWARM-PRO)"
                            ),
                            "quantity", Map.of(
                                    "type", "integer",
                                    "description", "Number of units to order"
                            )
                    ),
                    "required", List.of("customer_id", "product_id", "quantity")
            );
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public PermissionLevel getPermissionLevel() {
            return PermissionLevel.WORKSPACE_WRITE;
        }

        @Override
        public String getTriggerWhen() {
            return "Customer wants to place or create a new order, buy a product, or make a purchase";
        }

        @Override
        public String getAvoidWhen() {
            return "Customer is just browsing products or asking about existing orders";
        }

        @Override
        public String getCategory() {
            return "orders";
        }

        @Override
        public List<String> getTags() {
            return List.of("order", "create", "purchase", "checkout");
        }
    }

    // ---- 5. TicketCreateTool ------------------------------------------------

    /**
     * Creates a new support ticket.
     * Requires WORKSPACE_WRITE permission since it modifies state.
     */
    static class TicketCreateTool implements BaseTool {

        @Override
        public String getFunctionName() {
            return "ticket_create";
        }

        @Override
        public String getDescription() {
            return "Create a new support ticket for a customer. Requires customer ID, category, "
                    + "subject, and description. Returns the created ticket ID for reference.";
        }

        @Override
        public Object execute(Map<String, Object> parameters) {
            String customerId = (String) parameters.getOrDefault("customer_id", "");
            String category = (String) parameters.getOrDefault("category", "GENERAL");
            String subject = (String) parameters.getOrDefault("subject", "");
            String description = (String) parameters.getOrDefault("description", "");

            logger.info("  [tool] ticket_create customerId=\"{}\" category=\"{}\" subject=\"{}\"",
                    customerId, category, subject);

            if (customerId.isBlank()) {
                return "Error: customer_id is required.";
            }
            if (subject.isBlank()) {
                return "Error: subject is required.";
            }

            String ticketId = "TKT-" + TICKET_SEQUENCE.incrementAndGet();
            String now = Instant.now().toString();

            Ticket ticket = new Ticket(ticketId, customerId, category.toUpperCase(),
                    subject, description, "OPEN", now);
            TICKET_STORE.put(ticketId, ticket);

            return String.format("Support ticket created successfully!\n"
                            + "  Ticket ID:   %s\n"
                            + "  Customer:    %s\n"
                            + "  Category:    %s\n"
                            + "  Subject:     %s\n"
                            + "  Status:      OPEN\n"
                            + "  Created:     %s\n\n"
                            + "Our team will review your ticket and respond within the applicable SLA.",
                    ticketId, customerId, category.toUpperCase(), subject, now);
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "customer_id", Map.of(
                                    "type", "string",
                                    "description", "The customer ID filing the ticket"
                            ),
                            "category", Map.of(
                                    "type", "string",
                                    "description", "Ticket category: BILLING, TECHNICAL, ACCOUNT, or GENERAL"
                            ),
                            "subject", Map.of(
                                    "type", "string",
                                    "description", "Brief summary of the issue"
                            ),
                            "description", Map.of(
                                    "type", "string",
                                    "description", "Detailed description of the issue or request"
                            )
                    ),
                    "required", List.of("customer_id", "category", "subject", "description")
            );
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public PermissionLevel getPermissionLevel() {
            return PermissionLevel.WORKSPACE_WRITE;
        }

        @Override
        public String getTriggerWhen() {
            return "Customer wants to file a support ticket, report an issue, or escalate a problem";
        }

        @Override
        public String getAvoidWhen() {
            return "Customer's issue can be resolved directly without creating a ticket";
        }

        @Override
        public String getCategory() {
            return "support";
        }

        @Override
        public List<String> getTags() {
            return List.of("ticket", "support", "issue", "escalation");
        }
    }
}
