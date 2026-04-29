package ai.intelliswarm.swarmai.examples.arxiv;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.research.ArxivTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ArxivTool showcase — a research agent builds a short literature review of recent papers
 * on a given topic, citing arXiv IDs and PDF links.
 *
 * <p>Run: {@code ./run.sh arxiv "transformer interpretability"}
 */
@Component
public class ArxivPaperExample {

    private static final Logger logger = LoggerFactory.getLogger(ArxivPaperExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final ArxivTool arxivTool;

    public ArxivPaperExample(ChatClient.Builder chatClientBuilder,
                             ApplicationEventPublisher eventPublisher,
                             ArxivTool arxivTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.arxivTool = arxivTool;
    }

    public void run(String... args) {
        String topic = args.length > 0 ? String.join(" ", args) : "multi-agent reinforcement learning";

        String smoke = arxivTool.smokeTest();
        if (smoke != null) {
            logger.error("ArxivTool unhealthy: {}", smoke);
            return;
        }

        ChatClient chatClient = chatClientBuilder.build();

        Agent reviewer = Agent.builder()
            .role("Academic Literature Reviewer")
            .goal("Produce a short literature review of recent arXiv papers on: " + topic)
            .backstory("You are a rigorous academic researcher. You always use the arxiv_search tool " +
                       "with sort_by='submittedDate' to find recent work, then optionally use " +
                       "operation='get' with a specific arXiv ID to pull more detail. Every paper you " +
                       "mention must have been returned by the tool — never invent arXiv IDs.")
            .chatClient(chatClient)
            .tools(List.of(arxivTool))
            .maxTurns(5)
            .verbose(true)
            .build();

        Task review = Task.builder()
            .description("Find the 3-5 most relevant recent arXiv papers on: \"" + topic + "\". For each " +
                         "paper provide the title, first author, arXiv ID, and a one-sentence summary " +
                         "grounded in the abstract the tool returned. Also write a short synthesis " +
                         "paragraph of the themes you noticed.")
            .expectedOutput("3-5 papers (title, first author, arXiv ID, summary) plus a synthesis paragraph")
            .outputType(LiteratureReview.class)
            .agent(reviewer)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(reviewer)
            .task(review)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("topic", topic));

        logger.info("");
        logger.info("=== ArxivTool showcase result ===");

        // Demonstrate typed access — the agent's output is auto-parsed into LiteratureReview.
        TaskOutput out = result.getTaskOutputs().isEmpty() ? null : result.getTaskOutputs().get(0);
        LiteratureReview lr = out != null ? out.as(LiteratureReview.class) : null;
        if (lr != null && lr.papers != null) {
            logger.info("Found {} papers:", lr.papers.size());
            for (Paper p : lr.papers) {
                logger.info("  [{}] {} — {}", p.arxivId, p.title, p.firstAuthor);
                logger.info("      {}", p.summary);
            }
            logger.info("");
            logger.info("Synthesis: {}", lr.synthesis);
        } else {
            // Fallback for runs where the model couldn't produce JSON.
            logger.info("{}", result.getFinalOutput());
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("arxiv", args) : new String[]{"arxiv"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    /** Typed shape returned by the literature review task. */
    public static class LiteratureReview {
        public java.util.List<Paper> papers;
        public String synthesis;
        public LiteratureReview() {}
    }

    public static class Paper {
        public String arxivId;
        public String title;
        public String firstAuthor;
        public String summary;
        public Paper() {}
    }
}
