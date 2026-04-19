package ai.intelliswarm.swarmai.examples.notion;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.productivity.NotionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * NotionTool showcase — a knowledge-base assistant searches the user's Notion workspace,
 * retrieves a page, and returns a grounded answer citing the page URL.
 *
 * <p>Run: {@code ./run.sh notion "Q2 goals"}
 */
@Component
public class NotionWorkspaceExample {

    private static final Logger logger = LoggerFactory.getLogger(NotionWorkspaceExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final NotionTool notionTool;

    public NotionWorkspaceExample(ChatClient.Builder chatClientBuilder,
                                  ApplicationEventPublisher eventPublisher,
                                  NotionTool notionTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.notionTool = notionTool;
    }

    public void run(String... args) {
        String query = args.length > 0 ? String.join(" ", args) : "goals";

        String smoke = notionTool.smokeTest();
        if (smoke != null) {
            logger.error("NotionTool unhealthy: {}", smoke);
            logger.error("Set NOTION_TOKEN env var AND share at least one page with the integration.");
            return;
        }

        ChatClient chatClient = chatClientBuilder.build();

        Agent kbAgent = Agent.builder()
            .role("Knowledge Base Assistant")
            .goal("Find and summarise relevant Notion pages for: " + query)
            .backstory("You are an internal-knowledge assistant. For every lookup you use the notion " +
                       "tool: first operation='search' with the user's query, then operation=" +
                       "'retrieve_page' on the most relevant result. You cite page URLs. If the " +
                       "integration hasn't been shared with any matching page you say so honestly.")
            .chatClient(chatClient)
            .tools(List.of(notionTool))
            .maxTurns(5)
            .verbose(true)
            .build();

        Task task = Task.builder()
            .description("The user asks: \"Find me the most relevant Notion page about " + query + ".\" " +
                         "Use the notion tool to search the workspace, pick the best match, retrieve its " +
                         "body, and summarise it. Always cite the Notion URL of the retrieved page.")
            .expectedOutput("A 3-5 sentence summary of the most relevant Notion page + its URL, or a " +
                            "clear 'no matching page is shared with this integration' message.")
            .agent(kbAgent)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(kbAgent)
            .task(task)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("query", query));

        logger.info("");
        logger.info("=== NotionTool showcase result ===");
        logger.info("{}", result.getFinalOutput());
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("notion", args) : new String[]{"notion"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
