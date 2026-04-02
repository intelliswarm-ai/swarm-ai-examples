# Customer Support Triage

Demonstrates intelligent query routing and agent handoff using SwarmGraph conditional edges, with a quality gate that can escalate to a senior agent.

## Architecture

```
 [START]
    |
    v
 [classify] -- Classifier Agent detects category
    |
    +-- BILLING ----> [billing]   --+
    +-- TECHNICAL --> [technical] --+
    +-- ACCOUNT ----> [account]  --+--> [satisfaction] -- QA Agent scores response
    +-- GENERAL ----> [general]  --+         |
                                        (APPROVED)  (ESCALATE)
                                            |            |
                                          [END]     [escalate] -- Senior Director
                                                         |
                                                       [END]
```

## What You'll Learn

- Building graph-based workflows with `SwarmGraph.create()` and `CompiledSwarm`
- Conditional routing via `addConditionalEdge()` based on runtime state
- State management with `StateSchema` and typed `Channels` (lastWriteWins)
- Creating inline agents within `addNode()` lambdas for dynamic behavior
- Implementing a quality gate pattern (satisfaction check with escalation path)
- Agent handoff between specialist roles

## Prerequisites

- Ollama with `mistral:latest` (or any configured model)
- No additional API keys required

## Run

```bash
# Default query: "I was charged twice for my subscription last month and I need a refund"
./scripts/run.sh customer-support

# Custom query
./scripts/run.sh customer-support "I cannot log into my account after resetting my password"
```

## How It Works

The workflow uses a `SwarmGraph` with six nodes. First, a Classifier agent categorizes the incoming query into BILLING, TECHNICAL, ACCOUNT, or GENERAL. A conditional edge routes to the appropriate specialist node, each of which creates an inline agent with domain-specific expertise. All specialists converge at a Satisfaction node where a QA agent evaluates the response for quality. Another conditional edge either approves the response (ending the workflow) or escalates to a Senior Support Director who has authority to override policies and offer compensation. State flows through `AgentState` channels using `lastWriteWins` semantics.

## Key Code

```java
CompiledSwarm compiled = SwarmGraph.create()
        .addAgent(placeholderAgent)
        .addTask(placeholderTask)
        .addEdge(SwarmGraph.START, "classify")

        .addNode("classify", state -> {
            // Classifier agent detects category from query
            TaskOutput output = classifier.executeTask(task, List.of());
            return Map.of("category", extractCategory(output.getRawOutput()));
        })

        // Conditional routing by detected category
        .addConditionalEdge("classify", state -> {
            return switch (state.valueOrDefault("category", "GENERAL")) {
                case "BILLING"   -> "billing";
                case "TECHNICAL" -> "technical";
                case "ACCOUNT"   -> "account";
                default          -> "general";
            };
        })

        .addNode("billing", state -> runSpecialist(state, ...))
        .addEdge("billing", "satisfaction")

        // Quality gate: approve or escalate
        .addConditionalEdge("satisfaction", state -> {
            return state.valueOrDefault("escalated", false)
                    ? "escalate" : SwarmGraph.END;
        })

        .addEdge("escalate", SwarmGraph.END)
        .stateSchema(schema)
        .compileOrThrow();
```

## Customization

- Add new categories by extending the `extractCategory()` method and adding corresponding specialist nodes
- Adjust the satisfaction criteria in the QA agent's prompt to change escalation sensitivity
- Replace the inline specialist agents with persistent agents that have tools (e.g., database lookup, refund API)
- Add `CheckpointSaver` for state persistence across restarts

## YAML DSL

This workflow can also be defined declaratively in YAML. See [`workflows/graph-customer-support.yaml`](../../../../../resources/workflows/graph-customer-support.yaml):

```java
// Load and run via YAML instead of Java
CompiledWorkflow workflow = swarmLoader.loadWorkflow("workflows/graph-customer-support.yaml");
SwarmOutput output = workflow.kickoff(Map.of());
```

The YAML definition includes graph-based category routing (BILLING/TECHNICAL/ACCOUNT/GENERAL).
