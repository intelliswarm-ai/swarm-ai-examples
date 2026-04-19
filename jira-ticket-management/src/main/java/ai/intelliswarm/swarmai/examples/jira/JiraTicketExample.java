package ai.intelliswarm.swarmai.examples.jira;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.productivity.JiraTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * JiraTool showcase — a triage agent queries issues via JQL and proposes next actions.
 *
 * <p>Run: {@code ./run.sh jira "project = ACME AND status = \"In Progress\""}
 */
@Component
public class JiraTicketExample {

    private static final Logger logger = LoggerFactory.getLogger(JiraTicketExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final JiraTool jiraTool;

    public JiraTicketExample(ChatClient.Builder chatClientBuilder,
                             ApplicationEventPublisher eventPublisher,
                             JiraTool jiraTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.jiraTool = jiraTool;
    }

    public void run(String... args) {
        String jql = args.length > 0 ? String.join(" ", args)
            : "assignee = currentUser() ORDER BY updated DESC";

        String smoke = jiraTool.smokeTest();
        if (smoke != null) {
            logger.error("JiraTool unhealthy: {}", smoke);
            logger.error("Set JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN env vars.");
            return;
        }

        ChatClient chatClient = chatClientBuilder.build();

        Agent triageAgent = Agent.builder()
            .role("Sprint Triage Lead")
            .goal("Summarise open work and recommend next actions based on JQL search results")
            .backstory("You are a pragmatic engineering lead. For any Jira question you use the jira " +
                       "tool: operation='search_issues' with a JQL query to pull issues, then " +
                       "operation='get_issue' for any ticket that needs deeper context. You cite " +
                       "issue keys (e.g. ACME-42) everywhere.")
            .chatClient(chatClient)
            .tools(List.of(jiraTool))
            .permissionMode(PermissionLevel.WORKSPACE_WRITE) // read-only JQL is safe; writes gated via allow-writes use case
            .maxTurns(5)
            .verbose(true)
            .build();

        Task task = Task.builder()
            .description("Using JQL: `" + jql + "`, pull the matching Jira issues and produce: " +
                         "(1) a bullet list of the top 10 issues with key / summary / status / assignee, " +
                         "(2) a one-paragraph 'what needs attention first' summary citing 2-3 specific " +
                         "issue keys, and (3) a suggested follow-up question the user might ask next.")
            .expectedOutput("A three-section triage report referencing real Jira issue keys.")
            .agent(triageAgent)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(triageAgent)
            .task(task)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("jql", jql));

        logger.info("");
        logger.info("=== JiraTool showcase result ===");
        logger.info("{}", result.getFinalOutput());
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("jira", args) : new String[]{"jira"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
