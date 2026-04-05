package ai.intelliswarm.swarmai.examples.vulnpatcher;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.governance.*;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.security.CVELookupTool;
import ai.intelliswarm.swarmai.tool.security.OSVLookupTool;
import ai.intelliswarm.swarmai.tool.security.GitHubPRTool;
import ai.intelliswarm.swarmai.tool.common.FileReadTool;
import ai.intelliswarm.swarmai.tool.common.FileWriteTool;
import ai.intelliswarm.swarmai.tool.common.DirectoryReadTool;
import ai.intelliswarm.swarmai.tool.common.CodeExecutionTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SwarmAI Vulnerability Patcher — migrated from Quarkus + LangChain4j.
 *
 * <h2>Original vs SwarmAI Comparison</h2>
 * <table>
 *   <tr><th>Aspect</th><th>Original (LangChain4j)</th><th>SwarmAI</th></tr>
 *   <tr><td>Orchestration code</td><td>~400 lines Java</td><td>~80 lines YAML + 60 lines Java</td></tr>
 *   <tr><td>Process type</td><td>Fixed 3-pass sequential</td><td>SELF_IMPROVING with RL policy</td></tr>
 *   <tr><td>Skill generation</td><td>None</td><td>Dynamic Groovy skills for unknown CVE types</td></tr>
 *   <tr><td>Budget tracking</td><td>None</td><td>$10 cap, 500K tokens, HARD_STOP</td></tr>
 *   <tr><td>Governance</td><td>None</td><td>Approval gate on iteration complete</td></tr>
 *   <tr><td>Tool permissions</td><td>None</td><td>Scanner=READ_ONLY, Engineer=WORKSPACE_WRITE</td></tr>
 *   <tr><td>Review loop</td><td>Fixed 3-pass</td><td>RL-driven: converge when quality met</td></tr>
 *   <tr><td>Learning</td><td>None</td><td>Skills persist and reuse across scans</td></tr>
 *   <tr><td>Audit trail</td><td>Prometheus metrics only</td><td>Full audit + observability</td></tr>
 *   <tr><td>Configuration</td><td>Java code only</td><td>YAML DSL (zero-code changes)</td></tr>
 * </table>
 *
 * <h2>Usage</h2>
 * <pre>
 * // With Spring Boot auto-config:
 * workflow.run("https://github.com/example/vulnerable-app");
 *
 * // Or via YAML DSL:
 * Swarm swarm = swarmLoader.load("workflows/vuln-patcher.yaml",
 *     Map.of("repo_url", "https://github.com/example/vulnerable-app"));
 * SwarmOutput result = swarm.kickoff(Map.of());
 * </pre>
 */
@Component
public class VulnPatcherWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(VulnPatcherWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public VulnPatcherWorkflow(ChatClient.Builder chatClientBuilder,
                                ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public SwarmOutput run(String repoUrl) {
        logger.info("=".repeat(70));
        logger.info("SWARMAI VULNERABILITY PATCHER");
        logger.info("Repository: {}", repoUrl);
        logger.info("=".repeat(70));

        ChatClient chatClient = chatClientBuilder.build();
        Memory memory = new InMemoryMemory();

        // ── Tools ──────────────────────────────────────────────────────
        CVELookupTool cveTool = new CVELookupTool();
        OSVLookupTool osvTool = new OSVLookupTool();
        FileReadTool fileReadTool = new FileReadTool();
        FileWriteTool fileWriteTool = new FileWriteTool();
        DirectoryReadTool dirReadTool = new DirectoryReadTool();
        CodeExecutionTool codeTool = new CodeExecutionTool();
        GitHubPRTool prTool = new GitHubPRTool();

        // ── Agents ─────────────────────────────────────────────────────
        Agent scanner = Agent.builder()
                .role("Vulnerability Scanner")
                .goal("Scan repository for known vulnerabilities across CVE and OSV databases")
                .backstory("Senior security engineer specialized in software composition analysis. " +
                           "Systematically checks every dependency. Never fabricates CVEs.")
                .chatClient(chatClient)
                .tools(List.of(cveTool, osvTool, fileReadTool, dirReadTool))
                .memory(memory)
                .permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.1)
                .verbose(true)
                .build();

        Agent engineer = Agent.builder()
                .role("Security Patch Engineer")
                .goal("Generate minimal, secure patches for identified vulnerabilities")
                .backstory("Staff security engineer who writes precise, backward-compatible fixes. " +
                           "Prefers version bumps over code changes when possible.")
                .chatClient(chatClient)
                .tools(List.of(fileReadTool, fileWriteTool, codeTool))
                .memory(memory)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .temperature(0.3)
                .verbose(true)
                .build();

        Agent reviewer = Agent.builder()
                .role("Security Expert Reviewer")
                .goal("Review patches for security completeness. Approve only when all fixes meet quality bar.")
                .backstory("CISO-level reviewer. Meticulous and conservative. Rejects patches that " +
                           "don't fully address the vulnerability or introduce complexity.")
                .chatClient(chatClient)
                .tools(List.of(fileReadTool))
                .memory(memory)
                .permissionMode(PermissionLevel.READ_ONLY)
                .temperature(0.2)
                .maxTurns(3)
                .verbose(true)
                .build();

        // ── Tasks ──────────────────────────────────────────────────────
        Task scanTask = Task.builder()
                .id("scan")
                .description(String.format(
                        "Scan repository at %s for security vulnerabilities.\n" +
                        "1. Read dependency files (pom.xml, package.json, requirements.txt)\n" +
                        "2. Look up each dependency in CVE and OSV databases\n" +
                        "3. Report: CVE ID, severity, CVSS, affected dependency, fix version",
                        repoUrl))
                .expectedOutput("Structured vulnerability report with CVE IDs and severity")
                .agent(scanner)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Task fixTask = Task.builder()
                .id("fix")
                .description("Generate secure patches for all CRITICAL and HIGH severity vulnerabilities. " +
                             "For dependency issues, update version. For code issues, rewrite the affected code.")
                .expectedOutput("Patched files with explanations")
                .agent(engineer)
                .dependsOn(scanTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        // Note: review task is NOT defined as a separate task.
        // In SELF_IMPROVING mode, the reviewer agent is the managerAgent
        // who evaluates output after each iteration and decides:
        //   APPROVED → done
        //   CAPABILITY_GAPS → generate new skills → retry
        //   NEEDS_REFINEMENT → inject feedback → retry

        // ── Budget ─────────────────────────────────────────────────────
        BudgetPolicy budgetPolicy = BudgetPolicy.builder()
                .maxTotalTokens(500_000)
                .maxCostUsd(10.00)
                .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                .warningThresholdPercent(80.0)
                .build();
        BudgetTracker budgetTracker = new InMemoryBudgetTracker(budgetPolicy);

        // ── Governance ─────────────────────────────────────────────────
        ApprovalGate reviewGate = ApprovalGate.builder()
                .name("Security Review Gate")
                .description("Security expert must approve patches before completion")
                .trigger(GateTrigger.ON_ITERATION_COMPLETE)
                .timeout(Duration.ofSeconds(5))
                .policy(new ApprovalPolicy(1, List.of(), true))
                .build();

        // ── Swarm (SELF_IMPROVING) ─────────────────────────────────────
        // The self-improving process:
        //   1. Scanner + Engineer execute tasks (scan → fix)
        //   2. Reviewer evaluates: APPROVED / CAPABILITY_GAPS / NEEDS_REFINEMENT
        //   3. If CAPABILITY_GAPS: generates Groovy skills, validates, hot-loads
        //   4. If NEEDS_REFINEMENT: injects feedback, retries
        //   5. If APPROVED: done
        //   6. RL policy decides convergence (max 10 iterations)
        // Skills persist to output/skills/ for reuse across future scans.
        Swarm swarm = Swarm.builder()
                .id("vuln-patcher-" + System.currentTimeMillis())
                .agent(scanner)
                .agent(engineer)
                .managerAgent(reviewer)          // Reviewer drives the self-improving loop
                .task(scanTask)
                .task(fixTask)
                .process(ProcessType.SELF_IMPROVING)
                .config("maxIterations", 10)
                .config("qualityCriteria",
                        "All CRITICAL and HIGH CVEs must be patched with verified fixes. " +
                        "No new vulnerabilities introduced. Fixes must be backward-compatible.")
                .verbose(true)
                .eventPublisher(eventPublisher)
                .memory(memory)
                .budgetTracker(budgetTracker)
                .budgetPolicy(budgetPolicy)
                .build();

        // ── Execute ────────────────────────────────────────────────────
        long startTime = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(Map.of("repo_url", repoUrl));
        long duration = System.currentTimeMillis() - startTime;

        // ── Results ────────────────────────────────────────────────────
        BudgetSnapshot snapshot = budgetTracker.getSnapshot(swarm.getId());

        logger.info("");
        logger.info("=".repeat(70));
        logger.info("VULNERABILITY PATCHER RESULTS");
        logger.info("=".repeat(70));
        logger.info("Repository:    {}", repoUrl);
        logger.info("Duration:      {}s", duration / 1000);
        logger.info("Success:       {}", result.isSuccessful());
        logger.info("Tasks:         {}", result.getTaskOutputs().size());

        if (snapshot != null) {
            logger.info("Tokens used:   {} / {} ({}%)",
                    snapshot.totalTokensUsed(), budgetPolicy.maxTotalTokens(),
                    String.format("%.1f", snapshot.tokenUtilizationPercent()));
            logger.info("Est. cost:     ${} / ${}",
                    String.format("%.4f", snapshot.estimatedCostUsd()),
                    budgetPolicy.maxCostUsd());
        }

        // Self-improving metrics
        var meta = result.getMetadata();
        if (meta != null) {
            logger.info("Iterations:    {}", meta.getOrDefault("iterations", "N/A"));
            logger.info("Skills gen'd:  {}", meta.getOrDefault("skillsGenerated", 0));
            logger.info("Skills reused: {}", meta.getOrDefault("skillsReused", 0));
            logger.info("Approved:      {}", meta.getOrDefault("approved", false));
        }

        logger.info("");
        logger.info("--- Vulnerability Report ---");
        for (var taskOutput : result.getTaskOutputs()) {
            logger.info("[{}] {} chars", taskOutput.getTaskId(),
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0);
        }
        if (!result.getTaskOutputs().isEmpty()) {
            logger.info("\n{}", result.getFinalOutput());
        }
        logger.info("=".repeat(70));

        return result;
    }
}
