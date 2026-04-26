# RAG Lessons (IntelliDoc 2026-04-26 evaluation)

## Defaults that won

The default `RagConfig` baked into `RagPipeline` is the configuration that
won the iter#4 head-to-head against LangGraph-Python and LangChain4j-Java.

| field             | default | reason                                                                                |
|-------------------|---------|---------------------------------------------------------------------------------------|
| chunk size        | 800     | Peer-aligned. 500 produced too many tiny chunks; 1200 lost recall on dense PDFs.       |
| chunk overlap     | 100     | Enough to span formula/sentence boundaries without doubling embedding cost.           |
| top-K             | 5       | K=10 added prompt tokens without proportional recall gain.                            |
| max passage chars | 2400    | Model attention dropped past ~2000 chars; cutting from 4000 saved ~30 percent latency. |
| hybrid retrieval  | true    | BM25 + vector via Reciprocal Rank Fusion. Disabling tanked chunk-hit 14% to 8%.       |
| contextual prefix | true    | Anthropic-style `[filename] ` prepend before embedding gave a small recall lift.       |
| MMR rerank        | false   | Diversity rerank over-spread results away from the right document.                    |
| temperature       | 0.2     | At 0.1 the model over-refused; at 0.3 it paraphrased formulas. 0.2 is the sweet spot. |
| num predict       | 350     | Capping output saved 30 percent latency without hurting answer completeness.          |

## The synthesis prompt that won

A short plain-English prompt beat enumerated multi-rule prompts for small
models like qwen2.5:3b. Refusal rate dropped from 36 percent to 12 percent
with no hallucination regression.

```
Answer the question based EXCLUSIVELY on the source passages provided in the user message.

Rules:
- Write the actual answer in plain English. Include the factual content, not just a citation marker.
- Append the citation tag immediately after each fact. Example: "The capital of Japan is Tokyo [source: japan.md #0]."
- The citation tag must use the EXACT label shown in each passage's header.
- If the passages contain a formula, equation, or specific notation, copy it verbatim.
- If the passages don't answer the question, say so plainly — don't guess.
- Be direct and concise.
```

## Lessons in order of impact

1. **Simple prompts beat enumerated rules for small models.** Switching from
   six numbered rules to five plain-English bullets cut refusals 3x.
2. **Hybrid retrieval (vector + BM25 + RRF) is doing real work.** BM25 catches
   exact identifiers (algorithm names, formulas, numbers) the embedding model
   misranks.
3. **Chroma is not a free win over Lucene HNSW.** Spring AI's Chroma client
   defaults (ef_search=10, metadata-driven distance metric) lose ~7pp chunk-hit
   vs Lucene under identical chunk and prompt config.
4. **Strict chunk_hit is not the same as practical answer quality.** Faithful
   (content-word overlap >= 0.30) is a better proxy.
5. **PDF math symbols can crash Chroma ingest.** PDFBox emits lone UTF-16
   surrogate pairs that Chroma rejects with `invalid high surrogate in string`.
   `RagPipeline.sanitizeUtf16()` replaces them with U+FFFD before adding.
