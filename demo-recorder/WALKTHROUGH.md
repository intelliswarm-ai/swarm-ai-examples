# Recording a demo end-to-end — walkthrough

A concrete recording session from zero to "it plays on intelliswarm.ai".
Use this alongside [README.md](README.md) (reference docs).

---

## The 60-second version

```bash
# 1. Install framework SNAPSHOT to local M2 (only after a swarm-ai change)
cd /mnt/d/Intelliswarm.ai/swarm-ai && mvn install -DskipTests

# 2. Record one demo against one model
cd /mnt/d/Intelliswarm.ai/swarm-ai-examples
./demo-recorder/record-demo.sh stock-market-analysis gpt-4o AAPL

# 3. Sync to website + preview
cp -r demos/stock-market-analysis/runs/gpt-4o \
      ../intelliswarm.ai/website/src/assets/demos/stock-market-analysis/runs/
cd ../intelliswarm.ai/website && npm start
# → http://localhost:4200/demos/stock-market-analysis?model=gpt-4o
```

If that works, skip the rest. The sections below explain *why*, what to
check, and what to do when it doesn't.

---

## 1. Prerequisites (one-time setup)

### Environment

- **Java 21** + Maven (`mvn -v` should show 3.9+)
- **Ollama running** locally if you'll record against `mistral-ollama` — `run.sh` auto-pulls the model, so you don't need to do it yourself
- **API keys** in `swarm-ai-examples/.env` for cloud providers:
  ```
  OPENAI_API_KEY=sk-proj-...
  ANTHROPIC_API_KEY=sk-ant-...
  ```
  `record-demo.sh` auto-loads `.env` — no `export` needed.

### Local Maven snapshot

The examples depend on the current swarm-ai SNAPSHOT. After any framework change:

```bash
cd /mnt/d/Intelliswarm.ai/swarm-ai
mvn install -DskipTests
```

This populates `~/.m2/repository/ai/intelliswarm/...` so `swarm-ai-examples` can resolve the local SNAPSHOT. Only needed when the framework version in `swarm-ai-examples/pom.xml` ends in `-SNAPSHOT` AND that version isn't on Maven Central yet.

---

## 2. What the recorder produces

One `record-demo.sh` invocation writes two files per `(slug, model, framework-version)`:

```
demos/<slug>/runs/<model>/<framework-version>/
├── <slug>.json       ← swarm trace (left panel on the website)
└── baseline.json     ← raw-LLM trace (right panel)
```

Example:

```
demos/stock-market-analysis/runs/gpt-4o/1.0.5/
├── stock-market-analysis.json   (84s, ~110k tokens, real Finnhub citations)
└── baseline.json                (8s, ~700 tokens, GPT-4o alone)
```

Both files follow [the trace schema](../../intelliswarm.ai/docs/DEMO_TRACE_SCHEMA.md) — the website's Angular `TracePlayer` component reads them verbatim.

---

## 3. Picking a `(slug, model, args)` combination

### Slug

Must be a directory under `swarm-ai-examples/demos/`. The script maps slug → example ID for `run.sh`:

| Slug                    | Example ID run by run.sh |
|---                      |---                       |
| `stock-market-analysis` | `stock-analysis`         |

### Model

Pass any string OpenAI/Anthropic/Ollama accepts. Known display names are in `record-demo.sh`:

- `gpt-4o`, `gpt-4o-mini`, `gpt-5`, `gpt-5-mini`, `gpt-5.4`, `gpt-5.4-mini`
- `o1`, `o1-mini`
- `claude-sonnet-4-6`, `claude-opus-4-6`
- `mistral-ollama` / `llama-*` / `qwen-*`

Anything else renders with the raw model string.

### Workflow args

Everything after positional 2 goes to `run.sh` unchanged:

```bash
./demo-recorder/record-demo.sh stock-market-analysis gpt-4o AAPL
```

**Critical:** when you pass workflow args, edit `prompt.md` to match so the baseline answers the same question the swarm solves. For example, if you record stock-analysis with `AAPL`, the prompt.md should say "analyze AAPL", not "analyze IMPP".

---

## 4. A real recording session — stock-market-analysis × gpt-4o × AAPL

### Step 1 — verify the prompt matches

```bash
cat demos/stock-market-analysis/prompt.md
```

Expected:
```
Produce an institutional-quality investment analysis report for **AAPL**.
…
```

If it says IMPP (or anything else), edit it. The baseline will read this verbatim.

### Step 2 — run

```bash
./demo-recorder/record-demo.sh stock-market-analysis gpt-4o AAPL
```

What happens in order:

1. **Ollama detection** — `run.sh` pings Ollama. Harmless even for OpenAI runs; the `openai` profile excludes Ollama's autoconfig so no local call happens.
2. **Build** — if `target/swarmai-examples-1.0.0-SNAPSHOT.jar` is missing, a `mvn package -DskipTests -q` runs (~20s).
3. **Swarm phase** (2–4 min for stock-analysis):
   ```
   [INFO] Running: stock-analysis
   SwarmEvent [SWARM_STARTED]   ...
   SwarmEvent [AGENT_STARTED]   ...
   SwarmEvent [TOOL_STARTED]    tool=finnhub ...
   SwarmEvent [TOOL_COMPLETED]  tool=finnhub ...
   SwarmEvent [LLM_REQUEST]     ...
   SwarmEvent [AGENT_COMPLETED] ...
   SwarmEvent [SWARM_COMPLETED] ...
   DemoRecorder: wrote .../runs/gpt-4o/1.0.5/stock-market-analysis.json (44 steps, ...)
   ```
4. **Baseline phase** (~10s):
   ```
   Starting BaselineRunner using Java 21 ...
   BaselineRunner: wrote .../runs/gpt-4o/1.0.5/baseline.json (731 tokens, 8172 ms)
   ```

### Step 3 — verify the trace

```bash
python3 <<'EOF'
import json
for p in [
  'demos/stock-market-analysis/runs/gpt-4o/1.0.5/stock-market-analysis.json',
  'demos/stock-market-analysis/runs/gpt-4o/1.0.5/baseline.json',
]:
  d = json.load(open(p))
  m = d['metrics']
  kinds = set(s['kind'] for s in d['steps'])
  print(f"{d['side']:>8} · {len(d['steps'])} steps · "
        f"{m['wallTimeMs']/1000:.1f}s · {m['totalTokens']} tok · "
        f"tool_calls: {m['toolCalls']} · kinds: {sorted(kinds)}")
EOF
```

You want to see:
- **swarm** side: ≥ 20 steps, `tool_call` in kinds (Finnhub / web_search invocations), non-empty `finalOutput.content`
- **baseline** side: exactly 2 steps (`llm_request`, `final`), non-empty `finalOutput.content`

If `tool_calls: 0` on the swarm side, the `ToolEventInterceptor` isn't active — re-check `mvn install` on swarm-ai.

### Step 4 — sync to the website

```bash
cp -r demos/stock-market-analysis/runs/gpt-4o \
      ../intelliswarm.ai/website/src/assets/demos/stock-market-analysis/runs/
```

**Only the model dir.** Don't sync meta.json or prompt.md — those live on the website and we keep them aligned manually.

### Step 5 — preview

```bash
cd ../intelliswarm.ai/website
npm start          # if not already running
```

Open `http://localhost:4200/demos/stock-market-analysis?model=gpt-4o`.

What you should see:
- Both panels populated
- Swarm side: tool_call rows with real tool names + durations, agent bubbles with citations like `[Finnhub: metric.grossMarginTTM]`, a final report
- Baseline side: GPT-4o's response (may hallucinate a price, which is the asymmetry)
- Delta strip: meaningful token / cost / wall-time deltas

### Step 6 — deploy

```bash
cd /mnt/d/Intelliswarm.ai/intelliswarm.ai
./deploy.ps1       # Windows
# or
./deploy.sh        # Linux/WSL (if you have it)
```

Deploy builds Angular, uploads to S3, invalidates CloudFront. Live in ~90 seconds.

---

## 5. Common failures & fixes

| Symptom | Likely cause | Fix |
|---|---|---|
| `Prompt file empty/missing: .../prompt.md` | prompt.md doesn't exist in `demos/<slug>/` | copy from the website: `cp ../intelliswarm.ai/website/src/assets/demos/<slug>/prompt.md demos/<slug>/` |
| `OPENAI_API_KEY is not set` | `.env` missing or malformed | check `swarm-ai-examples/.env`; the script sources it automatically |
| `Could not find artifact ai.intelliswarm:swarmai-core:...` | local M2 doesn't have this SNAPSHOT | `cd ../swarm-ai && mvn install -DskipTests` |
| `Unable to find a single main class` (JAR build) | Both `SwarmAIExamplesApplication` and `BaselineRunner` are `@SpringBootApplication` | already fixed — the parent pom pins the repackage main class. If this recurs, check `swarm-ai-examples/pom.xml` has `<mainClass>` on the repackage execution |
| Swarm trace has 0 `tool_call` steps | `ToolEventInterceptor` not on classpath | confirm the file exists under `swarm-ai/swarmai-core/.../tool/instrument/` and that you re-ran `mvn install` |
| Website shows "Recording pending" | trace file 404s | check the path — website reads `runs/<model>/<frameworkVersion>/<slug>.json` where `frameworkVersion` comes from `meta.json` |

---

## 6. Re-recording

To overwrite an existing trace (same slug, same model, same framework version):

```bash
FORCE=1 ./demo-recorder/record-demo.sh stock-market-analysis gpt-4o AAPL
```

Without `FORCE=1` the script skips any `(slug, model, version)` pair that already has files.

## 7. Recording the full launch set

Edits to `LAUNCH_DEMOS` inside the script control what `launch` records:

```bash
./demo-recorder/record-demo.sh launch gpt-4o
# ~2-3 minutes on gpt-4o; ~$0.08 in OpenAI tokens
```

This runs the curated launch demos (currently just `stock-market-analysis`) in sequence against one model. Doesn't accept per-workflow args (same args would apply to every demo, which is almost never what you want).

---

## 8. Where the canonical prompts live

**The prompt the baseline sends is `demos/<slug>/prompt.md` under whichever directory `SWARMAI_DEMO_OUT_DIR` points to** (default: `swarm-ai-examples/demos`).

**The swarm's task is hardcoded in Java** inside `<slug>/src/main/java/.../Workflow.java`. For the baseline to answer the same question the swarm solves, `prompt.md` must mirror the Java workflow's task description.

If you change the Java workflow, update `prompt.md` to match. If you pass a workflow arg that alters behaviour (ticker, topic), edit `prompt.md` too.
