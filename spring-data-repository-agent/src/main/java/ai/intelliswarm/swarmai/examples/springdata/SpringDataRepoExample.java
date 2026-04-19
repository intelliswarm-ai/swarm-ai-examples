package ai.intelliswarm.swarmai.examples.springdata;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.data.repository.SpringDataRepositoryTool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SpringDataRepositoryTool showcase — seed an embedded H2 database via a real JpaRepository,
 * then let an agent query it reflectively through the tool.
 *
 * <p>The {@link ai.intelliswarm.swarmai.examples.springdata.CustomerRepository} bean picks up
 * automatically because this module is on the Spring component scan.
 *
 * <p>Run: {@code ./run.sh spring-data "show top customers by lifetime spend"}
 */
@Component
@ConditionalOnProperty(name = "swarmai.examples.spring-data.enabled", havingValue = "true")
public class SpringDataRepoExample {

    private static final Logger logger = LoggerFactory.getLogger(SpringDataRepoExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final SpringDataRepositoryTool repositoryTool;
    private final CustomerRepository customerRepository;
    private final ApplicationContext applicationContext;

    public SpringDataRepoExample(ChatClient.Builder chatClientBuilder,
                                 ApplicationEventPublisher eventPublisher,
                                 SpringDataRepositoryTool repositoryTool,
                                 CustomerRepository customerRepository,
                                 ApplicationContext applicationContext) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.repositoryTool = repositoryTool;
        this.customerRepository = customerRepository;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void seed() {
        if (customerRepository.count() > 0) return;
        customerRepository.save(new Customer(null, "alice@example.com", "Alice", 12_500, "ENTERPRISE"));
        customerRepository.save(new Customer(null, "bob@example.com",   "Bob",    850, "PRO"));
        customerRepository.save(new Customer(null, "carol@example.com", "Carol",    0, "FREE"));
        customerRepository.save(new Customer(null, "dave@example.com",  "Dave",  3_200, "PRO"));
        logger.info("Seeded {} customers into H2", customerRepository.count());
    }

    public void run(String... args) {
        String question = args.length > 0 ? String.join(" ", args)
            : "Which customers are on the ENTERPRISE tier? Also list anyone with lifetime spend under $1,000.";

        ChatClient chatClient = chatClientBuilder.build();

        Agent domainAgent = Agent.builder()
            .role("Domain Query Agent")
            .goal("Answer questions about the application's customers by querying the repository tool")
            .backstory("You are a domain-model assistant. You use the repo_query tool: first " +
                       "operation='list_repositories' to see what's available, then 'list_methods' to " +
                       "see what queries the CustomerRepository exposes, then 'invoke' with the right " +
                       "method + args. You NEVER guess data — every answer is grounded in a real " +
                       "repository call. Always include the customer id in your output so the reader " +
                       "can verify.")
            .chatClient(chatClient)
            .tools(List.of(repositoryTool))
            .permissionMode(PermissionLevel.DANGEROUS) // the tool is DANGEROUS — domain leakage risk
            .maxTurns(6)
            .verbose(true)
            .build();

        Task task = Task.builder()
            .description("Use the repo_query tool to answer this business question about customers: " +
                         question + ". Every claim about customer data must be grounded in a specific " +
                         "repository method invocation. Show the method name and args you called.")
            .expectedOutput("An answer with cited repository calls (method + args) and the returned " +
                            "customer rows.")
            .agent(domainAgent)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(domainAgent)
            .task(task)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("question", question));

        logger.info("");
        logger.info("=== SpringDataRepositoryTool showcase result ===");
        logger.info("{}", result.getFinalOutput());
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("spring-data", args) : new String[]{"spring-data"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
