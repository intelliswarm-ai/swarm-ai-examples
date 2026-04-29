package ai.intelliswarm.swarmai.examples.typedoutput;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
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
 * Demonstrates typed structured output: the LLM is asked to extract invoice fields
 * from a free-text purchase summary, and SwarmAI parses the response directly into
 * a Java record. No manual JSON parsing in user code.
 *
 * <p>The example shows three calls to {@link Task.Builder#outputType(Class)}, with
 * three target shapes — a flat record, a nested record, and a list-bearing record —
 * to make clear that {@code outputType} works for any Jackson-serialisable type.</p>
 *
 * <p>Run:</p>
 * <pre>
 *   ./typed-structured-output/run.sh
 *   ./run.sh typed-output
 * </pre>
 */
@Component
public class TypedStructuredOutputExample {

    private static final Logger logger = LoggerFactory.getLogger(TypedStructuredOutputExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public TypedStructuredOutputExample(ChatClient.Builder chatClientBuilder,
                                        ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    // ============================================================
    // Target output shapes — declared as records, parsed by the framework.
    // ============================================================

    /** Flat record. Smallest possible typed-output target. */
    public record Invoice(String vendor, double total, String currency, String dueDate) {}

    /** Nested record. Demonstrates that nested types parse cleanly. */
    public record Address(String street, String city, String country) {}
    public record Customer(String name, String email, Address billingAddress) {}

    /** List-bearing record. Demonstrates collection field parsing. */
    public record LineItem(String description, int quantity, double unitPrice) {}
    public record Order(String orderId, List<LineItem> items, double total) {}

    public void run(String... args) {
        ChatClient chatClient = chatClientBuilder.build();
        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("typed-output");
        metrics.start();

        Agent extractor = Agent.builder()
                .role("Document Extraction Specialist")
                .goal("Extract structured fields from unstructured text")
                .backstory("You are a careful, literal extractor. You return only the JSON object the " +
                           "task description asks for — no commentary, no Markdown fencing.")
                .chatClient(chatClient)
                .verbose(true)
                .build();

        // ---- Task 1: flat invoice extraction ----------------------------------
        Task invoiceTask = Task.builder()
                .description("""
                        Extract invoice fields from this text:
                        \"\"\"
                        INVOICE #4521
                        From: Acme Cloud Services
                        Amount: 1,249.00 USD
                        Due: 2026-05-15
                        \"\"\"
                        """)
                .agent(extractor)
                .outputType(Invoice.class)
                .build();

        // ---- Task 2: nested customer extraction --------------------------------
        Task customerTask = Task.builder()
                .description("""
                        Extract the customer record from this snippet:
                        \"\"\"
                        Customer: Jane Smith <jane@acme.test>
                        Billing address: 221B Baker Street, London, UK
                        \"\"\"
                        """)
                .agent(extractor)
                .outputType(Customer.class)
                .build();

        // ---- Task 3: order with line items -------------------------------------
        Task orderTask = Task.builder()
                .description("""
                        Extract the order with all line items from this receipt:
                        \"\"\"
                        Order #ORD-9912
                        - Widget A x3 @ $4.50
                        - Widget B x1 @ $12.00
                        - Shipping x1 @ $7.95
                        Total: $33.45
                        \"\"\"
                        """)
                .agent(extractor)
                .outputType(Order.class)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(extractor)
                .task(invoiceTask)
                .task(customerTask)
                .task(orderTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput result = swarm.kickoff(Map.of());
        metrics.stop();

        logger.info("\n========== TYPED OUTPUT RESULTS ==========");
        for (TaskOutput out : result.getTaskOutputs()) {
            Object typed = out.getTypedOutput();
            if (typed == null) {
                logger.warn("Task {} returned no typed output. Raw: {}",
                        out.getTaskId(), truncate(out.getRawOutput()));
                continue;
            }
            // The cast site is the only place callers need to know the target type.
            // After this line everything is fully type-safe with IDE autocomplete.
            if (typed instanceof Invoice inv) {
                logger.info("Invoice → vendor={}, total={} {}, due={}",
                        inv.vendor(), inv.total(), inv.currency(), inv.dueDate());
            } else if (typed instanceof Customer c) {
                logger.info("Customer → name={}, email={}, city={}",
                        c.name(), c.email(),
                        c.billingAddress() != null ? c.billingAddress().city() : "?");
            } else if (typed instanceof Order o) {
                logger.info("Order {} → {} line items, total=${}",
                        o.orderId(),
                        o.items() != null ? o.items().size() : 0,
                        o.total());
                if (o.items() != null) {
                    for (LineItem li : o.items()) {
                        logger.info("    • {} x{} @ ${}",
                                li.description(), li.quantity(), li.unitPrice());
                    }
                }
            }
        }

        metrics.report();
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    public static void main(String[] args) {
        // CLI workflow — no embedded web server needed. Setting this here lets the
        // example launch from any IDE classpath (IntelliJ, VS Code, Eclipse) without
        // needing the Spring Boot fat-jar's transitive Tomcat. Spring AI's
        // spring-webflux dependency would otherwise fool Spring into trying to
        // start a reactive web server with no server implementation present.
        System.setProperty("spring.main.web-application-type", "none");
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"typed-output"});
    }
}
