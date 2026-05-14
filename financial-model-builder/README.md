# Financial Model Builder (SwarmAI 1.0.24)

Composes the four new 1.0.24 finance tools with the Apache POI authoring + audit tools to produce a complete valuation package as an Excel file — and then audits the file for hardcodes, formula errors, and magic numbers.

**No API key required.** Pure deterministic Java + Apache POI. Runs offline.

## What it demonstrates

| Step | Tool | What it does |
|---|---|---|
| 1 | `DcfModelTool` | FCF projection + Gordon growth terminal value + 3×3 WACC/g sensitivity grid |
| 2 | `CompsAnalysisTool` | Peer-set multiples → median/mean/low/high → implied equity per share |
| 3 | `LboModelTool` | Entry price + leverage → year-by-year debt paydown → exit IRR / MOIC |
| 4 | `TearSheetTool` (composes `XlsxAuthorTool`) | Writes Summary + Financials + Multiples sheets to `.xlsx` |
| 5 | `XlsxAuditTool` | Verdict: PASS/FAIL on hardcode ratio, formula errors, magic numbers |

## Run

```bash
./financial-model-builder/run.sh           # default ticker ACME
./financial-model-builder/run.sh TSLA      # custom ticker (label only)
```

## Output

- Console: per-step valuation results + audit report
- File: `output/financial-model/financial-model.xlsx`

### Why the audit verdict is `FAIL`

`FAIL` is **the expected outcome** for this demo and is the point of step 5.

`TearSheetTool` writes a deliberately weak spreadsheet of hardcoded literals (revenue, EBITDA, market cap, …) into the `.xlsx` — no formulas, no named ranges. We do this so the next step, `XlsxAuditTool`, has something to catch.

The audit then runs over the file and reports the 14 magic numbers it found. That's the audit succeeding: it flagged exactly what an analyst would flag in a junior banker's draft model. In production you'd feed `XlsxAuditTool` a formula-driven model and expect `PASS` (zero hardcodes above the threshold, zero `#REF!`/`#DIV/0!` errors, every assumption traceable to a named range).

In other words: this example proves both ends of the pipeline — the *author* tool can produce an Excel artifact, and the *audit* tool can read it back and grade it.

## Configuration

None — runs offline.
