package ai.intelliswarm.swarmai.examples.distributed;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.distributed.cluster.DistributedSwarmNode;
import ai.intelliswarm.swarmai.distributed.execution.WorkPartition;
import ai.intelliswarm.swarmai.distributed.goal.GoalStatus;
import ai.intelliswarm.swarmai.distributed.goal.PartitionStrategy;
import ai.intelliswarm.swarmai.distributed.goal.SwarmGoal;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Distributed Goal Execution — showcases SwarmAI's RAFT-based multi-node processing.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   ┌─────────────┐      RAFT       ┌─────────────┐      RAFT       ┌─────────────┐
 *   │   Node 1    │◄───consensus───►│   Node 2    │◄───consensus───►│   Node 3    │
 *   │  (Leader)   │                  │ (Follower)  │                  │ (Follower)  │
 *   │             │                  │             │                  │             │
 *   │ Partition 1 │                  │ Partition 2 │                  │ Partition 3 │
 *   │ Partition 4 │                  │ Partition 5 │                  │ Partition 6 │
 *   └─────────────┘                  └─────────────┘                  └─────────────┘
 *          │                                │                                │
 *          ▼                                ▼                                ▼
 *   GoalReconciler                   Local Agents                    Local Agents
 *   monitors progress                execute work                   execute work
 * </pre>
 *
 * <h2>What This Demonstrates</h2>
 * <ul>
 *   <li><b>SwarmGoal</b> — Declarative goal with success criteria and deadline</li>
 *   <li><b>Work Partitioning</b> — Goal divided into work units distributed across nodes</li>
 *   <li><b>RAFT Consensus</b> — Leader election, log replication, fault-tolerant coordination</li>
 *   <li><b>GoalReconciler</b> — Monitors progress, reassigns failed partitions</li>
 *   <li><b>FailureDetector</b> — Phi-accrual failure detection with suspect/dead transitions</li>
 *   <li><b>Local Execution</b> — Each node runs partitions using local SwarmAI agents</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   ./scripts/run.sh distributed-goal "Analyze top 10 AI frameworks"
 * </pre>
 */
@Component
public class DistributedGoalWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(DistributedGoalWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public DistributedGoalWorkflow(ChatClient.Builder chatClientBuilder,
                                    ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "AI agent frameworks";
        int nodeCount = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        logger.info("\n" + "=".repeat(70));
        logger.info("DISTRIBUTED GOAL EXECUTION");
        logger.info("=".repeat(70));
        logger.info("Topic:      {}", topic);
        logger.info("Cluster:    {} nodes (localhost, ports 9100-{})", nodeCount, 9100 + nodeCount - 1);
        logger.info("Consensus:  RAFT with leader election");
        logger.info("Strategy:   ADAPTIVE (least-loaded-first)");
        logger.info("=".repeat(70));

        // ── Define the Goal ────────────────────────────────────────────
        // A SwarmGoal is declarative: WHAT to achieve, not HOW.
        // The cluster decides how to partition and distribute the work.

        List<String> targets = List.of(
                "LangChain", "CrewAI", "AutoGen", "Spring AI",
                "LlamaIndex", "Semantic Kernel", "Haystack",
                "SwarmAI", "OpenAI Swarm", "MetaGPT"
        );

        SwarmGoal goal = SwarmGoal.builder()
                .name("framework-analysis-" + System.currentTimeMillis())
                .objective("Produce a comparative analysis of " + targets.size() +
                           " AI agent frameworks covering: architecture, strengths, " +
                           "weaknesses, and production readiness")
                .successCriterion("All " + targets.size() + " frameworks analyzed")
                .successCriterion("Each analysis includes architecture overview")
                .successCriterion("Each analysis includes strengths and weaknesses")
                .deadline(Instant.now().plus(10, ChronoUnit.MINUTES))
                .partitioning(PartitionStrategy.ADAPTIVE)
                .replicas(nodeCount)
                .build();

        logger.info("\n--- Goal ---");
        logger.info("Name:       {}", goal.name());
        logger.info("Objective:  {}", goal.objective());
        logger.info("Criteria:   {}", goal.successCriteria());
        logger.info("Deadline:   {}", goal.deadline());
        logger.info("Strategy:   {}", goal.partitioning());

        // ── Prepare Work Items ─────────────────────────────────────────
        // Each work item becomes a WorkPartition assigned to a cluster node.

        List<Map<String, Object>> workItems = new ArrayList<>();
        for (String framework : targets) {
            workItems.add(Map.of(
                    "framework", framework,
                    "topic", topic,
                    "instructions", String.format(
                            "Analyze '%s' as an AI agent framework. Cover:\n" +
                            "1. ARCHITECTURE: Core abstractions, execution model\n" +
                            "2. STRENGTHS: What it does better than alternatives\n" +
                            "3. WEAKNESSES: Limitations and gaps\n" +
                            "4. PRODUCTION READINESS: Enterprise features, scalability\n" +
                            "Keep analysis concise (3-4 paragraphs).", framework)
            ));
        }

        logger.info("\n--- Work Items ---");
        logger.info("Total partitions: {}", workItems.size());
        for (int i = 0; i < workItems.size(); i++) {
            logger.info("  [{}] {}", i, workItems.get(i).get("framework"));
        }

        // ── Build the Local Agent for Partition Execution ──────────────
        // Each node uses this agent to execute its assigned partitions.

        ChatClient chatClient = chatClientBuilder.build();

        Agent analyst = Agent.builder()
                .role("Framework Analyst")
                .goal("Produce accurate, structured analysis of AI agent frameworks")
                .backstory("You are a senior software architect who has evaluated " +
                           "dozens of AI frameworks for enterprise adoption. You focus on " +
                           "practical capabilities, not marketing claims.")
                .chatClient(chatClient)
                .verbose(true)
                .temperature(0.3)
                .build();

        // ── Build Cluster Nodes ────────────────────────────────────────
        // In production, each node runs on a separate JVM/machine.
        // For this example, we run all nodes in-process on different ports.

        logger.info("\n--- Starting Cluster ---");
        List<DistributedSwarmNode> nodes = new ArrayList<>();

        try {
            for (int i = 0; i < nodeCount; i++) {
                String nodeId = "node-" + (i + 1);
                int port = 9100 + i;

                var builder = DistributedSwarmNode.builder()
                        .nodeId(nodeId)
                        .listenPort(port)
                        .heartbeatIntervalMs(150)
                        .electionTimeoutMinMs(300)
                        .electionTimeoutMaxMs(500)
                        .suspectThresholdMs(5000)
                        .deadThresholdMs(15000)
                        .workerThreads(2);

                // Register all other nodes as peers
                for (int j = 0; j < nodeCount; j++) {
                    if (j != i) {
                        builder.peer("node-" + (j + 1), "localhost", 9100 + j);
                    }
                }

                // Each node executes partitions using a local SwarmAI agent
                builder.partitionExecutor(partition -> executePartition(analyst, partition));

                DistributedSwarmNode node = builder.build();
                node.start();
                nodes.add(node);
                logger.info("  Started {} on port {}", nodeId, port);
            }

            // Wait for RAFT leader election
            logger.info("\nWaiting for RAFT leader election...");
            Thread.sleep(2000);

            DistributedSwarmNode leader = nodes.stream()
                    .filter(DistributedSwarmNode::isLeader)
                    .findFirst()
                    .orElse(nodes.get(0)); // fallback to first node

            logger.info("Leader elected: {}", leader.nodeId());
            logger.info("Cluster topology: {} active nodes",
                    leader.topology().activeNodes().size());

            // ── Submit Goal to Leader ──────────────────────────────────
            logger.info("\n--- Submitting Goal ---");
            long startTime = System.currentTimeMillis();

            GoalStatus status = leader.submitGoal(goal, workItems);
            logger.info("Goal submitted: phase={}", status.phase());

            // ── Wait for Completion ────────────────────────────────────
            logger.info("Waiting for goal completion (timeout: 5 minutes)...");
            GoalStatus finalStatus = leader.awaitGoalCompletion(5, TimeUnit.MINUTES);

            long duration = System.currentTimeMillis() - startTime;

            // ── Report Results ─────────────────────────────────────────
            logger.info("\n" + "=".repeat(70));
            logger.info("DISTRIBUTED GOAL RESULTS");
            logger.info("=".repeat(70));

            if (finalStatus != null) {
                GoalStatus.GoalStatusSnapshot snapshot = finalStatus.snapshot();
                logger.info("Phase:              {}", snapshot.phase());
                logger.info("Duration:           {}s", duration / 1000);
                logger.info("Total partitions:   {}", snapshot.totalPartitions());
                logger.info("Completed:          {}", snapshot.completedPartitions());
                logger.info("Failed:             {}", snapshot.failedPartitions());
                logger.info("Completion:         {}%", String.format("%.1f", snapshot.completionPercentage()));
                logger.info("All criteria met:   {}", snapshot.allCriteriaMet());
                logger.info("Leader:             {}", snapshot.leaderId());

                logger.info("\n--- Cluster Node Status ---");
                for (var entry : finalStatus.nodeStatuses().entrySet()) {
                    GoalStatus.NodeStatus ns = entry.getValue();
                    logger.info("  {} | state={} | assigned={} | completed={}",
                            ns.nodeId(), ns.state(),
                            ns.assignedPartitions(), ns.completedPartitions());
                }

                logger.info("\n--- Success Criteria ---");
                for (var entry : snapshot.criteriaStatus().entrySet()) {
                    logger.info("  [{}] {}", entry.getValue(), entry.getKey());
                }

                logger.info("\n--- Events ---");
                for (String event : snapshot.events()) {
                    logger.info("  {}", event);
                }
            } else {
                logger.warn("Goal did not complete within timeout");
            }

            logger.info("=".repeat(70));

        } finally {
            // ── Cleanup ────────────────────────────────────────────────
            logger.info("\nShutting down cluster...");
            for (DistributedSwarmNode node : nodes) {
                try {
                    node.close();
                } catch (Exception e) {
                    logger.debug("Cleanup error for {}: {}", node.nodeId(), e.getMessage());
                }
            }
            logger.info("Cluster shut down.");
        }
    }

    /**
     * Executes a single work partition using a local SwarmAI agent.
     * In production, this would run a full Swarm with multiple agents.
     */
    private Map<String, Object> executePartition(Agent analyst, WorkPartition partition) {
        String framework = (String) partition.inputs().getOrDefault("framework", "unknown");
        String instructions = (String) partition.inputs().getOrDefault("instructions", "Analyze the framework");

        logger.info("    [{}] Analyzing: {}", partition.partitionId(), framework);

        try {
            Task analysisTask = Task.builder()
                    .description(instructions)
                    .expectedOutput("Structured analysis with architecture, strengths, weaknesses, readiness")
                    .agent(analyst)
                    .build();

            Swarm swarm = Swarm.builder()
                    .agent(analyst)
                    .task(analysisTask)
                    .process(ProcessType.SEQUENTIAL)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of("framework", framework));

            return Map.of(
                    "framework", framework,
                    "analysis", output.getFinalOutput() != null ? output.getFinalOutput() : "",
                    "success", true,
                    "tokens", output.getTotalTokens()
            );
        } catch (Exception e) {
            logger.error("    [{}] Failed: {}", partition.partitionId(), e.getMessage());
            return Map.of(
                    "framework", framework,
                    "error", e.getMessage(),
                    "success", false
            );
        }
    }
}
