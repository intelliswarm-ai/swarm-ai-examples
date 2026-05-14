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

## Configuration

None — runs offline.
