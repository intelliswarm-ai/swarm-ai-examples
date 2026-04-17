# Self-Evolving Swarm

Demonstrates **transparent self-evolution**: the swarm is configured as SEQUENTIAL, but `Swarm.kickoff()` automatically applies learned optimizations from prior runs — switching to PARALLEL when appropriate.

This is **self-evolution**, not self-healing: the swarm becomes smarter about how to use capabilities it already has. No code changes needed between runs.

## How It Works

```
Run 1:  SEQUENTIAL (default)
        ↓ ImprovementCollector detects: PROCESS_SUITABILITY
        ↓ "3 independent tasks at depth 0 — parallel may reduce latency"
        ↓ EvolutionEngine persists PROCESS_TYPE_CHANGE to H2
        
Run 2:  Swarm.kickoff() consults evolution advisor
        ↓ Finds PROCESS_TYPE_CHANGE in H2
        ↓ Transparently switches to PARALLEL
        ↓ ~40-60% faster, zero code changes
```

The example always builds the swarm as `ProcessType.SEQUENTIAL` with 3 independent research tasks. The evolution happens inside `Swarm.kickoff()` — the framework evolves itself.

## Run It

```bash
# Run 1: SEQUENTIAL (learns)
./self-evolving-swarm/run.sh "AI orchestration"

# Run 2: PARALLEL (evolved — transparently applied by Swarm.kickoff())
./self-evolving-swarm/run.sh "AI orchestration"
```

## What You'll See

### Run 1 (Initial)
```
SELF-EVOLVING SWARM — Run #1
  Configured:   SEQUENTIAL (3 independent tasks)
  Status:       INITIAL — will observe and learn for next run
  Evolutions:   0 in H2

Sequential Process: Executing 3 tasks
Duration: ~210s

Self-Improvement Report:
  Observations collected:    2
  Proposals generated:       1 (TOKEN_OPTIMIZATION)
  Evolution persisted:       PROCESS_TYPE_CHANGE (confidence: 0.70)
```

### Run 2 (Evolved)
```
SELF-EVOLVING SWARM — Run #3
  Configured:   SEQUENTIAL (3 independent tasks)
  Status:       EVOLVED — applying learned optimization
  Evolutions:   2 in H2

[self-evolving-swarm] Self-evolution applied: SEQUENTIAL → PARALLEL (learned from prior runs)
Parallel Process: 3 tasks organized into 1 layers
Duration: ~120s (42% faster)
```

## Self-Improvement Pipeline

```
Workflow → ImprovementCollector (9 observation types)
    → H2 persistent store (survives JVM restarts)
    → PatternExtractor (cross-workflow evidence from DB)
    → ImprovementClassifier (Tier 1/2/3)
    → LedgerStore (counters accumulated)
    → DailyTelemetryScheduler (POST to intelliswarm.ai)
    → Ledger visible at intelliswarm.ai/ledger
```

## Internal vs External Observations

| Routing | Types | Action |
|---------|-------|--------|
| **INTERNAL** (self-evolution) | PROCESS_SUITABILITY, EXPENSIVE_TASK, CONVERGENCE_PATTERN, TOOL_SELECTION | EvolutionEngine applies at runtime |
| **EXTERNAL** (framework gap) | FAILURE, ANTI_PATTERN, DECISION_QUALITY, COORDINATION_QUALITY | Proposal POSTed to intelliswarm.ai/contribute |

Internal observations drive runtime evolution — the swarm restructures itself using existing capabilities. External observations report structural gaps that require framework code changes.

## Studio

View the evolution timeline in the browser:

```
GET /evolution.html          — Visual timeline UI
GET /api/studio/evolutions   — JSON API
```

## Architecture

```
┌─────────────────────────────────────────────────┐
│  SelfEvolvingSwarmWorkflow                      │
│  Always builds: ProcessType.SEQUENTIAL          │
│                                                 │
│  Swarm.kickoff()                                │
│    ├─ EvolutionAdvisor.advise()                 │
│    │   └─ Reads H2: PROCESS_TYPE_CHANGE?        │
│    │       ├─ Yes → switch to PARALLEL           │
│    │       └─ No  → keep SEQUENTIAL              │
│    ├─ createProcess() (uses evolved type)        │
│    └─ execute()                                  │
│                                                 │
│  After completion (async):                      │
│    ├─ ImprovementCollector → observations        │
│    ├─ EvolutionEngine → persist to H2            │
│    └─ TelemetryScheduler → POST to ledger        │
└─────────────────────────────────────────────────┘
```
