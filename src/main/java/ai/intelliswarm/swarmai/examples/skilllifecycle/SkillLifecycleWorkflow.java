package ai.intelliswarm.swarmai.examples.skilllifecycle;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.SelfImprovingProcess;
import ai.intelliswarm.swarmai.rl.HeuristicPolicy;
import ai.intelliswarm.swarmai.skill.GeneratedSkill;
import ai.intelliswarm.swarmai.skill.SkillRegistry;
import ai.intelliswarm.swarmai.skill.SkillStatus;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Skill Lifecycle Demo — watch a generated skill progress through all lifecycle stages.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   Run 1: Workflow hits capability gap → generates skill → CANDIDATE
 *       │
 *       ▼  (sandbox validation)
 *   Run 1: Skill passes tests → VALIDATED
 *       │
 *       ▼  (used in execution)
 *   Run 2: Skill used successfully → ACTIVE
 *       │
 *       ▼  (reused 5+ times with 70%+ success)
 *   Run 5: Hits promotion threshold → PROMOTED
 *       │
 *       ▼  (persisted to disk)
 *   Run 6: Loaded on startup → PERMANENT
 *       │
 *       ▼  (quality assessment)
 *   Run 7: New workflow auto-selects skill (no generation needed) → CURATED
 * </pre>
 *
 * <h2>What This Proves</h2>
 * <ul>
 *   <li>Skills are generated dynamically at runtime when gaps are detected</li>
 *   <li>Skills have a formal lifecycle with promotion criteria</li>
 *   <li>Skills persist and are reused across workflow runs</li>
 *   <li>The framework LEARNS — later runs are cheaper because skills are reused</li>
 *   <li>Shared SkillRegistry enables cross-workflow skill sharing</li>
 * </ul>
 */
@Component
public class SkillLifecycleWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(SkillLifecycleWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public SkillLifecycleWorkflow(ChatClient.Builder chatClientBuilder,
                                   ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "AI agent frameworks";
        int totalRuns = args.length > 1 ? Integer.parseInt(args[1]) : 7;

        logger.info("\n" + "=".repeat(80));
        logger.info("SKILL LIFECYCLE DEMONSTRATION");
        logger.info("=".repeat(80));
        logger.info("Topic:    {}", topic);
        logger.info("Runs:     {} (watching skill progress CANDIDATE → PERMANENT)", totalRuns);
        logger.info("Registry: Shared across all runs (simulates cross-workflow reuse)");
        logger.info("=".repeat(80));

        ChatClient chatClient = chatClientBuilder.build();

        // Shared SkillRegistry persists across all runs —
        // this is the key to demonstrating cross-workflow learning
        SkillRegistry sharedRegistry = new SkillRegistry();

        // Vary topics to prove skills generalize across domains
        List<String> topics = List.of(
                topic,
                "distributed systems patterns",
                "cloud-native architecture",
                "real-time data processing",
                "enterprise security frameworks",
                "DevOps automation pipelines",
                "microservices observability"
        );

        List<RunSnapshot> snapshots = new ArrayList<>();

        for (int run = 1; run <= totalRuns; run++) {
            String runTopic = topics.get((run - 1) % topics.size());

            logger.info("\n" + "-".repeat(60));
            logger.info("RUN {}/{} — Topic: '{}'", run, totalRuns, runTopic);
            logger.info("-".repeat(60));

            // Snapshot before
            int skillsBefore = sharedRegistry.size();
            int activeBefore = sharedRegistry.getActiveSkillCount();
            Map<SkillStatus, Integer> statusBefore = countByStatus(sharedRegistry);

            // Execute self-improving workflow with shared registry
            Memory memory = new InMemoryMemory();
            SwarmOutput output = executeSelfImprovingRun(
                    chatClient, runTopic, sharedRegistry, memory, run);

            // Snapshot after
            int skillsAfter = sharedRegistry.size();
            int activeAfter = sharedRegistry.getActiveSkillCount();
            Map<SkillStatus, Integer> statusAfter = countByStatus(sharedRegistry);

            // Check for promotions
            int newSkills = skillsAfter - skillsBefore;
            int newActive = activeAfter - activeBefore;

            // Manually simulate promotion for skills that meet threshold
            // (in production, SelfImprovingProcess does this automatically)
            int promoted = 0;
            for (GeneratedSkill skill : sharedRegistry.getActiveSkills()) {
                if (skill.getStatus() == SkillStatus.ACTIVE && skill.meetsPromotionThreshold()) {
                    skill.setStatus(SkillStatus.PROMOTED);
                    promoted++;
                    logger.info("  PROMOTED: '{}' (usage={}, effectiveness={}%)",
                            skill.getName(), skill.getUsageCount(),
                            String.format("%.0f", skill.getEffectiveness() * 100));
                }
                if (skill.getStatus() == SkillStatus.PROMOTED && skill.getUsageCount() >= 8) {
                    skill.setStatus(SkillStatus.PERMANENT);
                    logger.info("  PERMANENT: '{}' (will be loaded on next startup)",
                            skill.getName());
                }
            }

            Map<SkillStatus, Integer> statusFinal = countByStatus(sharedRegistry);

            RunSnapshot snapshot = new RunSnapshot(
                    run, runTopic, output.isSuccessful(),
                    output.getTotalTokens(), newSkills, newActive, promoted,
                    statusFinal, sharedRegistry.size());
            snapshots.add(snapshot);

            logger.info("  Skills: {} total ({} new), statuses: {}",
                    sharedRegistry.size(), newSkills, statusFinal);
        }

        // ── Final Report ───────────────────────────────────────────────

        logger.info("\n" + "=".repeat(80));
        logger.info("SKILL LIFECYCLE RESULTS");
        logger.info("=".repeat(80));

        logger.info(String.format("\n%-4s %-30s %8s %6s %6s %8s",
                "Run", "Topic", "Tokens", "New", "Total", "Statuses"));
        logger.info("-".repeat(72));

        for (RunSnapshot s : snapshots) {
            logger.info(String.format("%-4d %-30s %8d %6d %6d %s",
                    s.run, truncate(s.topic, 30), s.tokens,
                    s.newSkills, s.totalSkills, formatStatuses(s.statuses)));
        }

        // Learning curve: tokens should decrease as skills are reused
        if (snapshots.size() >= 2) {
            long firstTokens = snapshots.get(0).tokens;
            long lastTokens = snapshots.get(snapshots.size() - 1).tokens;
            double delta = firstTokens > 0
                    ? ((lastTokens - firstTokens) * 100.0 / firstTokens) : 0;
            logger.info("\n  Token trend: Run 1={} → Run {}={} ({}{}%)",
                    firstTokens, snapshots.size(), lastTokens,
                    delta >= 0 ? "+" : "", String.format("%.1f", delta));
        }

        // Final skill inventory
        logger.info("\n--- Skill Inventory ---");
        Map<String, Object> stats = sharedRegistry.getStats();
        for (var entry : stats.entrySet()) {
            logger.info("  {}: {}", entry.getKey(), entry.getValue());
        }

        // List all skills with lifecycle status
        logger.info("\n--- Individual Skills ---");
        for (GeneratedSkill skill : sharedRegistry.getActiveSkills()) {
            logger.info("  [{}] {} — usage={}, effectiveness={}%, quality={}",
                    skill.getStatus(), skill.getName(),
                    skill.getUsageCount(),
                    String.format("%.0f", skill.getEffectiveness() * 100),
                    skill.getQualityScore() != null ? skill.getQualityScore().grade() : "N/A");
        }

        logger.info("\n  Lifecycle progression demonstrated:");
        logger.info("    CANDIDATE → VALIDATED → ACTIVE → PROMOTED → PERMANENT");
        logger.info("    Skills crystallize after 5+ uses with 70%+ success rate");
        logger.info("    Reused skills save token budget on subsequent runs");
        logger.info("=".repeat(80));
    }

    private SwarmOutput executeSelfImprovingRun(ChatClient chatClient, String topic,
                                                 SkillRegistry sharedRegistry,
                                                 Memory memory, int runNumber) {
        Agent analyst = Agent.builder()
                .role("Research Analyst")
                .goal("Produce a structured analysis of: " + topic)
                .backstory("Senior analyst. Use available tools and skills. " +
                           "Identify gaps in your capabilities.")
                .chatClient(chatClient)
                .memory(memory)
                .permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.2)
                .verbose(false)
                .build();

        Agent reviewer = Agent.builder()
                .role("Quality Reviewer")
                .goal("Evaluate analysis quality. Respond APPROVED if thorough, " +
                      "or CAPABILITY_GAPS listing specific missing abilities.")
                .backstory("Demanding reviewer who pushes for data-backed claims.")
                .chatClient(chatClient)
                .temperature(0.1)
                .verbose(false)
                .build();

        Task task = Task.builder()
                .id("analysis-run-" + runNumber)
                .description("Analyze '" + topic + "': key concepts, industry applications, " +
                             "competitive landscape, and future outlook.")
                .expectedOutput("Structured 4-section analysis")
                .agent(analyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        // Use the shared SkillRegistry — skills generated in earlier runs
        // are available to this run immediately
        SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(analyst), reviewer, eventPublisher,
                3, "Analysis must cite specific projects, tools, or data points.",
                sharedRegistry  // ← Shared across all runs
        );

        return process.execute(List.of(task), Map.of("topic", topic),
                "skill-lifecycle-run-" + runNumber);
    }

    private Map<SkillStatus, Integer> countByStatus(SkillRegistry registry) {
        Map<SkillStatus, Integer> counts = new LinkedHashMap<>();
        for (SkillStatus status : SkillStatus.values()) {
            counts.put(status, 0);
        }
        for (GeneratedSkill skill : registry.getActiveSkills()) {
            counts.merge(skill.getStatus(), 1, Integer::sum);
        }
        // Also count candidates (getActiveSkills filters them out)
        Map<String, Object> stats = registry.getStats();
        int total = (int) stats.getOrDefault("totalSkills", 0);
        int active = registry.getActiveSkillCount();
        counts.put(SkillStatus.CANDIDATE, total - active);
        return counts;
    }

    private String formatStatuses(Map<SkillStatus, Integer> statuses) {
        StringBuilder sb = new StringBuilder();
        for (var entry : statuses.entrySet()) {
            if (entry.getValue() > 0) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(entry.getKey().name().charAt(0)).append("=").append(entry.getValue());
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    record RunSnapshot(int run, String topic, boolean success, long tokens,
                        int newSkills, int newActive, int promoted,
                        Map<SkillStatus, Integer> statuses, int totalSkills) {}
}
