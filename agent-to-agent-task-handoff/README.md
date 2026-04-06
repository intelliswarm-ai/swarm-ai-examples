# Agent-to-Agent Task Handoff

Two agents in sequence: a Researcher gathers information, then an Editor refines it.

## Architecture

```
[Researcher] --> [Editor] --> output
```

## What You'll Learn

- Chain agents using task dependencies so one agent's output feeds into the next
- Control agent depth with `maxTurns`
- Use permission levels to restrict agent capabilities

## Run

```bash
./agent-to-agent-task-handoff/run.sh
# or
./run.sh agent-handoff "quantum computing applications"
```

## Key Concepts

- **`Task.builder().dependsOn(previousTask)`** — declares that a task requires another task's output.
- **`maxTurns`** — caps how many LLM calls an agent can make per task (Researcher=2, Editor=1).
- **`PermissionLevel`** — controls what tools an agent may use (`READ_ONLY` vs `WORKSPACE_WRITE`).
- In `SEQUENTIAL` mode, the swarm executes tasks in order and threads outputs through dependencies.

## Source

- [`AgentHandoffExample.java`](src/main/java/ai/intelliswarm/swarmai/examples/basics/AgentHandoffExample.java)
