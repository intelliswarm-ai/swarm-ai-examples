# Citation-Required Pipeline (SwarmAI 1.0.24)

End-to-end demo of the new native-Anthropic + compliance flow:

1. Attach a plain-text 10-K excerpt as a `DocumentBlock.PlainText` with citations enabled
2. Send via `AnthropicNativeClient` with:
   - Prompt caching (1h breakpoint on `system`, 5m on `documents`)
   - Extended thinking budget (2048 tokens)
   - Inline citation mode on
3. Get back text + inline citations + cache-hit telemetry
4. Run the response through `CitationRequiredGate.strict()` — every numeric claim must be anchored to a citation
5. Print PASS / FAIL with specific findings if any claim is un-cited

## Requires

```bash
# Set in .env or export directly
ANTHROPIC_API_KEY=sk-ant-...
```

The example exits cleanly with a hint if the key is missing.

## Run

```bash
ANTHROPIC_API_KEY=sk-ant-... ./citation-required-pipeline/run.sh
```

Run it twice — the second run should show `cache_read > 0` for the system prompt (1h TTL) and documents (5m TTL).

## Sample output

```
[1/3] Sending request to Anthropic …
      Model:           claude-opus-4-7
      Stop reason:     END_TURN
      Input tokens:    1432
      Output tokens:   198
      Cache read:      0 tokens          ← first run
      Cache write 5m:  324 tokens
      Cache write 1h:  87 tokens

[2/3] Response text:
------------------------------------------------------------
In FY25, ACME reported revenue of $1,200 million (+20.0%) and EBITDA of
$360 million (+28.6%), expanding operating margin to 18.4% (+240 bps). Free
cash flow reached $220 million (+22.2%).
------------------------------------------------------------
Inline citations: 4
  - [PageLocation[startPageNumber=1, endPageNumber=1]] Revenue: $1,200 million in FY25…
  - [PageLocation[startPageNumber=1, endPageNumber=1]] EBITDA: $360 million in FY25…
  - ...

[3/3] Running CitationRequiredGate.strict() …
      Verdict:         PASS — every numeric claim is cited
```

## What 1.0.24 features this exercises

| Feature | Module |
|---|---|
| Native Anthropic adapter | `swarmai-native-anthropic` |
| Prompt caching with 5m / 1h breakpoints + cache-usage telemetry | `agent/llm/CachePolicy` + `CacheUsage` |
| Extended thinking budget | `agent/llm/ThinkingBudget` |
| Document blocks (plain text) with citations enabled | `agent/llm/DocumentBlock` + `CitationConfig` |
| Inline citations with `CharLocation` / `PageLocation` anchors | `agent/llm/InlineCitation` + `CitationLocation` |
| `CitationRequiredGate.strict()` — per-sentence numeric-claim anchoring | `governance/compliance/CitationRequiredGate` |
| `ComplianceEvaluator` orchestrating gates → `ComplianceReport` | `governance/compliance/` |
