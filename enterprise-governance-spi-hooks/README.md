# Enterprise Governance with SPI Hooks

Enterprise-grade workflow with governance gates, budget tracking, multi-tenancy, and SPI extension points (AuditSink, LicenseProvider, MeteringSink).

## Architecture

```
[Researcher] --> [Governance Gate] --> [Writer] --> output
     |                                     |
     +-- Budget Tracking ----- Tenant Isolation --+
     +-- AuditSink ---------- MeteringSink -------+
```

## What You'll Learn

- SPI extension points for audit, licensing, and metering
- Human-in-the-loop approval gates
- Multi-tenant isolation with `TenantContext`
- Budget enforcement with configurable policies

## Run

```bash
./enterprise-governance-spi-hooks/run.sh
# or
./run.sh enterprise-self-improving "AI governance in enterprise"
```

## Key Concepts

- **AuditSink** — SPI for recording audit events (tool calls, decisions, access)
- **LicenseProvider** — SPI for license validation (COMMUNITY, ENTERPRISE, TRIAL)
- **MeteringSink** — SPI for usage metering and billing integration
- **ApprovalGate** — human-in-the-loop gate that pauses workflow for approval
- **TenantContext** — thread-local tenant isolation for multi-tenant deployments

## Source

- [`GovernedEnterpriseWorkflow.java`](src/main/java/ai/intelliswarm/swarmai/examples/enterprise/GovernedEnterpriseWorkflow.java)
