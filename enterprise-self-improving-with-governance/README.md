# Enterprise Workflows

This directory contains two enterprise-grade workflow examples that demonstrate SwarmAI's production features for multi-tenant, governed, budget-tracked AI deployments.

---

## 1. GovernedEnterpriseWorkflow

A sequential research-to-report pipeline wrapped with multi-tenancy, budget tracking, and human-in-the-loop governance gates.

### Architecture

```mermaid
graph TD
    subgraph Tenant: acme-research | Budget: $5 / 500K tokens
        RA[Research Analyst<br/>Memory-enabled] --> GATE{Approval Gate<br/>auto-approve 5s}
        GATE --> RW[Report Writer<br/>Memory-enabled]
    end
    RW --> SNAP[Budget Snapshot]
    RW --> OUT[Final Report]
```

### What You'll Learn

- Multi-tenancy with TenantResourceQuota (max concurrent workflows, max tokens, max memory entries)
- Budget tracking with BudgetPolicy (token limits, USD cost caps, WARN vs HARD_STOP enforcement)
- Governance gates with ApprovalGate (human-in-the-loop pause points, auto-approve timeout)
- Tenant-scoped InMemoryMemory for cross-run learning
- Exception handling for TenantQuotaExceededException, BudgetExceededException, GovernanceException

### Run

```bash
./run.sh governed-enterprise "AI agents in enterprise software"
./run.sh governed-enterprise "cloud security" acme-research
```

### Key Code

```java
// Wire all three enterprise features into the swarm
Swarm swarm = Swarm.builder()
        .id("enterprise-governed-" + System.currentTimeMillis())
        .agent(researcher).agent(writer)
        .task(researchTask).task(reportTask)
        .process(ProcessType.SEQUENTIAL)
        .memory(memory)
        .tenantId(tenantId)                     // Multi-tenancy
        .tenantQuotaEnforcer(quotaEnforcer)     // Resource quotas
        .budgetTracker(budgetTracker)           // Cost tracking
        .budgetPolicy(budgetPolicy)             // Budget limits
        .governance(governance)                 // Governance engine
        .approvalGate(researchReviewGate)       // Approval gate
        .build();
```

---

## 2. EnterpriseSelfImprovingWorkflow

The full self-improving workflow (LLM-driven planning, dynamic skill generation, convergence detection) combined with all enterprise governance features.

### Architecture

```
+---------------------------------------------------------------+
|  TENANT: "enterprise-team"     BUDGET: 1M tokens / $5.00      |
|                                                                |
|  PHASE 1: PLANNING                                             |
|    User Query ---> [Planner] ---> WorkflowPlan                 |
|                                                                |
|  PHASE 2: BUILD                                                |
|    Plan ---> Analyst (dynamic tools) + Writer + Reviewer       |
|                                                                |
|  PHASE 3: SELF-IMPROVING LOOP                                  |
|    +-----------+    +--------+    +----------+                 |
|    | Analyst   |--->| Writer |--->| Reviewer |                 |
|    | (Memory,  |    |        |    | (QA Dir) |                 |
|    |  tools)   |    +--------+    +----+-----+                 |
|    +-----------+                       |                       |
|                              +---------+---------+             |
|                              |                   |             |
|                        QUALITY_ISSUES      CAPABILITY_GAPS     |
|                        (feedback loop)     (skill generation)  |
|                              |                   |             |
|                              +-------> APPROVED -+             |
|                                                                |
|  ==== APPROVAL GATE ==== (after analysis, before report)       |
|                                                                |
|  Budget: tracked per LLM call, snapshot logged at end          |
|  Tenant: quota enforced, memory scoped per tenant              |
+---------------------------------------------------------------+
                  |
                  v
      output/enterprise_self_improving_report.md
```

### What You'll Learn

- Self-improving process combined with enterprise governance
- LLM-driven workflow planning with enriched tool catalog and routing hints
- Convergence-based auto-stopping (pass `0` for max iterations)
- Budget tracking across iterative self-improvement cycles
- Multi-tenancy isolation with tenant-scoped memory
- Governance gates within self-improving loops
- Fallback tool strategies (web_search -> http_request -> web_scrape -> curl)

### Run

```bash
./run.sh enterprise-governed "Compare top 5 AI coding assistants"
./run.sh enterprise-governed "Cloud providers AWS vs Azure vs GCP"
./run.sh enterprise-governed "AI coding assistants" 3   # max 3 iterations
./run.sh enterprise-governed "market analysis" 0        # auto-stop
```

### Key Code

```java
// Self-improving process with all enterprise features
Swarm swarm = Swarm.builder()
        .id("enterprise-self-improving-" + System.currentTimeMillis())
        .agent(analyst).agent(writer)
        .managerAgent(reviewer)
        .process(ProcessType.SELF_IMPROVING)
        .config("maxIterations", maxIterations)
        .config("qualityCriteria", plan.qualityCriteria)
        .memory(memory)
        .tenantId(tenantId)
        .tenantQuotaEnforcer(quotaEnforcer)
        .budgetTracker(budgetTracker)
        .budgetPolicy(budgetPolicy)
        .governance(governance)
        .approvalGate(analysisGate)
        .build();
```

---

## Prerequisites (Both Workflows)

- Ollama running locally (or OpenAI/Anthropic API key configured)
- No additional API keys required (tools work with public APIs)
- For the self-improving workflow: `application.yml` configuration for known API endpoints

## Output

Both workflows produce:
- Markdown report files in the `output/` directory
- Console telemetry including:
  - Tenant context (active workflows, memory entries)
  - Budget snapshot (tokens used/limit, estimated cost, utilization %)
  - Governance status (pending approvals, gates passed)
  - Per-task breakdown (character count, prompt/completion tokens)
  - Skills generated/reused/promoted (self-improving only)

## Customization

- Switch `BudgetPolicy.BudgetAction.WARN` to `HARD_STOP` for strict budget enforcement
- Configure real approval handlers (REST API, Slack webhook) instead of auto-approve timeouts
- Add tenant-specific tool restrictions via TenantResourceQuota
- Increase `maxTokenBudget` per tenant for production workloads
- Replace InMemoryMemory with a persistent store (Redis, PostgreSQL) for cross-run learning
- Adjust governance gate trigger from `AFTER_TASK` to `BEFORE_TASK` for pre-approval workflows

## YAML DSL

This workflow can also be defined declaratively in YAML. See [`workflows/enterprise.yaml`](src/main/resources/workflows/enterprise.yaml):

```bash
# Load and run via YAML instead of Java
Swarm swarm = swarmLoader.load("workflows/enterprise.yaml",
    Map.of("topic", "AI trends"));
SwarmOutput output = swarm.kickoff(Map.of());
```

The YAML definition includes budget tracking, governance gates, workflow hooks, tenant isolation, and agent-level memory.
