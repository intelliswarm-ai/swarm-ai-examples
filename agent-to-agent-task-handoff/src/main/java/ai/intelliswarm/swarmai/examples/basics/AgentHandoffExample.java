package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import ai.intelliswarm.swarmai.judge.LLMJudge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Map;

/**
 * Shows how one agent's output feeds into another agent's task.
 *
 * TWO agents, TWO tasks with a dependency (task2 dependsOn task1).
 * The Researcher gathers information and the Editor refines it into a polished summary.
 */
@Component
public class AgentHandoffExample {

    private static final Logger logger = LoggerFactory.getLogger(AgentHandoffExample.class);
    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public AgentHandoffExample(ChatClient.Builder chatClientBuilder,
                               ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        ChatClient chatClient = chatClientBuilder.build();
        String topic = args.length > 0 ? String.join(" ", args)
                : "WebAssembly outside the browser: where it actually makes sense in 2026";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("agent-handoff");
        metrics.start();

        // Agent 1: Researcher -- READ_ONLY, gathers raw information
        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research the given topic and produce detailed, factual notes")
                .backstory("You are a thorough researcher who gathers comprehensive information "
                         + "on any topic. You focus on accuracy and breadth of coverage.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(2)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .build();

        // Agent 2: Editor -- WORKSPACE_WRITE, improves the research output
        Agent editor = Agent.builder()
                .role("Editor")
                .goal("Edit and improve raw research into polished, publication-ready content")
                .backstory("You are a senior editor who transforms rough notes into clear, "
                         + "well-structured, and engaging content.")
                .chatClient(chatClient)
                .verbose(true)
                .maxTurns(1)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .toolHook(metrics.metricsHook())
                .build();

        // Task 1: Research
        Task researchTask = Task.builder()
                .description("Research the topic: " + topic + ". "
                           + "Identify key concepts, recent developments, and practical applications.")
                .expectedOutput("Detailed research notes covering the topic's key aspects")
                .agent(researcher)
                .build();

        // Task 2: Edit -- depends on task 1, so it receives the research output
        Task editTask = Task.builder()
                .description("Take the research notes and shape them into a publication-ready "
                           + "blog post. Use Markdown headings, an introduction, 3-5 main sections, "
                           + "and a brief conclusion. Cite specific facts the researcher found "
                           + "(do not invent new ones). Write in clear, engaging prose.")
                .expectedOutput("A complete Markdown blog post, ready to publish, with title, "
                              + "intro, sections, and conclusion")
                .agent(editor)
                .dependsOn(researchTask)
                .build();

        // Sequential swarm -- task1 runs first, then task2 receives its output
        Swarm swarm = Swarm.builder()
                .agent(researcher)
                .agent(editor)
                .task(researchTask)
                .task(editTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput result = swarm.kickoff(Map.of("topic", topic));

        logger.info("\n=== Result ===");
        logger.info("{}", result.getFinalOutput());

        // Persist the editor's polished post as a real Markdown file the user can open.
        if (result.isSuccessful() && result.getFinalOutput() != null) {
            Path outDir = Paths.get("output", "agent-handoff");
            Files.createDirectories(outDir);
            String slug = topic.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-|-$)", "");
            if (slug.length() > 60) slug = slug.substring(0, 60);
            Path postFile = outDir.resolve(LocalDate.now() + "-" + slug + ".md");
            Files.writeString(postFile, result.getFinalOutput());
            logger.info("📄 Polished post written to: {}", postFile.toAbsolutePath());
        }

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("agent-handoff",
                "Two agents with task dependencies: researcher -> editor",
                result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startMs,
                2, 2, "SEQUENTIAL", "agent-to-agent-task-handoff");
        }

        metrics.stop();
        metrics.report();
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"agent-handoff"});
    }
}
