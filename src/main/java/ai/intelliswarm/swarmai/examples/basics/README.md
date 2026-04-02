# Basic Examples

Single-concept examples that each teach ONE SwarmAI feature in isolation. Start here if you're new to the framework.

---

## Bare Minimum

The simplest possible SwarmAI setup: one agent, one task, sequential process, no tools.

**What you'll learn:** How to create an Agent, a Task, and a Swarm, then execute with `swarm.kickoff()`.

### Architecture

```
[Summarizer] --> output
```

### Run

```bash
./scripts/run.sh bare-minimum
# or
./scripts/run.sh bare-minimum "renewable energy"
```

### Key Concepts

- **Agent** -- defined with `role`, `goal`, and `backstory` to shape the LLM's behavior.
- **Task** -- a unit of work with a `description` and `expectedOutput`.
- **Swarm** -- wires agents and tasks together under a `ProcessType` (here `SEQUENTIAL`).
- **`swarm.kickoff(inputs)`** -- starts execution; the inputs map passes runtime variables.
- **WorkflowMetricsCollector** -- optional helper that tracks timing and budget.

---

## Tool Calling

A single agent equipped with the `CalculatorTool` to perform precise arithmetic.

**What you'll learn:** How to attach tools to an agent and let the LLM invoke them during execution.

### Architecture

```
[Math Tutor] --uses--> (CalculatorTool) --> output
```

### Run

```bash
./scripts/run.sh tool-calling
# or
./scripts/run.sh tool-calling "What is 17% of 2450?"
```

### Key Concepts

- **`Agent.builder().tools(List.of(...))`** -- registers one or more tools the agent can call.
- **Spring-managed tools** -- `CalculatorTool` is a Spring `@Component`, injected automatically.
- **`toolHook`** -- optional callback that fires on every tool invocation (used here for metrics).
- The agent decides *when* to call a tool based on the task description; you don't hard-code invocations.

---

## Agent Handoff

Two agents in sequence: a Researcher gathers information, then an Editor refines it.

**What you'll learn:** How to chain agents using task dependencies so one agent's output feeds into the next.

### Architecture

```
[Researcher] --> [Editor] --> output
```

### Run

```bash
./scripts/run.sh agent-handoff
# or
./scripts/run.sh agent-handoff "quantum computing applications"
```

### Key Concepts

- **`Task.builder().dependsOn(previousTask)`** -- declares that a task requires another task's output.
- **`maxTurns`** -- caps how many LLM calls an agent can make per task (Researcher=2, Editor=1).
- **`PermissionLevel`** -- controls what tools an agent may use (`READ_ONLY` vs `WORKSPACE_WRITE`).
- In `SEQUENTIAL` mode, the swarm executes tasks in order and threads outputs through dependencies.

---

## Context Variables

Three agents in a pipeline -- Outliner, Drafter, Polisher -- all sharing context through the inputs map.

**What you'll learn:** How to pass shared state (topic, audience, tone, word count) across multiple agents via `swarm.kickoff(inputs)`.

### Architecture

```
[Outliner] --> [Drafter] --> [Polisher] --> output
       \          |           /
        +-- shared context --+
          (topic, audience,
           tone, wordCount)
```

### Run

```bash
./scripts/run.sh context-variables
# or
./scripts/run.sh context-variables "microservices architecture"
```

### Key Concepts

- **Inputs map** -- a `Map<String, Object>` passed to `swarm.kickoff()` carrying shared state.
- **String interpolation** -- task descriptions use `String.format` to inject context variables.
- **`dependsOn` chaining** -- `outlineTask -> draftTask -> polishTask` forms a three-stage pipeline.
- **`SwarmOutput.getSuccessRate()`** -- reports what fraction of tasks completed successfully.
- Each agent sees only the previous task's output plus the interpolated context, not the full history.

---

## Multi-Turn

A single agent that reasons across multiple LLM turns with automatic context compaction.

**What you'll learn:** How to enable multi-turn reasoning with `maxTurns` and prevent token overflow using `CompactionConfig`.

### Architecture

```
[Deep Researcher]
   |  turn 1: identify key aspects
   |  turn 2: analyze each aspect
   |  turn 3: synthesize findings
   |  ... (up to 5 turns)
   +--> output
```

### Run

```bash
./scripts/run.sh multi-turn
# or
./scripts/run.sh multi-turn "the impact of LLMs on software engineering"
```

### Key Concepts

- **`maxTurns(5)`** -- allows the agent up to 5 LLM calls, using CONTINUE/DONE markers to control flow.
- **`CompactionConfig.of(3, 4000)`** -- after 3 turns, older context is summarized to stay under 4000 tokens.
- Multi-turn is useful when a task requires iterative reasoning that cannot fit in a single prompt.
- The agent autonomously decides when to continue and when its analysis is complete.
