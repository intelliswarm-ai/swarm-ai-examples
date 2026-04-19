package ai.intelliswarm.swarmai.examples.openapi;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.integrations.OpenApiToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * OpenApiToolkit showcase — point the agent at an arbitrary OpenAPI spec and let it
 * discover + invoke operations without any per-API code.
 *
 * <p>Default target: the public Swagger Petstore v3 spec. No API key needed.
 *
 * <p>Run: {@code ./run.sh openapi [spec-url]}
 */
@Component
public class OpenApiClientExample {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiClientExample.class);
    private static final String DEFAULT_SPEC = "https://petstore3.swagger.io/api/v3/openapi.json";

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final OpenApiToolkit openApiToolkit;

    public OpenApiClientExample(ChatClient.Builder chatClientBuilder,
                                ApplicationEventPublisher eventPublisher,
                                OpenApiToolkit openApiToolkit) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.openApiToolkit = openApiToolkit;
    }

    public void run(String... args) {
        String specUrl = args.length > 0 ? args[0] : DEFAULT_SPEC;

        ChatClient chatClient = chatClientBuilder.build();

        Agent integratorAgent = Agent.builder()
            .role("API Integration Specialist")
            .goal("Explore and exercise an OpenAPI-described service: " + specUrl)
            .backstory("You are an API integration specialist. For any REST API described by an " +
                       "OpenAPI spec you (1) call operation='list_operations' to enumerate endpoints, " +
                       "(2) pick a read-only operation to test, (3) call operation='invoke' with the " +
                       "right operationId and parameters. You NEVER hit DELETE/POST on a spec you don't " +
                       "own without explicit permission.")
            .chatClient(chatClient)
            .tools(List.of(openApiToolkit))
            .permissionMode(PermissionLevel.DANGEROUS) // the toolkit itself is DANGEROUS
            .maxTurns(5)
            .verbose(true)
            .build();

        Task task = Task.builder()
            .description("Explore the OpenAPI service at " + specUrl + ": " +
                         "(1) list every operation, (2) pick a safe read-only operation that returns a " +
                         "sensible sample (e.g. a list of pets by status), (3) invoke it, and (4) " +
                         "report back the operation name, the URL you called, the HTTP status, and a " +
                         "1-2 sentence summary of what came back.")
            .expectedOutput("A four-section report: operations enumerated / operation chosen / URL & status / " +
                            "response summary.")
            .agent(integratorAgent)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(integratorAgent)
            .task(task)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("specUrl", specUrl));

        logger.info("");
        logger.info("=== OpenApiToolkit showcase result ===");
        logger.info("{}", result.getFinalOutput());
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("openapi", args) : new String[]{"openapi"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
