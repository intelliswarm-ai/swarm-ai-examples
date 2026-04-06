# YAML Workflow Definition

Define and run workflows using YAML instead of Java. The YAML DSL supports agents, tasks, budget, governance gates, tool hooks, and template variables — no compilation required.

## Architecture

```
YAML file --> SwarmLoader.load() --> Swarm --> kickoff() --> output
```

## What You'll Learn

- Define complete workflows in YAML without writing Java
- Use template variables for runtime substitution
- Configure budget, governance gates, and tool hooks declaratively
- Load and execute YAML workflows with `SwarmLoader`

## Run

```bash
./yaml-workflow-definition/run.sh
# or
./run.sh yaml-dsl "AI agent frameworks"
```

## Example YAML

```yaml
swarm:
  process: SEQUENTIAL
  budget:
    maxTokens: 100000
    maxCostUsd: 5.0
  agents:
    researcher:
      role: "Research Analyst"
      goal: "Research {{topic}} thoroughly"
      temperature: 0.2
  tasks:
    research:
      description: "Research {{topic}}"
      agent: researcher
      outputFormat: MARKDOWN
```

```java
Swarm swarm = swarmLoader.load("workflow.yaml",
    Map.of("topic", "AI Safety"));
SwarmOutput output = swarm.kickoff(Map.of());
```

## Key Concepts

- **SwarmLoader** — loads YAML into a runnable `Swarm` or `CompiledWorkflow`
- **Template variables** — `{{topic}}`, `{{outputDir}}` substituted at load time
- **All process types** — SEQUENTIAL, PARALLEL, HIERARCHICAL, ITERATIVE, SELF_IMPROVING, COMPOSITE, SWARM
- **Governance gates** — approval requirements between tasks
- **Tool hooks** — audit, rate-limit, sanitize, deny — all configurable in YAML

## Source

- [`YamlDslWorkflow.java`](src/main/java/ai/intelliswarm/swarmai/examples/yamldsl/YamlDslWorkflow.java)
