# Wolfram Alpha Math Example

> **New to SwarmAI?** Start from the [quickstart template](../quickstart-template/) for the
> minimum viable app, then come back here to swap `WikipediaTool` → `WolframAlphaTool` and copy
> the quantitative-analyst prompt below.



Exercises **`WolframAlphaTool`** — an analyst agent answers quantitative questions by calling
Wolfram Alpha for every numeric claim. Demonstrates both the "short answer" API (single-line
replies) and the "full results" API (step-by-step pods).

## How it works

```mermaid
sequenceDiagram
    actor User
    participant Ex as WolframMathExample
    participant Agent as "Quantitative Analyst" agent
    participant Tool as WolframAlphaTool
    participant WA as api.wolframalpha.com

    User->>Ex: ./run.sh wolfram "question"
    Ex->>Tool: smokeTest()
    Tool->>WA: GET /v1/result?appid=...&i=1+1
    WA-->>Tool: 200 "2"
    Ex->>Agent: kickoff(question)
    loop
        Agent->>Tool: wolfram(input, mode=short | full)
        alt short mode
            Tool->>WA: GET /v1/result (plain text)
        else full mode
            Tool->>WA: GET /v2/query?output=json (pods)
        end
        WA-->>Tool: answer / pods
        Tool-->>Agent: quoted result
    end
    Agent-->>User: numeric answer with Wolfram citation
```

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

Sample output (illustrative — your run will show your data):

The Quantitative Analyst agent will issue one or more `wolfram_alpha` tool calls. A `mode='short'`
call returns a single line, while `mode='full'` returns structured pods:

```text
Wolfram Alpha — `integrate x^3 from 0 to 5`

**Input interpretation**
integrate x^3 dx, x = 0 to 5

**Definite integral**
625/4

**Decimal approximation**
156.25
```

The agent's final answer composes those tool results into a structured response:

```text
=== WolframAlphaTool showcase result ===

1. Integral of x^3 from 0 to 5
   Wolfram Alpha (mode='full') returned the definite integral 625/4 = 156.25.

2. Mass of Jupiter in kilograms
   Wolfram Alpha (mode='short') returned: 1.898 x 10^27 kg

Each numeric value above is quoted verbatim from a wolfram_alpha tool invocation.
```

## What to expect

For a quantitative question, the agent quotes Wolfram Alpha's short answer verbatim or walks
through structured pods (Input interpretation → Indefinite integral → Numeric result) when
symbolic reasoning is needed.

## Value add

Eliminates one of the best-known LLM failure modes — numerical and symbolic reasoning.
Analyst, finance, scientific, and engineering agents get trustworthy computations for math,
unit conversions, and physical constants they would otherwise hallucinate.

## What this proves about the tool

- Short-mode returns a single plain-text line the agent can quote verbatim.
- Full-mode returns structured pods (Input interpretation / Indefinite integral / Numeric result),
  which the agent walks through when a symbolic answer needs step-by-step reasoning.
- HTTP 501 "could not interpret" is translated into a human-readable rephrase hint.
- Missing `WOLFRAM_APPID` surfaces a setup error, not a crash.
