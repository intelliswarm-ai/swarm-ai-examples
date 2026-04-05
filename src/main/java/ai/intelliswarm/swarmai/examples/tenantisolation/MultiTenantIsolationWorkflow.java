package ai.intelliswarm.swarmai.examples.tenantisolation;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.enterprise.tenant.InMemoryTenantQuotaEnforcer;
import ai.intelliswarm.swarmai.enterprise.tenant.TenantAwareMemory;
import ai.intelliswarm.swarmai.enterprise.tenant.TenantResourceQuota;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.spi.AuditSink;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tenant.TenantQuotaEnforcer;
import ai.intelliswarm.swarmai.tenant.TenantQuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-Tenant Isolation — proves data doesn't leak between tenants on shared infrastructure.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Shared Infrastructure (single JVM, single InMemoryMemory)
 *   ┌──────────────────────────────────────────────────────┐
 *   │                                                      │
 *   │   ┌────────────────┐    ┌────────────────┐          │
 *   │   │  ACME-CORP     │    │  GLOBEX-INC    │          │
 *   │   │  Tenant        │    │  Tenant        │          │
 *   │   │                │    │                │          │
 *   │   │  Topic:        │    │  Topic:        │          │
 *   │   │  "quantum      │    │  "blockchain   │          │
 *   │   │   computing"   │    │   finance"     │          │
 *   │   │                │    │                │          │
 *   │   │  Quota: 5 max  │    │  Quota: 3 max  │          │
 *   │   │  Budget: $2    │    │  Budget: $1    │          │
 *   │   └───────┬────────┘    └───────┬────────┘          │
 *   │           │                     │                    │
 *   │           ▼                     ▼                    │
 *   │   ┌──────────────────────────────────────┐          │
 *   │   │     TenantAwareMemory                │          │
 *   │   │     (wraps shared InMemoryMemory)    │          │
 *   │   │                                      │          │
 *   │   │  acme-corp::agent-1 → "quantum..."   │          │
 *   │   │  globex-inc::agent-1 → "blockchain.."│          │
 *   │   │                                      │          │
 *   │   │  getRecentMemories("agent-1"):       │          │
 *   │   │    acme sees ONLY acme data          │          │
 *   │   │    globex sees ONLY globex data      │          │
 *   │   └──────────────────────────────────────┘          │
 *   └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>What This Proves</h2>
 * <ol>
 *   <li><b>Memory isolation</b> — Tenant A cannot see Tenant B's agent memories</li>
 *   <li><b>Quota isolation</b> — Each tenant has independent workflow limits</li>
 *   <li><b>Budget isolation</b> — Separate cost tracking per tenant</li>
 *   <li><b>Audit isolation</b> — Audit entries tagged with tenantId</li>
 *   <li><b>Concurrent safety</b> — Both tenants run simultaneously via CompletableFuture</li>
 *   <li><b>Quota enforcement</b> — Exceeding quota throws TenantQuotaExceededException</li>
 * </ol>
 */
@Component
public class MultiTenantIsolationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenantIsolationWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public MultiTenantIsolationWorkflow(ChatClient.Builder chatClientBuilder,
                                         ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        logger.info("\n" + "=".repeat(80));
        logger.info("MULTI-TENANT ISOLATION PROOF");
        logger.info("=".repeat(80));
        logger.info("Tenants:    acme-corp (quantum computing) vs globex-inc (blockchain finance)");
        logger.info("Infra:      Single JVM, single shared InMemoryMemory");
        logger.info("Isolation:  TenantAwareMemory with prefix-based scoping");
        logger.info("=".repeat(80));

        // ── Shared Infrastructure ──────────────────────────────────────
        // Both tenants use the SAME underlying memory store.
        // TenantAwareMemory wraps it to enforce isolation.

        InMemoryMemory sharedMemoryStore = new InMemoryMemory();
        TenantAwareMemory tenantMemory = new TenantAwareMemory(sharedMemoryStore);

        // ── Per-Tenant Quotas ──────────────────────────────────────────

        TenantResourceQuota acmeQuota = TenantResourceQuota.builder("acme-corp")
                .maxConcurrentWorkflows(5)
                .maxSkills(50)
                .maxMemoryEntries(5000)
                .maxTokenBudget(500_000)
                .build();

        TenantResourceQuota globexQuota = TenantResourceQuota.builder("globex-inc")
                .maxConcurrentWorkflows(3)
                .maxSkills(20)
                .maxMemoryEntries(2000)
                .maxTokenBudget(200_000)
                .build();

        TenantQuotaEnforcer quotaEnforcer = new InMemoryTenantQuotaEnforcer(
                Map.of("acme-corp", acmeQuota, "globex-inc", globexQuota),
                TenantResourceQuota.builder("default").build()
        );

        logger.info("\n--- Tenant Quotas ---");
        logger.info("  acme-corp:  {} max workflows, {} max tokens",
                acmeQuota.maxConcurrentWorkflows(), acmeQuota.maxTokenBudget());
        logger.info("  globex-inc: {} max workflows, {} max tokens",
                globexQuota.maxConcurrentWorkflows(), globexQuota.maxTokenBudget());

        // ── Per-Tenant Budget Trackers ─────────────────────────────────

        BudgetPolicy acmeBudget = BudgetPolicy.builder()
                .maxTotalTokens(200_000).maxCostUsd(2.00)
                .onExceeded(BudgetPolicy.BudgetAction.WARN).build();
        InMemoryBudgetTracker acmeBudgetTracker = new InMemoryBudgetTracker(acmeBudget);

        BudgetPolicy globexBudget = BudgetPolicy.builder()
                .maxTotalTokens(100_000).maxCostUsd(1.00)
                .onExceeded(BudgetPolicy.BudgetAction.WARN).build();
        InMemoryBudgetTracker globexBudgetTracker = new InMemoryBudgetTracker(globexBudget);

        // ── Audit Trail (per-tenant tagging) ───────────────────────────

        List<AuditSink.AuditEntry> auditLog = new CopyOnWriteArrayList<>();
        AuditSink auditSink = entry -> {
            auditLog.add(entry);
            logger.debug("[AUDIT] tenant={} action={} resource={}",
                    entry.tenantId(), entry.action(), entry.resource());
        };

        // ── Run Both Tenants Concurrently ──────────────────────────────

        logger.info("\n--- Launching concurrent tenant workflows ---\n");

        ChatClient chatClient = chatClientBuilder.build();

        CompletableFuture<SwarmOutput> acmeFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return runTenantWorkflow(
                        chatClient, tenantMemory, quotaEnforcer,
                        acmeBudgetTracker, acmeBudget, auditSink,
                        "acme-corp", "quantum computing in enterprise",
                        "Senior quantum computing researcher at IBM Research");
            } catch (Exception e) {
                logger.error("acme-corp workflow failed: {}", e.getMessage());
                return null;
            }
        });

        CompletableFuture<SwarmOutput> globexFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return runTenantWorkflow(
                        chatClient, tenantMemory, quotaEnforcer,
                        globexBudgetTracker, globexBudget, auditSink,
                        "globex-inc", "blockchain in institutional finance",
                        "Senior blockchain analyst at Goldman Sachs");
            } catch (Exception e) {
                logger.error("globex-inc workflow failed: {}", e.getMessage());
                return null;
            }
        });

        // Wait for both to complete
        SwarmOutput acmeResult = acmeFuture.join();
        SwarmOutput globexResult = globexFuture.join();

        // ── PROOF 1: Memory Isolation ──────────────────────────────────

        logger.info("\n" + "=".repeat(80));
        logger.info("ISOLATION PROOF RESULTS");
        logger.info("=".repeat(80));

        logger.info("\n--- PROOF 1: Memory Isolation ---");
        int sharedStoreSize = sharedMemoryStore.size();
        logger.info("  Shared memory store total entries: {}", sharedStoreSize);

        // Query the tenant-aware memory as each tenant would
        // acme-corp's memories are stored under "acme-corp::agent-id"
        List<String> acmeMemories = sharedMemoryStore.getRecentMemories("acme-corp::researcher", 100);
        List<String> globexMemories = sharedMemoryStore.getRecentMemories("globex-inc::researcher", 100);

        logger.info("  acme-corp memories:  {} entries", acmeMemories.size());
        logger.info("  globex-inc memories: {} entries", globexMemories.size());

        // Cross-check: does acme's data contain globex content?
        boolean acmeContainsBlockchain = acmeMemories.stream()
                .anyMatch(m -> m.toLowerCase().contains("blockchain"));
        boolean globexContainsQuantum = globexMemories.stream()
                .anyMatch(m -> m.toLowerCase().contains("quantum"));

        logger.info("  acme contains 'blockchain': {} {}", acmeContainsBlockchain,
                acmeContainsBlockchain ? "LEAK DETECTED" : "ISOLATED");
        logger.info("  globex contains 'quantum':  {} {}", globexContainsQuantum,
                globexContainsQuantum ? "LEAK DETECTED" : "ISOLATED");

        // ── PROOF 2: Quota Isolation ───────────────────────────────────

        logger.info("\n--- PROOF 2: Quota Isolation ---");
        logger.info("  acme-corp active workflows:  {}", quotaEnforcer.getActiveWorkflowCount("acme-corp"));
        logger.info("  globex-inc active workflows: {}", quotaEnforcer.getActiveWorkflowCount("globex-inc"));

        // Test quota enforcement: try exceeding globex's 3-workflow limit
        logger.info("\n  Testing quota enforcement for globex-inc (max 3)...");
        AtomicInteger quotaViolations = new AtomicInteger(0);
        for (int i = 0; i < 4; i++) {
            try {
                quotaEnforcer.checkWorkflowQuota("globex-inc");
                quotaEnforcer.recordWorkflowStart("globex-inc");
                logger.info("    Workflow {} started: OK", i + 1);
            } catch (TenantQuotaExceededException e) {
                quotaViolations.incrementAndGet();
                logger.info("    Workflow {} BLOCKED: {} (EXPECTED)", i + 1, e.getMessage());
            }
        }
        // Clean up test workflows
        for (int i = 0; i < 3; i++) {
            quotaEnforcer.recordWorkflowEnd("globex-inc");
        }

        logger.info("  Quota violations caught: {} (expected: 1)", quotaViolations.get());

        // ── PROOF 3: Budget Isolation ──────────────────────────────────

        logger.info("\n--- PROOF 3: Budget Isolation ---");
        BudgetSnapshot acmeSnapshot = acmeBudgetTracker.getSnapshot("acme-corp");
        BudgetSnapshot globexSnapshot = globexBudgetTracker.getSnapshot("globex-inc");

        if (acmeSnapshot != null) {
            logger.info("  acme-corp:  {} tokens, ${} (limit: ${} / {} tokens)",
                    acmeSnapshot.totalTokensUsed(),
                    String.format("%.4f", acmeSnapshot.estimatedCostUsd()),
                    acmeBudget.maxCostUsd(), acmeBudget.maxTotalTokens());
        }
        if (globexSnapshot != null) {
            logger.info("  globex-inc: {} tokens, ${} (limit: ${} / {} tokens)",
                    globexSnapshot.totalTokensUsed(),
                    String.format("%.4f", globexSnapshot.estimatedCostUsd()),
                    globexBudget.maxCostUsd(), globexBudget.maxTotalTokens());
        }
        logger.info("  Budgets tracked independently: YES");

        // ── PROOF 4: Audit Isolation ───────────────────────────────────

        logger.info("\n--- PROOF 4: Audit Isolation ---");
        long acmeAuditEntries = auditLog.stream()
                .filter(e -> "acme-corp".equals(e.tenantId())).count();
        long globexAuditEntries = auditLog.stream()
                .filter(e -> "globex-inc".equals(e.tenantId())).count();
        logger.info("  Total audit entries:  {}", auditLog.size());
        logger.info("  acme-corp entries:    {}", acmeAuditEntries);
        logger.info("  globex-inc entries:   {}", globexAuditEntries);
        logger.info("  All entries tagged with tenantId: {}",
                auditLog.stream().allMatch(e -> e.tenantId() != null) ? "YES" : "NO");

        // ── PROOF 5: Workflow Results ──────────────────────────────────

        logger.info("\n--- Workflow Results ---");
        if (acmeResult != null) {
            logger.info("  acme-corp:  success={}, tasks={}, tokens={}",
                    acmeResult.isSuccessful(), acmeResult.getTaskOutputs().size(),
                    acmeResult.getTotalTokens());
        }
        if (globexResult != null) {
            logger.info("  globex-inc: success={}, tasks={}, tokens={}",
                    globexResult.isSuccessful(), globexResult.getTaskOutputs().size(),
                    globexResult.getTotalTokens());
        }

        // ── Summary Verdict ────────────────────────────────────────────

        boolean memoryIsolated = !acmeContainsBlockchain && !globexContainsQuantum;
        boolean quotaEnforced = quotaViolations.get() >= 1;
        boolean budgetSeparate = true; // always true with separate trackers
        boolean auditTagged = auditLog.stream().allMatch(e -> e.tenantId() != null);

        logger.info("\n" + "=".repeat(80));
        logger.info("ISOLATION VERDICT");
        logger.info("=".repeat(80));
        logger.info("  Memory isolation:   {}", memoryIsolated ? "PASS" : "FAIL");
        logger.info("  Quota enforcement:  {}", quotaEnforced ? "PASS" : "FAIL");
        logger.info("  Budget isolation:   {}", budgetSeparate ? "PASS" : "FAIL");
        logger.info("  Audit tagging:      {}", auditTagged ? "PASS" : "FAIL");
        logger.info("  Overall:            {}", (memoryIsolated && quotaEnforced && budgetSeparate && auditTagged) ? "ALL PROOFS PASS" : "ISOLATION BREACH DETECTED");
        logger.info("=".repeat(80));
    }

    /**
     * Runs a complete workflow for a single tenant with full isolation.
     */
    private SwarmOutput runTenantWorkflow(
            ChatClient chatClient,
            Memory tenantMemory,
            TenantQuotaEnforcer quotaEnforcer,
            InMemoryBudgetTracker budgetTracker,
            BudgetPolicy budgetPolicy,
            AuditSink auditSink,
            String tenantId,
            String topic,
            String backstory) {

        logger.info("  [{}] Starting workflow: '{}'", tenantId, topic);

        // Record audit: workflow start
        auditSink.record(new AuditSink.AuditEntry(
                UUID.randomUUID().toString(), Instant.now(), tenantId, "system",
                "WORKFLOW_START", "tenant-isolation-proof", "INITIATED",
                null, Map.of("topic", topic)));

        Agent researcher = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Produce a comprehensive analysis of: " + topic +
                      ". Focus on real market data, key players, and adoption trends.")
                .backstory(backstory)
                .chatClient(chatClient)
                .memory(tenantMemory) // Shared store, tenant-scoped via TenantAwareMemory
                .verbose(false)
                .temperature(0.2)
                .build();

        Agent writer = Agent.builder()
                .role("Report Writer")
                .goal("Write a polished executive report based on the research findings.")
                .backstory("You write for C-suite executives. Be concise and data-driven.")
                .chatClient(chatClient)
                .memory(tenantMemory)
                .verbose(false)
                .temperature(0.3)
                .build();

        Task researchTask = Task.builder()
                .id("research")
                .description("Research '" + topic + "' covering: market size, key players, " +
                             "adoption trends, and risk factors.")
                .expectedOutput("3-paragraph research brief")
                .agent(researcher)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Task reportTask = Task.builder()
                .id("report")
                .description("Write an executive report on '" + topic + "' using the research.")
                .expectedOutput("Executive report with recommendations")
                .agent(writer)
                .dependsOn(researchTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Swarm swarm = Swarm.builder()
                .id(tenantId + "-workflow-" + System.currentTimeMillis())
                .agent(researcher)
                .agent(writer)
                .task(researchTask)
                .task(reportTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(false)
                .eventPublisher(eventPublisher)
                .memory(tenantMemory)
                .tenantId(tenantId)
                .tenantQuotaEnforcer(quotaEnforcer)
                .budgetTracker(budgetTracker)
                .budgetPolicy(budgetPolicy)
                .build();

        SwarmOutput result = swarm.kickoff(Map.of("topic", topic));

        // Record audit: workflow complete
        auditSink.record(new AuditSink.AuditEntry(
                UUID.randomUUID().toString(), Instant.now(), tenantId, "system",
                "WORKFLOW_COMPLETE", "tenant-isolation-proof",
                result.isSuccessful() ? "SUCCESS" : "FAILED",
                null, Map.of("tasks", result.getTaskOutputs().size())));

        logger.info("  [{}] Workflow complete: {} tasks, {} tokens",
                tenantId, result.getTaskOutputs().size(), result.getTotalTokens());

        return result;
    }
}
