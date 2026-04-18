# demo-recorder

Externalised trace recorder for SwarmAI examples. Subscribes to the framework's
`SwarmEvent` bus, captures the whole run as JSON, and drops a sibling
`baseline.json` produced by calling the same model with no workflow.

Outputs feed `intelliswarm.ai/website/src/assets/demos/` — the same JSON the
Angular `/demos/:slug` page plays back.

## Quick start

```bash
# From the swarm-ai-examples/ directory.
# Ollama is auto-started by run.sh, mistral:latest is auto-pulled.

./demo-recorder/record-demo.sh stock-market-analysis
```

Produces:

```
demos/stock-market-analysis/runs/mistral-ollama/
├── swarm.json        ← left panel of the website
└── baseline.json     ← right panel of the website
```

Record the curated launch demo against Mistral (currently just `stock-market-analysis` — see `LAUNCH_DEMOS` in the script):

```bash
./demo-recorder/record-demo.sh launch mistral-ollama
```

Against GPT-4o (set `OPENAI_API_KEY` first):

```bash
SPRING_PROFILES_ACTIVE=openai ./demo-recorder/record-demo.sh all gpt-4o
```

Re-record over existing output:

```bash
FORCE=1 ./demo-recorder/record-demo.sh stock-market-analysis
```

## How it works

The recorder is a Spring Boot auto-configured bean that activates only when
`swarmai.demo.record=true` (or the env var `SWARMAI_DEMO_RECORD=true`). When
inactive, it contributes zero beans and examples run unchanged.

The `TranscriptRecorder` subscribes to `SwarmEvent` via `@EventListener` —
nothing in the example code changes. Each event becomes a step in the trace
JSON. The first event starts the clock; `SWARM_COMPLETED` triggers the flush.

The `BaselineRunner` is a separate Spring Boot entrypoint that loads the
demo's `prompt.md`, calls `ChatClient.prompt().user(prompt).call()`, and
writes `baseline.json` with the same model config.

## Configuration properties

All under prefix `swarmai.demo`:

| Property           | Env var                           | Default    | Notes |
|---                 |---                                |---         |---    |
| `record`           | `SWARMAI_DEMO_RECORD`             | `false`    | Master on/off switch |
| `slug`             | `SWARMAI_DEMO_SLUG`               | —          | Demo directory name |
| `model`            | `SWARMAI_DEMO_MODEL`              | —          | Model identifier for the path |
| `model-display-name` | `SWARMAI_DEMO_MODEL_DISPLAY_NAME` | —        | Shown in UI chip |
| `provider`         | `SWARMAI_DEMO_PROVIDER`           | inferred   | `ollama` / `openai` / `anthropic` |
| `out-dir`          | `SWARMAI_DEMO_OUT_DIR`            | `demos`    | Root output directory |
| `framework-version`| `SWARMAI_DEMO_FRAMEWORK_VERSION`  | —          | Written into reproducibility block |
| `framework-git-sha`| `SWARMAI_DEMO_FRAMEWORK_GIT_SHA`  | —          | Written into reproducibility block |
| `temperature`      | `SWARMAI_DEMO_TEMPERATURE`        | `0.0`      | Written into reproducibility block |
| `seed`             | `SWARMAI_DEMO_SEED`               | `42`       | Written into reproducibility block |
| `top-p`            | `SWARMAI_DEMO_TOP_P`              | `1.0`      | Written into reproducibility block |
| `max-tokens`       | `SWARMAI_DEMO_MAX_TOKENS`         | `2048`     | Written into reproducibility block |

## Publishing a trace to the website

```bash
cp -r demos/* ../intelliswarm.ai/website/src/assets/demos/
```

The Angular dev server picks up the JSON without a restart.

## Regeneration as a regression check

Every trace embeds a `reproducibility` block (pinned model version, seed,
framework git SHA, prompt hash). Re-recording gives you a deterministic
diff target. A dedicated regression CLI (planned — task #9) will compare
cost / wall-time / final-output similarity against the canonical run and
fail CI on drift beyond thresholds.

## Limitations (v0.1)

- **Token counts for swarm runs** come from whatever the framework puts in
  `SwarmEvent.metadata`. A follow-up will add a `ChatClient` advisor that
  records per-call token usage directly from `ChatResponse.getMetadata().getUsage()`.
- **Cost** is always `0.0` for swarm traces; the baseline runner has the hook
  but no provider cost oracle. For Ollama traces that's correct.
- **History archival** (`demos/<slug>/history/…`) is not yet implemented;
  traces are written straight to `runs/<model>/`.
- **Workflow hash** is not computed; `promptHash` is populated by the baseline
  runner but not by the swarm recorder (needs wiring to the DSL loader).
