## What you're looking at

Same question — "what defaults should I use for `RagPipeline`?" — answered with vs without retrieval.
**Left: SwarmAI** — `RagPipeline` ingests the project's `RAG_LESSONS.md` corpus and synthesises a cited answer with the eval-winning defaults baked in.
**Right: raw LLM** — same model, same prompt, no documents, no retrieval.

## On GPT-4o

<div class="compare-grid">
  <div class="compare-card swarm popular">
    <div class="card-badge">Most useful</div>
    <div class="card-tag swarm-tag">SwarmAI</div>
    <h4 class="card-verdict">Concrete defaults <span class="conf">(CITED)</span></h4>
    <p class="card-reason">3 citations to <code>RAG_LESSONS.md</code>, every value justified</p>
    <ul class="card-list">
      <li>Chunk size <b>800</b> — peer-aligned (500 too small, 1200 lost recall)</li>
      <li>Top-K <b>5</b> — K=10 added prompt tokens without proportional recall</li>
      <li>Hybrid (vector + BM25 → RRF) — disabling tanked chunk-hit 14 % → 8 %</li>
      <li>Five-bullet plain-English prompt — refusals 36 % → 12 %</li>
      <li><b>~6.4s wall time</b>, 3 citations</li>
    </ul>
  </div>
  <div class="compare-card baseline">
    <div class="card-tag baseline-tag">Baseline</div>
    <h4 class="card-verdict">Generic advice</h4>
    <p class="card-reason">Admits it can't see the documents, falls back to general best-practices</p>
    <ul class="card-list negative">
      <li>"I don't have access to specific documents like the IntelliDoc 2026-04-26 evaluation"</li>
      <li>Generic "use a chunk size around N", "consider hybrid retrieval"</li>
      <li>No reference to <em>this</em> codebase's defaults at all</li>
      <li><b>~9.8s wall time</b>, 0 citations</li>
    </ul>
  </div>
</div>

## On GPT-5.4 mini

<div class="compare-grid">
  <div class="compare-card swarm popular">
    <div class="card-badge">Most useful</div>
    <div class="card-tag swarm-tag">SwarmAI</div>
    <h4 class="card-verdict">Same answer, faster <span class="conf">(CITED)</span></h4>
    <p class="card-reason">Smaller model, same RAG-grounded answer in 4.7s</p>
    <ul class="card-list">
      <li>Chunk size <b>800</b>, top-K <b>5</b>, simple prompt — exactly what the corpus says</li>
      <li>3 citations to <code>RAG_LESSONS.md</code></li>
      <li><b>~4.7s wall time</b> — 27 % faster than GPT-4o swarm path</li>
    </ul>
  </div>
  <div class="compare-card baseline">
    <div class="card-tag baseline-tag">Baseline</div>
    <h4 class="card-verdict">Hallucinates a number</h4>
    <p class="card-reason">Invents "chunk size ≈ 500 tokens" — wrong, the eval picked 800</p>
    <ul class="card-list negative">
      <li>States "Chunk size: ~500 tokens" — fabricated, the project's actual default is 800</li>
      <li>Recommends "grounded, citation-first prompt" — vague, no template</li>
      <li>Confident tone, but every specific value is the model's prior, not the corpus</li>
      <li><b>~5.8s wall time</b>, 0 citations</li>
    </ul>
  </div>
</div>

## What changed under the hood

`RagPipeline` is the new high-level RAG facade in `swarmai-core` (1.0.10). It bakes in the configuration that won the
**IntelliDoc 2026-04-26 evaluation** — 7 iterations × 225 document-grounded questions × 3 platforms (IntelliDoc,
LangGraph-Python, LangChain4j-Java). The defaults that produced the best chunk-hit + faithfulness + latency tradeoff:

| field | default | reason |
|---|---|---|
| chunk size | 800 | peer-aligned (500 too small, 1200 lost recall) |
| chunk overlap | 100 | enough to span formula/sentence boundaries |
| top-K | 5 | K=10 added prompt tokens without proportional recall |
| hybrid retrieval | **on** | BM25 + vector → RRF; disabling tanked chunk-hit 14 % → 8 % |
| MMR rerank | off | over-spreads results away from the right doc |
| temperature | 0.2 | 0.1 over-refused, 0.3 paraphrased formulas |
| num predict | 350 | 30 % latency saving without hurting completeness |
| synthesis prompt | 5 plain-English bullets | dropped refusals 36 % → 12 % vs 6-rule prompt |

Builder is one line:

```java
RagPipeline rag = RagPipeline.builder()
        .vectorStore(vectorStore)
        .chatClient(chatClient)
        .config(RagConfig.defaults())
        .build();

rag.ingestText("RAG_LESSONS.md", Files.readString(Paths.get("RAG_LESSONS.md")));
RagAnswer a = rag.query("What are the recommended defaults?");
// a.answer() → cited reply
// a.citations() → [Citation(RAG_LESSONS.md, 0, ...), ...]
```

## Reproduce

```bash
cd swarm-ai-examples
./demo-recorder/record-rag-demo.sh gpt-4o gpt-5.4-mini
# requires OPENAI_API_KEY in .env, Ollama running with nomic-embed-text
```

Outputs land in `demos/rag-knowledge-base/runs/<model>/<framework-version>/` and sync to the website
under `intelliswarm.ai/website/src/assets/demos/`.
