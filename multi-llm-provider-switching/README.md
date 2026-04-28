# Multi-Provider Comparison

Demonstrates model-agnostic agent design by running the same analysis task across different temperature settings and model variants, then comparing the results side by side.

## Architecture

**Phase 1: Temperature Sweep** (same model, different temperatures)

```mermaid
graph LR
    T1[Agent @ temp=0.1] --> R1[Result 1]
    T2[Agent @ temp=0.5] --> R2[Result 2]
    T3[Agent @ temp=0.9] --> R3[Result 3]
    R1 & R2 & R3 --> CMP1[Comparison Table]
```

**Phase 2: Model Variants** (same temperature, different models)

```mermaid
graph LR
    M1[mistral:7b] --> R4[Result 4]
    M2[llama3:8b] --> R5[Result 5]
    M3[gemma:7b] --> R6[Result 6]
    R4 & R5 & R6 --> CMP2[Comparison Table]
```

## What You'll Learn

- Using `Agent.builder().modelName()` to target specific models within a provider
- Using `Agent.builder().temperature()` to control output determinism vs. creativity
- Running identical tasks across multiple configurations for A/B comparison
- Extracting structured metrics (word count, sections, tokens, themes) from agent output
- Building comparison tables and theme frequency analysis from multiple runs

## Prerequisites

- Ollama with `mistral:latest` (required for temperature sweep)
- For model variant comparison, also pull: `ollama pull llama3:8b` and `ollama pull gemma:7b`
- Runs with graceful error handling if some models are unavailable

## Run

```bash
# Default topic: "AI agent frameworks in 2026"
./run.sh multi-provider

# Custom topic
./run.sh multi-provider "the future of autonomous vehicles"
```

Sample output (illustrative — your run will show your data):

```
================================================================================
MULTI-PROVIDER COMPARISON WORKFLOW
================================================================================
Topic:         AI agent frameworks in 2026
Comparison 1:  Temperature sweep on configured model
Comparison 2:  Model variants (mistral:7b, llama3:8b, gemma:7b) -- must be available in provider
================================================================================

PHASE 1: Temperature Sweep (same model, different temperatures)
  >>> Run 1: Deterministic (0.1)
    OK: 387 words, 5 sections, 4128 ms
  >>> Run 2: Balanced (0.5)
    OK: 412 words, 5 sections, 4502 ms
  >>> Run 3: Creative (0.9)
    OK: 463 words, 6 sections, 5117 ms

PHASE 2: Model Variants (different models, temperature=0.5)
  >>> Model: mistral:7b
    OK: 401 words, 5 sections, 4380 ms
  >>> Model: llama3:8b
    OK: 438 words, 5 sections, 5921 ms
  >>> Model: gemma:7b
    FAILED: model 'gemma:7b' not found, try 'ollama pull gemma:7b'

================================================================================
TEMPERATURE SWEEP
  Config                 | Status | Words  | Sects | Duration  | Tokens  | Themes
  ------------------------------------------------------------------------------
  Deterministic (0.1)    | OK     | 387    | 5     | 4128ms    | 612     | Multi-Agent, Orchestration, Tool Use...
  Balanced (0.5)         | OK     | 412    | 5     | 4502ms    | 648     | Multi-Agent, Orchestration, Reasoning...
  Creative (0.9)         | OK     | 463    | 6     | 5117ms    | 731     | Autonomy, Multi-Agent, Safety...
================================================================================

================================================================================
MODEL VARIANTS
  Config                 | Status | Words  | Sects | Duration  | Tokens  | Themes
  ------------------------------------------------------------------------------
  mistral:7b             | OK     | 401    | 5     | 4380ms    | 634     | Multi-Agent, Orchestration, Tool Use...
  llama3:8b              | OK     | 438    | 5     | 5921ms    | 692     | Multi-Agent, Reasoning, Enterprise...
  gemma:7b               | ERROR  | model 'gemma:7b' not found, try 'ollama pull gemma:7b'
================================================================================

================================================================================
THEME FREQUENCY (5 successful runs)
  Multi-Agent          ***** (5/5)
  Orchestration        **** (4/5)
  Tool Use             *** (3/5)
  Reasoning            *** (3/5)
  Enterprise           ** (2/5)
  Autonomy             * (1/5)
  Safety               * (1/5)

TAKEAWAYS:
  1. Low temperature -> consistent, factual; high -> creative, varied
  2. Different models emphasize different aspects of the same topic
  3. Use Agent.modelName() to target specific models within one provider
================================================================================
```

## How It Works

The workflow runs in two phases. Phase 1 performs a temperature sweep on the default model at 0.1 (deterministic), 0.5 (balanced), and 0.9 (creative), producing the same structured analysis at each setting. Phase 2 runs the same analysis across three model variants (`mistral:7b`, `llama3:8b`, `gemma:7b`) at a fixed temperature of 0.5, using `Agent.builder().modelName()` to target each model. Each run produces a `RunResult` capturing word count, section count, token usage, duration, and extracted themes. After all runs complete, comparison tables and a theme frequency analysis show how temperature and model choice affect output style, length, and thematic emphasis.

## Key Code

```java
// Temperature sweep: same model, different creativity levels
for (int i = 0; i < TEMPERATURES.length; i++) {
    tempResults.add(runAnalysis(topic, null, TEMPERATURES[i], TEMP_LABELS[i], metrics));
}

// Model variant comparison via Agent.modelName()
for (String model : MODEL_VARIANTS) {
    modelResults.add(runAnalysis(topic, model, 0.5, model, metrics));
}

// Inside runAnalysis: modelName switches the target model
Agent.Builder ab = Agent.builder()
        .role("Technology Analyst")
        .chatClient(chatClient)
        .temperature(temperature)
        .permissionMode(PermissionLevel.READ_ONLY);
if (modelName != null) ab.modelName(modelName);
Agent analyst = ab.build();
```

## Customization

- Add or change models in `MODEL_VARIANTS` to compare any Ollama-hosted or API-backed model
- Adjust the `TEMPERATURES` array to test different creativity levels
- Modify the analysis prompt to benchmark different task types (summarization, creative writing, coding)
- Add timing thresholds to flag slow models automatically

## YAML DSL

This workflow can also be defined declaratively in YAML. See [`workflows/multiprovider.yaml`](src/main/resources/workflows/multiprovider.yaml):

```java
// Load and run via YAML instead of Java
Swarm swarm = swarmLoader.load("workflows/multiprovider.yaml",
    Map.of("topic", "AI Safety"));
SwarmOutput output = swarm.kickoff(Map.of());
```

The YAML definition includes per-agent model selection (openai/gpt-4o-mini, anthropic/claude-sonnet).
