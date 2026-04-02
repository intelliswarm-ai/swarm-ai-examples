# Agent-to-Agent Debate Workflow

An example that demonstrates agent-to-agent communication through a structured
debate. Two autonomous agents argue opposing sides of a proposition over multiple
rounds, reading each other's arguments from shared state and producing targeted
rebuttals. After three rounds, a neutral judge agent evaluates the full transcript
and declares a winner.

## Graph Topology

```
[START] -> [proponent] -> [opponent] --(round < 3)--> [proponent]  (loop back)
                                      --(round >= 3)--> [judge] -> [END]
```

## State Channels

| Channel          | Type           | Purpose                                  |
|------------------|----------------|------------------------------------------|
| `proposition`    | lastWriteWins  | The debate topic                         |
| `debate_log`     | appender       | Accumulates all arguments and the verdict|
| `round`          | counter        | Tracks completed debate rounds           |
| `proponent_arg`  | lastWriteWins  | Latest argument from the proponent       |
| `opponent_arg`   | lastWriteWins  | Latest argument from the opponent        |
| `verdict`        | lastWriteWins  | Final judge ruling                       |

## Agents

- **Proponent** -- Argues FOR the proposition. Reads the opponent's previous
  argument from state and produces a rebuttal plus new supporting points.
- **Opponent** -- Argues AGAINST the proposition. Reads the proponent's argument
  from state and dismantles it while advancing counter-arguments.
- **Judge** -- Reads the full `debate_log` (all rounds), scores both sides on
  evidence quality, logical coherence, rebuttal effectiveness, and rhetorical
  persuasion, then declares a winner with detailed reasoning.

## Framework Features Demonstrated

- **SwarmGraph conditional edges** -- loop back for additional rounds or advance
  to the judge based on the round counter.
- **Appender channel** -- the `debate_log` accumulates every argument across all
  rounds, giving the judge a complete transcript without manual concatenation.
- **Counter channel** -- the `round` counter increments automatically when each
  node writes `1L`, tracking progress through the debate.
- **Agent-to-agent communication** -- agents do not call each other directly.
  Instead they read and write shared state channels, a pattern that keeps agents
  decoupled while enabling multi-turn dialogue.

## Usage

```bash
# Default proposition
java -jar swarmai-framework.jar agent-debate

# Custom proposition
java -jar swarmai-framework.jar agent-debate "Open source AI models will surpass proprietary ones"
```

## Output

The workflow prints each round's arguments as they are generated, followed by the
full debate transcript and the judge's final verdict including scores and reasoning.
