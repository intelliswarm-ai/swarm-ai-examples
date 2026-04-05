package ai.intelliswarm.swarmai.examples.distributed;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterTopology;
import ai.intelliswarm.swarmai.distributed.cluster.NodeCommunicator;
import ai.intelliswarm.swarmai.distributed.consensus.RaftNode;
import ai.intelliswarm.swarmai.distributed.execution.DistributedIntelligence;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distributed Intelligence Sharing — nodes share learned skills, rules, and insights via RAFT.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   ┌─────────────────────┐
 *   │  Node 1 (Leader)    │───── Learns: "JSON parsing skill"
 *   │  DistributedIntel   │      Shares via RAFT log replication
 *   └────────┬────────────┘
 *            │ RAFT replicate
 *   ┌────────▼────────────┐
 *   │  Node 2 (Follower)  │───── Receives skill → Applies to local analysis
 *   │  DistributedIntel   │      Reports: "skill effective, quality=0.9"
 *   └────────┬────────────┘
 *            │ RAFT replicate
 *   ┌────────▼────────────┐
 *   │  Node 3 (Follower)  │───── Receives skill + feedback
 *   │  DistributedIntel   │      Proposes improvement rule based on cluster data
 *   └────────────────────────┘
 * </pre>
 *
 * <h2>What This Demonstrates</h2>
 * <ul>
 *   <li><b>Skill sharing</b> — Generated skills propagated to all nodes via RAFT</li>
 *   <li><b>Feedback loop</b> — Nodes report skill effectiveness back to cluster</li>
 *   <li><b>Improvement rules</b> — Cluster-wide optimization rules proposed and accepted</li>
 *   <li><b>Convergence insights</b> — Optimal parameters discovered and shared</li>
 *   <li><b>Collective learning</b> — The cluster gets smarter as more nodes contribute</li>
 * </ul>
 */
@Component
public class DistributedIntelligenceWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(DistributedIntelligenceWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public DistributedIntelligenceWorkflow(ChatClient.Builder chatClientBuilder,
                                            ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "distributed AI systems";

        logger.info("\n" + "=".repeat(70));
        logger.info("DISTRIBUTED INTELLIGENCE SHARING");
        logger.info("=".repeat(70));
        logger.info("Topic:    {}", topic);
        logger.info("Cluster:  3 nodes sharing skills, rules, and convergence insights");
        logger.info("=".repeat(70));

        // ── Build a 3-Node Cluster with DistributedIntelligence ────────
        // Each node has its own DistributedIntelligence layer that communicates
        // learned knowledge through the RAFT consensus log.

        List<NodeCommunicator> communicators = new ArrayList<>();
        List<RaftNode> raftNodes = new ArrayList<>();
        List<DistributedIntelligence> intelligences = new ArrayList<>();

        try {
            for (int i = 0; i < 3; i++) {
                String nodeId = "intel-node-" + (i + 1);
                int port = 9200 + i;

                NodeCommunicator comm = new NodeCommunicator(nodeId, port);
                communicators.add(comm);

                RaftNode raft = new RaftNode(nodeId, comm, 150, 300, 500);
                raftNodes.add(raft);

                DistributedIntelligence intel = new DistributedIntelligence(raft);
                intelligences.add(intel);
            }

            // Wire peers
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    if (i != j) {
                        communicators.get(i).registerPeer(
                                "intel-node-" + (j + 1), "localhost", 9200 + j);
                        ClusterNode peer = new ClusterNode(
                                "intel-node-" + (j + 1), "localhost", 9200 + j);
                        raftNodes.get(i).addPeer(peer);
                    }
                }
            }

            // Start nodes
            for (int i = 0; i < 3; i++) {
                communicators.get(i).start();
                raftNodes.get(i).start();
                logger.info("  Started intel-node-{} on port {}", i + 1, 9200 + i);
            }

            // Wait for leader election
            Thread.sleep(2000);
            logger.info("Cluster ready.\n");

            // ── Set up Listeners to Track Intelligence Sharing ─────────
            AtomicInteger skillsReceived = new AtomicInteger(0);
            AtomicInteger rulesReceived = new AtomicInteger(0);
            AtomicInteger insightsReceived = new AtomicInteger(0);

            for (int i = 0; i < 3; i++) {
                int nodeIdx = i;
                intelligences.get(i).onSkillShared(skill ->
                        logger.info("  [node-{}] Received skill: {} (category={}, effectiveness={})",
                                nodeIdx + 1, skill.skillName(), skill.category(), skill.effectiveness()));
                intelligences.get(i).onSkillShared(s -> skillsReceived.incrementAndGet());

                intelligences.get(i).onRuleAccepted(rule ->
                        logger.info("  [node-{}] Accepted rule: {} → {} (confidence={})",
                                nodeIdx + 1, rule.category(), rule.recommendation(), rule.confidence()));
                intelligences.get(i).onRuleAccepted(r -> rulesReceived.incrementAndGet());

                intelligences.get(i).onConvergenceInsight(insight ->
                        logger.info("  [node-{}] Convergence: {}={} (improvement={}%)",
                                nodeIdx + 1, insight.parameterName(), insight.optimalValue(),
                                String.format("%.1f", insight.improvementPercent())));
                intelligences.get(i).onConvergenceInsight(c -> insightsReceived.incrementAndGet());
            }

            // ── Phase 1: Node 1 Discovers and Shares a Skill ──────────
            logger.info("--- Phase 1: Skill Discovery & Sharing ---");

            DistributedIntelligence node1Intel = intelligences.get(0);
            node1Intel.shareSkill(
                    "skill-json-extract", "JSON Response Parser",
                    "data-extraction", 0.85,
                    Map.of(
                            "pattern", "Extract structured fields from LLM JSON responses",
                            "inputType", "raw_llm_output",
                            "outputType", "Map<String, Object>"
                    ));

            Thread.sleep(500); // Allow RAFT replication
            logger.info("  Node 1 shared 'JSON Response Parser' skill");

            // ── Phase 2: Node 2 Uses Skill, Reports Feedback ──────────
            logger.info("\n--- Phase 2: Skill Usage & Feedback ---");

            // Node 2 runs a local analysis using the shared skill
            ChatClient chatClient = chatClientBuilder.build();
            Agent analyst = Agent.builder()
                    .role("Research Analyst")
                    .goal("Analyze " + topic + " with structured output")
                    .backstory("You produce well-structured analyses with clear data extraction.")
                    .chatClient(chatClient)
                    .temperature(0.3)
                    .build();

            Task analysisTask = Task.builder()
                    .description("Analyze '" + topic + "' and produce a structured report with: " +
                                 "key_findings (list), market_size (estimate), top_players (list), " +
                                 "risk_level (LOW/MEDIUM/HIGH)")
                    .expectedOutput("Structured analysis with labeled fields")
                    .agent(analyst)
                    .build();

            Swarm localSwarm = Swarm.builder()
                    .agent(analyst)
                    .task(analysisTask)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(eventPublisher)
                    .build();

            SwarmOutput output = localSwarm.kickoff(Map.of("topic", topic));
            logger.info("  Node 2 completed local analysis: {} chars",
                    output.getFinalOutput() != null ? output.getFinalOutput().length() : 0);

            // Report skill feedback
            DistributedIntelligence node2Intel = intelligences.get(1);
            node2Intel.reportSkillFeedback("skill-json-extract", true, 0.92,
                    output.getTotalTokens(),
                    "Used for structured extraction on '" + topic + "' analysis");
            logger.info("  Node 2 reported skill feedback: effective=true, quality=0.92");

            Thread.sleep(500);

            // ── Phase 3: Node 3 Proposes an Improvement Rule ──────────
            logger.info("\n--- Phase 3: Improvement Rule Proposal ---");

            DistributedIntelligence node3Intel = intelligences.get(2);
            node3Intel.proposeImprovementRule(
                    "rule-temp-for-extraction", "data-extraction",
                    Map.of("taskType", "structured_extraction", "outputFormat", "JSON"),
                    "Use temperature=0.1 for data extraction tasks to reduce hallucination",
                    0.88, 15);
            logger.info("  Node 3 proposed rule: 'Low temperature for extraction'");

            Thread.sleep(500);

            // ── Phase 4: Share Convergence Insight ─────────────────────
            logger.info("\n--- Phase 4: Convergence Insight ---");

            node1Intel.shareConvergenceInsight(
                    "insight-optimal-turns", "maxTurns",
                    3, 0.25,
                    Map.of("taskComplexity", "medium", "agentRole", "analyst"));
            logger.info("  Node 1 shared convergence insight: optimal maxTurns=3 for medium tasks");

            Thread.sleep(500);

            // ── Cluster Intelligence Snapshot ──────────────────────────
            logger.info("\n" + "=".repeat(70));
            logger.info("DISTRIBUTED INTELLIGENCE RESULTS");
            logger.info("=".repeat(70));

            DistributedIntelligence.ClusterIntelligenceSnapshot snapshot =
                    node1Intel.snapshot();

            logger.info("Skills shared:      {}", snapshot.totalSharedSkills());
            logger.info("Rules proposed:     {}", snapshot.totalAcceptedRules());
            logger.info("Insights shared:    {}", snapshot.totalConvergenceInsights());
            logger.info("Feedback entries:   {}", snapshot.totalSkillFeedbackEntries());
            logger.info("");
            logger.info("Cross-node propagation:");
            logger.info("  Skills received:  {} (across all nodes)", skillsReceived.get());
            logger.info("  Rules received:   {} (across all nodes)", rulesReceived.get());
            logger.info("  Insights received: {} (across all nodes)", insightsReceived.get());

            // Show the analysis result
            logger.info("\n--- Analysis Output ---\n{}", output.getFinalOutput());
            logger.info("=".repeat(70));

        } finally {
            // Cleanup
            logger.info("\nShutting down intelligence cluster...");
            for (RaftNode raft : raftNodes) {
                try { raft.stop(); } catch (Exception e) { /* cleanup */ }
            }
            for (NodeCommunicator comm : communicators) {
                try { comm.close(); } catch (Exception e) { /* cleanup */ }
            }
            logger.info("Cluster shut down.");
        }
    }
}
