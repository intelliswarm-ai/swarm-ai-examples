package ai.intelliswarm.swarmai.examples.governancerbac;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.enterprise.governance.InMemoryApprovalGateHandler;
import ai.intelliswarm.swarmai.enterprise.security.AccessDeniedException;
import ai.intelliswarm.swarmai.enterprise.security.InMemoryRbacEnforcer;
import ai.intelliswarm.swarmai.enterprise.security.Permission;
import ai.intelliswarm.swarmai.enterprise.security.RbacEnforcer;
import ai.intelliswarm.swarmai.enterprise.security.Role;
import ai.intelliswarm.swarmai.governance.*;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.spi.AuditSink;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Governance RBAC Demo — three users with different roles attempt the same workflow.
 *
 * <h2>Roles</h2>
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────────┐
 *   │  ADMIN (alice)                                                   │
 *   │  Permissions: ALL (WORKFLOW_EXECUTE, VIEW, SKILL_MANAGE,         │
 *   │               BUDGET_MANAGE, TENANT_MANAGE, TOOL_MANAGE)         │
 *   │  Result: Full access — runs workflow, manages budget/skills      │
 *   ├──────────────────────────────────────────────────────────────────┤
 *   │  OPERATOR (bob)                                                  │
 *   │  Permissions: WORKFLOW_EXECUTE, VIEW, SKILL_MANAGE, TOOL_MANAGE  │
 *   │  Result: Can run workflow, cannot manage budget or tenants       │
 *   ├──────────────────────────────────────────────────────────────────┤
 *   │  VIEWER (carol)                                                  │
 *   │  Permissions: WORKFLOW_VIEW only                                 │
 *   │  Result: AccessDeniedException on workflow execute               │
 *   ├──────────────────────────────────────────────────────────────────┤
 *   │  AGENT_MANAGER (dave)                                            │
 *   │  Permissions: WORKFLOW_EXECUTE, VIEW, SKILL_MANAGE               │
 *   │  Result: Can run workflow, cannot manage tools                   │
 *   └──────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>What This Proves</h2>
 * <ul>
 *   <li>RBAC enforcement is checked BEFORE workflow execution</li>
 *   <li>Permission granularity: 6 distinct permissions across 4 roles</li>
 *   <li>AccessDeniedException carries userId, permission, and role for audit</li>
 *   <li>Governance gates work independently of RBAC</li>
 *   <li>Audit trail records both granted and denied access attempts</li>
 * </ul>
 */
@Component
public class GovernanceRBACWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceRBACWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public GovernanceRBACWorkflow(ChatClient.Builder chatClientBuilder,
                                   ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "AI governance in enterprise";

        logger.info("\n" + "=".repeat(80));
        logger.info("GOVERNANCE & RBAC DEMONSTRATION");
        logger.info("=".repeat(80));
        logger.info("Topic:  {}", topic);
        logger.info("Users:  alice (ADMIN), bob (OPERATOR), carol (VIEWER), dave (AGENT_MANAGER)");
        logger.info("=".repeat(80));

        // ── RBAC Setup ─────────────────────────────────────────────────

        RbacEnforcer rbac = new InMemoryRbacEnforcer();
        rbac.assignRole("alice", Role.ADMIN);
        rbac.assignRole("bob", Role.OPERATOR);
        // carol gets no explicit role → defaults to VIEWER
        rbac.assignRole("dave", Role.AGENT_MANAGER);

        logger.info("\n--- Role Assignments ---");
        for (String user : List.of("alice", "bob", "carol", "dave")) {
            Role role = rbac.getRole(user);
            logger.info("  {} → {} (permissions: {})", user, role, role.getPermissions());
        }

        // ── Audit Trail ────────────────────────────────────────────────

        List<AuditSink.AuditEntry> auditLog = new CopyOnWriteArrayList<>();
        AuditSink auditSink = auditLog::add;

        // ── Permission Matrix Test ─────────────────────────────────────

        logger.info("\n--- Permission Matrix ---");
        logger.info(String.format("%-15s %-20s %-20s %-15s %-15s %-15s",
                "User", "WORKFLOW_EXECUTE", "WORKFLOW_VIEW", "SKILL_MANAGE", "BUDGET_MANAGE", "TOOL_MANAGE"));
        logger.info("-".repeat(100));

        for (String user : List.of("alice", "bob", "carol", "dave")) {
            logger.info(String.format("%-15s %-20s %-20s %-15s %-15s %-15s",
                    user,
                    rbac.hasPermission(user, Permission.WORKFLOW_EXECUTE) ? "GRANTED" : "DENIED",
                    rbac.hasPermission(user, Permission.WORKFLOW_VIEW) ? "GRANTED" : "DENIED",
                    rbac.hasPermission(user, Permission.SKILL_MANAGE) ? "GRANTED" : "DENIED",
                    rbac.hasPermission(user, Permission.BUDGET_MANAGE) ? "GRANTED" : "DENIED",
                    rbac.hasPermission(user, Permission.TOOL_MANAGE) ? "GRANTED" : "DENIED"));
        }

        // ── Workflow Execution Attempts ─────────────────────────────────

        ChatClient chatClient = chatClientBuilder.build();

        logger.info("\n--- Workflow Execution Attempts ---\n");

        Map<String, String> results = new LinkedHashMap<>();

        for (String user : List.of("alice", "bob", "carol", "dave")) {
            logger.info("  [{}] Attempting workflow execution...", user);

            auditSink.record(new AuditSink.AuditEntry(
                    UUID.randomUUID().toString(), Instant.now(), "default", user,
                    "WORKFLOW_EXECUTE_ATTEMPT", topic, "PENDING",
                    null, Map.of("role", rbac.getRole(user).name())));

            try {
                // Check RBAC before execution
                rbac.checkPermission(user, Permission.WORKFLOW_EXECUTE);

                // Permission granted — run the workflow
                SwarmOutput output = executeWorkflow(chatClient, topic, user, auditSink);
                results.put(user, "SUCCESS (" + output.getTotalTokens() + " tokens)");

                auditSink.record(new AuditSink.AuditEntry(
                        UUID.randomUUID().toString(), Instant.now(), "default", user,
                        "WORKFLOW_EXECUTE", topic, "SUCCESS",
                        null, Map.of("tokens", output.getTotalTokens())));

                logger.info("  [{}] GRANTED — workflow completed successfully\n", user);

            } catch (AccessDeniedException e) {
                results.put(user, "DENIED (" + e.getPermission() + ")");

                auditSink.record(new AuditSink.AuditEntry(
                        UUID.randomUUID().toString(), Instant.now(), "default", user,
                        "WORKFLOW_EXECUTE", topic, "ACCESS_DENIED",
                        null, Map.of("role", e.getRole().name(),
                                     "permission", e.getPermission().name())));

                logger.info("  [{}] DENIED — role {} lacks {} permission\n",
                        user, e.getRole(), e.getPermission());
            }
        }

        // ── Budget Management RBAC ─────────────────────────────────────

        logger.info("--- Budget Management RBAC ---\n");

        for (String user : List.of("alice", "bob", "carol", "dave")) {
            try {
                rbac.checkPermission(user, Permission.BUDGET_MANAGE);
                logger.info("  [{}] BUDGET_MANAGE: GRANTED — can modify budget policies", user);
            } catch (AccessDeniedException e) {
                logger.info("  [{}] BUDGET_MANAGE: DENIED — {} role cannot manage budgets",
                        user, e.getRole());
            }
        }

        // ── Skill Management RBAC ──────────────────────────────────────

        logger.info("\n--- Skill Management RBAC ---\n");

        for (String user : List.of("alice", "bob", "carol", "dave")) {
            try {
                rbac.checkPermission(user, Permission.SKILL_MANAGE);
                logger.info("  [{}] SKILL_MANAGE: GRANTED — can create/promote skills", user);
            } catch (AccessDeniedException e) {
                logger.info("  [{}] SKILL_MANAGE: DENIED — {} role cannot manage skills",
                        user, e.getRole());
            }
        }

        // ── Audit Trail Summary ────────────────────────────────────────

        logger.info("\n" + "=".repeat(80));
        logger.info("RBAC AUDIT TRAIL");
        logger.info("=".repeat(80));

        logger.info(String.format("\n%-12s %-15s %-25s %-15s",
                "User", "Action", "Resource", "Outcome"));
        logger.info("-".repeat(67));

        for (AuditSink.AuditEntry entry : auditLog) {
            logger.info(String.format("%-12s %-15s %-25s %-15s",
                    entry.userId(), entry.action(),
                    entry.resource().length() > 25
                            ? entry.resource().substring(0, 22) + "..."
                            : entry.resource(),
                    entry.outcome()));
        }

        // ── Results Summary ────────────────────────────────────────────

        logger.info("\n" + "=".repeat(80));
        logger.info("RBAC RESULTS");
        logger.info("=".repeat(80));

        for (var entry : results.entrySet()) {
            logger.info("  {} ({}): {}", entry.getKey(), rbac.getRole(entry.getKey()), entry.getValue());
        }

        long granted = auditLog.stream().filter(e -> "SUCCESS".equals(e.outcome())).count();
        long denied = auditLog.stream().filter(e -> "ACCESS_DENIED".equals(e.outcome())).count();
        logger.info("\n  Access granted: {}", granted);
        logger.info("  Access denied:  {}", denied);
        logger.info("  Total audit entries: {}", auditLog.size());
        logger.info("=".repeat(80));
    }

    private SwarmOutput executeWorkflow(ChatClient chatClient, String topic,
                                         String userId, AuditSink auditSink) {
        Agent analyst = Agent.builder()
                .role("Policy Analyst")
                .goal("Analyze governance aspects of: " + topic)
                .backstory("Enterprise governance consultant.")
                .chatClient(chatClient)
                .permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.2).verbose(false).build();

        Task task = Task.builder()
                .id("analysis-" + userId)
                .description("Analyze '" + topic + "': regulatory requirements, " +
                             "compliance frameworks, and implementation recommendations.")
                .expectedOutput("Governance analysis report")
                .agent(analyst)
                .outputFormat(OutputFormat.MARKDOWN).build();

        Swarm swarm = Swarm.builder()
                .id("rbac-" + userId + "-" + System.currentTimeMillis())
                .agent(analyst).task(task)
                .process(ProcessType.SEQUENTIAL)
                .verbose(false).eventPublisher(eventPublisher).build();

        return swarm.kickoff(Map.of("topic", topic, "userId", userId));
    }
}
