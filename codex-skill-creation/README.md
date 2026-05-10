# Codex-Driven Skill Creation

Use the **Codex CLI** as a coding agent that creates SwarmAI skills on the fly.
Replaces the default `SkillGenerator` (one ChatClient round-trip in, one
SKILL.md text blob out) with a full-iterating coding agent that runs in a
sandboxed workspace and writes the SKILL.md as a real file you can read back.

## What you'll see

```
======================================================================
  Codex Skill Creation Demo
======================================================================
Capability gap:
  Compute the haversine distance in kilometers between two GPS coordinates ...

Codex auth   : OK (cached-login=true, OPENAI_API_KEY=false)
Codex home   : /home/you/.codex

======================================================================
  Spawning Codex agent
======================================================================
This may take a minute or two — the agent is iterating in a sandboxed workspace.

======================================================================
  Codex produced a skill in 47239 ms
======================================================================
name        : haversine_distance
type        : CODE
description : Compute great-circle distance between two coordinates...
category    : computation
tags        : [geo, math, computation]

======================================================================
  SKILL.md
======================================================================
  ---
  name: haversine_distance
  description: ...
  ...

======================================================================
  Validating skill
======================================================================
validation : PASSED
  integration tests : 2 passed / 0 failed
```

If validation fails, the workflow asks Codex to **refine** the skill given the
validation errors and prints the result of the second pass.

## Architecture

```mermaid
graph TD
    GAP[Capability Gap<br/>"compute haversine"] --> ENG[CodingAgentSkillGenerator<br/>implements SkillEngine]
    ENG -->|"hands a workspace +<br/>prompt to write SKILL.md"| SPAWN[CodexSubagentSpawner]
    SPAWN -->|"spawns child process"| CODEX[codex exec --skip-git-repo-check --full-auto<br/>iterating in EphemeralDirectoryWorkspace]
    CODEX -->|"writes SKILL.md<br/>to workspace root"| WS[(workspace/SKILL.md)]
    WS -->|"read before close"| ENG
    ENG -->|"GeneratedSkill"| VAL[SkillValidator]
    VAL -->|FAILED| REFINE[engine.refine<br/>second Codex pass]
    VAL -->|PASSED| OUT[Skill ready to register]
    REFINE -->|second SKILL.md| OUT
```

The non-obvious bit: `CodingAgentSkillGenerator` wraps the workspace in a
`NonClosingWorkspace` so the spawner's race-fix close doesn't reap the
SKILL.md before the generator can read it back.

## Prerequisites

| Requirement | Why |
|---|---|
| `codex` CLI installed and on `$PATH` | the workflow spawns it as a child process |
| `codex login` **OR** `OPENAI_API_KEY` env var | `CodingAgentAuth.requireCodexAuth()` fails fast otherwise |
| SwarmAI `1.0.21-SNAPSHOT` (or later) in your local Maven cache | the new `CodingAgentSkillGenerator` / `CodingAgentAuth` classes ship in that version. From the framework root: `mvn -pl swarmai-core install -DskipTests` |

Verify Codex is functional before running the example:

```bash
codex --version
codex exec --skip-git-repo-check --full-auto "echo hello"
```

## Run

```bash
# Default — generate a haversine-distance skill
./codex-skill-creation/run.sh

# Custom gap
./codex-skill-creation/run.sh "Parse a CSV string into a list of column averages"

# Or via the central runner
./run.sh codex-skill-creation "Convert a city name into its IANA timezone"
```

## Configuration knobs

The example is intentionally pinned to sensible defaults. The two values you'd
most likely want to tweak live in the workflow class:

| Constant | Default | What it controls |
|---|---|---|
| `TIMEOUT` | `5 minutes` | wall-clock cap on a single Codex generation attempt |
| `EXISTING_TOOLS` | `calculator`, `http_request`, `json_transform` | tools advertised to the agent so it can compose them via `tools.X.execute(...)` in CODE skills |

For Docker-isolated execution (Codex running inside a container with no host
HOME), swap `EphemeralDirectoryWorkspace` for a `GitWorktreeWorkspace`
composed with `DockerWorkspace`, and bind-mount `CodingAgentAuth.codexHome()`
read-only into the container at `/root/.codex`. See the javadoc on
`CodingAgentAuth` for the exact pattern.

## Where this fits

- `SkillEngine` is the new SPI extracted from `SkillGenerator`. Both
  implementations share the same `generate(gap, tools)` and
  `refine(skill, errors)` contract.
- `SelfImprovingProcess` accepts a `SkillEngine` via `setSkillEngine(...)` —
  so once you've validated the Codex backend with this example, you can plug
  it into a full self-improving swarm just as easily.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `Codex authentication missing` | run `codex login` or export `OPENAI_API_KEY` |
| `Codex did not produce a usable skill` | either `codex` is not on `$PATH`, the agent timed out, or the agent wrote no `SKILL.md`. Re-run with `--logging.level.ai.intelliswarm.swarmai.skill=DEBUG` to see what came back. |
| Validation fails after one refinement | the gap may be too vague — add concrete input/output expectations to the gap description |
