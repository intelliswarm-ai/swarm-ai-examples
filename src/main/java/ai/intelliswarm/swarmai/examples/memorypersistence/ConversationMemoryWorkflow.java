/* SwarmAI Framework - Copyright (c) 2025 IntelliSwarm.ai (MIT License) */
package ai.intelliswarm.swarmai.examples.memorypersistence;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Conversation Memory Persistence -- demonstrates how agents share and persist
 * knowledge via the Memory interface across three sequential phases:
 * (1) Knowledge Collector researches a topic and saves findings to shared memory,
 * (2) Knowledge Synthesizer recalls and extends those findings, (3) cross-agent
 * queries show search(), getRecentMemories(), and size().
 *
 * Usage: java -jar swarmai-framework.jar memory "sustainable energy technologies"
 */
@Component
public class ConversationMemoryWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ConversationMemoryWorkflow.class);
    private static final String COLLECTOR_ID  = "knowledge-collector";
    private static final String SYNTHESIZER_ID = "knowledge-synthesizer";

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationMemoryWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0
                ? String.join(" ", args)
                : "sustainable energy technologies";

        logger.info("\n" + "=".repeat(80));
        logger.info("CONVERSATION MEMORY PERSISTENCE WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic: {}", topic);
        logger.info("Process: SEQUENTIAL (learn -> recall -> cross-agent query)");
        logger.info("Memory: InMemoryMemory (thread-safe, shared across agents)");
        logger.info("=".repeat(80));
        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("memory-persistence");
        metrics.start();
        ChatClient chatClient = chatClientBuilder.build();
        Memory sharedMemory = new InMemoryMemory();  // shared across all agents

        // ===================== PHASE 1 -- LEARNING =====================
        logger.info("\n" + "-".repeat(80));
        logger.info("PHASE 1: LEARNING -- Knowledge Collector researches the topic");
        logger.info("-".repeat(80));
        Agent collector = Agent.builder()
                .role("Knowledge Collector")
                .goal("Research '" + topic + "' thoroughly. Produce a structured summary " +
                      "covering: (1) current state, (2) key technologies, (3) major players, " +
                      "(4) challenges, and (5) future outlook. Be specific with names and figures.")
                .backstory("You are a senior research analyst known for thorough, well-organized " +
                           "research briefs that become the foundation for downstream analysis.")
                .chatClient(chatClient)
                .memory(sharedMemory)
                .maxTurns(2)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .temperature(0.2)
                .build();
        Task researchTask = Task.builder()
                .description(String.format(
                        "Research the following topic comprehensively:\n\n  Topic: %s\n\n" +
                        "REQUIRED SECTIONS:\n" +
                        "1. **Current State** -- Where the field stands today\n" +
                        "2. **Key Technologies** -- Most important breakthroughs\n" +
                        "3. **Major Players** -- Leading organizations and researchers\n" +
                        "4. **Challenges** -- Open problems and obstacles\n" +
                        "5. **Future Outlook** -- Direction over the next 3-5 years\n\n" +
                        "Be specific and cite concrete examples.", topic))
                .expectedOutput("Structured research summary with five sections covering the topic")
                .agent(collector)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();
        Swarm learningSwarm = Swarm.builder()
                .id("memory-learning")
                .agent(collector)
                .task(researchTask)
                .memory(sharedMemory)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .maxRpm(15)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput learningResult = learningSwarm.kickoff(Map.of("topic", topic));
        String findings = learningResult.getFinalOutput();
        if (findings == null || findings.isEmpty()) {
            findings = "(No output generated)";
        }
        logger.info("\nCollector findings (first 500 chars):\n{}",
                findings.length() > 500 ? findings.substring(0, 500) + "..." : findings);
        // Persist findings into shared memory
        sharedMemory.save(COLLECTOR_ID, findings,
                Map.of("phase", "learning", "topic", topic, "type", "research-summary"));
        sharedMemory.save(COLLECTOR_ID,
                "Key research topic: " + topic + " -- completed by Knowledge Collector.",
                Map.of("phase", "learning", "topic", topic, "type", "meta"));
        logger.info("Saved {} memory entries for '{}'", sharedMemory.size(), COLLECTOR_ID);

        // ===================== PHASE 2 -- RECALL =======================
        logger.info("\n" + "-".repeat(80));
        logger.info("PHASE 2: RECALL -- Knowledge Synthesizer recalls and extends");
        logger.info("-".repeat(80));

        Agent synthesizer = Agent.builder()
                .role("Knowledge Synthesizer")
                .goal("Recall prior research about '" + topic + "' and synthesize it into " +
                      "actionable insights: practical applications, investment opportunities, " +
                      "and strategic recommendations.")
                .backstory("You are a strategy consultant who turns raw research into actionable " +
                           "business intelligence. You always ground recommendations in evidence " +
                           "from the shared memory store.")
                .chatClient(chatClient)
                .memory(sharedMemory)
                .maxTurns(2)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .temperature(0.3)
                .build();

        Task synthesisTask = Task.builder()
                .description(String.format(
                        "Recall what was previously learned about '%s' and build upon it.\n\n" +
                        "Use the shared memory containing prior research to produce:\n" +
                        "1. **Key Takeaways** -- 3-5 most important findings from prior research\n" +
                        "2. **Practical Applications** -- Real-world use of these findings\n" +
                        "3. **Strategic Recommendations** -- Actionable next steps\n" +
                        "4. **Knowledge Gaps** -- What still needs investigation\n\n" +
                        "Ground every claim in the prior research.", topic))
                .expectedOutput("Synthesis report with takeaways, applications, recommendations, and gaps")
                .agent(synthesizer)
                .dependsOn(researchTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/memory_synthesis_report.md")
                .maxExecutionTime(120000)
                .build();

        Swarm recallSwarm = Swarm.builder()
                .id("memory-recall")
                .agent(synthesizer)
                .task(synthesisTask)
                .memory(sharedMemory)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .maxRpm(15)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        SwarmOutput recallResult = recallSwarm.kickoff(Map.of("topic", topic));
        // Save synthesizer output back into memory
        sharedMemory.save(SYNTHESIZER_ID, recallResult.getFinalOutput(),
                Map.of("phase", "recall", "topic", topic, "type", "synthesis-report"));
        // ============== PHASE 3 -- CROSS-AGENT MEMORY QUERIES ==========
        logger.info("\n" + "-".repeat(80));
        logger.info("PHASE 3: CROSS-AGENT MEMORY -- Querying the shared memory store");
        logger.info("-".repeat(80));

        logger.info("\n[Memory Stats]  Total entries: {}", sharedMemory.size());

        logger.info("\n[memory.search(\"{}\", 5)]", topic);
        List<?> searchResults = sharedMemory.search(topic, 5);
        for (int i = 0; i < searchResults.size(); i++) {
            logEntry("Result", i + 1, searchResults.get(i).toString());
        }
        logger.info("\n[memory.getRecentMemories(\"{}\", 3)]", COLLECTOR_ID);
        List<?> collectorMemories = sharedMemory.getRecentMemories(COLLECTOR_ID, 3);
        for (int i = 0; i < collectorMemories.size(); i++) {
            logEntry("Collector", i + 1, collectorMemories.get(i).toString());
        }
        logger.info("\n[memory.getRecentMemories(\"{}\", 3)]", SYNTHESIZER_ID);
        List<?> synthMemories = sharedMemory.getRecentMemories(SYNTHESIZER_ID, 3);
        for (int i = 0; i < synthMemories.size(); i++) {
            logEntry("Synthesizer", i + 1, synthMemories.get(i).toString());
        }
        metrics.stop();

        // ===================== RESULTS =================================
        logger.info("\n" + "=".repeat(80));
        logger.info("CONVERSATION MEMORY PERSISTENCE WORKFLOW COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Topic: {}", topic);
        logger.info("Memory entries persisted: {}", sharedMemory.size());
        logger.info("Agents: {} (collector), {} (synthesizer)", COLLECTOR_ID, SYNTHESIZER_ID);
        logger.info("\n{}", recallResult.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("\nFinal Synthesis Report:\n{}", recallResult.getFinalOutput());
        logger.info("=".repeat(80));

        metrics.report();
    }

    private static void logEntry(String label, int index, String text) {
        logger.info("  {} {}: {}", label, index,
                text.length() > 120 ? text.substring(0, 120) + "..." : text);
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"memory"});
    }
}
