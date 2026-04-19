# Wolfram Alpha Math Example

Exercises **`WolframAlphaTool`** — an analyst agent answers quantitative questions by calling
Wolfram Alpha for every numeric claim. Demonstrates both the "short answer" API (single-line
replies) and the "full results" API (step-by-step pods).

## Prerequisites

**API key (required):**

| Env var          | How to get it                                                   |
|------------------|-----------------------------------------------------------------|
| `WOLFRAM_APPID`  | Free tier at https://developer.wolframalpha.com/ (2000 req/mo)  |

```bash
export WOLFRAM_APPID=your-appid-here
```

**Infrastructure:** none — the tool calls `api.wolframalpha.com` directly.

## Run

```bash
./run.sh wolfram                                     # default multi-part question
./run.sh wolfram "integrate x^2 dx from 0 to 5"
./run.sh wolfram "mass of Jupiter in kilograms"
./run.sh wolfram "10 km in miles"
```

## What this proves about the tool

- Short-mode returns a single plain-text line the agent can quote verbatim.
- Full-mode returns structured pods (Input interpretation / Indefinite integral / Numeric result),
  which the agent walks through when a symbolic answer needs step-by-step reasoning.
- HTTP 501 "could not interpret" is translated into a human-readable rephrase hint.
- Missing `WOLFRAM_APPID` surfaces a setup error, not a crash.
