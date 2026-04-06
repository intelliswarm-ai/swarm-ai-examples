# Agent with Tool Calling

A single agent equipped with the `CalculatorTool` to perform precise arithmetic.

## Architecture

```
[Math Tutor] --uses--> (CalculatorTool) --> output
```

## What You'll Learn

- How to attach tools to an agent
- Let the LLM decide when to invoke tools during execution
- Spring-managed tool injection

## Run

```bash
./agent-with-tool-calling/run.sh
# or
./run.sh tool-calling "What is 17% of 2450?"
```

## Key Concepts

- **`Agent.builder().tools(List.of(...))`** — registers tools the agent can call.
- **Spring-managed tools** — `CalculatorTool` is a `@Component`, injected automatically.
- **`toolHook`** — optional callback that fires on every tool invocation (used here for metrics).
- The agent decides *when* to call a tool based on the task description; you don't hard-code invocations.

## Source

- [`ToolCallingExample.java`](src/main/java/ai/intelliswarm/swarmai/examples/basics/ToolCallingExample.java)
