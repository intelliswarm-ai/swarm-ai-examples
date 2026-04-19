package ai.intelliswarm.swarmai.examples.wikipedia;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.research.WikipediaTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * WikipediaTool showcase — research agent that writes a grounded mini-biography.
 *
 * <p>Exercises three facets of {@link WikipediaTool} from live agent use:
 * <ul>
 *   <li>{@code operation=search} to find the right article when the title is ambiguous</li>
 *   <li>{@code operation=summary} to fetch the abstract + description</li>
 *   <li>{@code operation=page} to pull additional body text if needed</li>
 * </ul>
 *
 * <p>Run: {@code ./run.sh wikipedia "Ada Lovelace"}  (no API key required)
 */
@Component
public class WikipediaResearchExample {

    private static final Logger logger = LoggerFactory.getLogger(WikipediaResearchExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final WikipediaTool wikipediaTool;

    public WikipediaResearchExample(ChatClient.Builder chatClientBuilder,
                                    ApplicationEventPublisher eventPublisher,
                                    WikipediaTool wikipediaTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.wikipediaTool = wikipediaTool;
    }

    public void run(String... args) {
        String subject = args.length > 0 ? String.join(" ", args) : "Ada Lovelace";
        logger.info("Researching: {}", subject);

        // Preflight: confirm the tool is reachable before running an LLM turn.
        String smoke = wikipediaTool.smokeTest();
        if (smoke != null) {
            logger.error("Wikipedia tool unhealthy: {}", smoke);
            return;
        }

        ChatClient chatClient = chatClientBuilder.build();

        Agent researcher = Agent.builder()
            .role("Wikipedia Researcher")
            .goal("Produce a concise, source-cited mini-biography of " + subject)
            .backstory("You are a careful encyclopedic researcher. You always use the wikipedia tool " +
                       "to retrieve facts — never guess. If the first search is ambiguous, you call it " +
                       "again with a narrower query. You cite the Wikipedia URL at the end.")
            .chatClient(chatClient)
            .tools(List.of(wikipediaTool))
            .maxTurns(4)
            .verbose(true)
            .build();

        Task biography = Task.builder()
            .description("Write a 5-sentence biography of " + subject + ". Every factual claim (dates, " +
                         "roles, achievements) must be grounded in a wikipedia tool call. " +
                         "End with a 'Source:' line pointing at the relevant Wikipedia URL.")
            .expectedOutput("A 5-sentence biography with specific dates/achievements + a Source: URL.")
            .agent(researcher)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(researcher)
            .task(biography)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("subject", subject));

        logger.info("");
        logger.info("=== WikipediaTool showcase result ===");
        logger.info("{}", result.getFinalOutput());
        logger.info("Successful: {}", result.isSuccessful());
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("wikipedia", args) : new String[]{"wikipedia"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
