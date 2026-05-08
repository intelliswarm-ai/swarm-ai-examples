# ask-user-question — agent-driven structured user prompts (swarmai 1.0.19+)

Showcases the `AskUserQuestionTool` primitive end-to-end with a real LLM. The
agent receives one tool — `ask_user_question(question, options?, allowFreeform?)` —
and uses it to gather decisions it cannot infer from prior context.

## What this proves

- **Agent can pause mid-run** to ask a structured question (option-style or freeform)
- **The harness controls the answer source** via the `UserQuestionResolver` SPI
  (console, scripted, web UI, REST callback — all interchangeable)
- **The resolver is decorator-friendly**: `RecordingUserQuestionResolver` wraps
  any resolver and captures full audit history with no behaviour change
- **The agent visibly responds**: the final answer references the user's input,
  proving the question/answer mechanism actually steered the agent

## Why scripted, not console

This example uses `ScriptedUserQuestionResolver` so the run is **deterministic
and automated** — the same as the test suite, no stdin piping. To use it
interactively, swap one line:

```java
// from
UserQuestionResolver scripted = new ScriptedUserQuestionResolver(
    "production", "release/v2.0", "yes", "notify slack channel #releases");

// to
UserQuestionResolver console = new ConsoleUserQuestionResolver();
```

The rest of the wiring is unchanged — the SPI is the abstraction.

## Architecture

| Layer | Type |
|---|---|
| Tool callback (Spring AI) | `AskUserQuestionTool.callback(resolver)` |
| Audit decorator | `RecordingUserQuestionResolver(delegate)` |
| Source of answers | `ScriptedUserQuestionResolver` / `ConsoleUserQuestionResolver` / your own impl |
| Question/answer record types | `UserQuestion` / `UserAnswer` (immutable) |

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./ask-user-question/run.sh                          # default release-planning prompt
./ask-user-question/run.sh "Plan a database migration"
```

Same provider auto-detection as every other example — `SPRING_PROFILES_ACTIVE`
controls openai-mini / openai / ollama.

## Output shape

```
======================================================================
  AskUserQuestion — agent-driven user prompts
======================================================================

======================================================================
  Question/answer transcript (every interaction the agent had)
======================================================================

  Q1: Which target environment for the deployment?
      options: [staging, production]
      A:  [option]   production

  Q2: What is the source branch name?
      A:  [freeform] release/v2.0

  Q3: Do you give final approval to proceed?
      options: [yes, no]
      A:  [option]   yes

  Q4: How would you like to be notified?
      A:  [freeform] notify slack channel #releases on success

======================================================================
  Final response from the LLM
======================================================================
Deployment Plan:
1. Branch:        release/v2.0
2. Target:        production
3. Approval:      received
4. Notification:  slack channel #releases on success
...

======================================================================
  Quality check
======================================================================
  questions asked:   4
  answers received:  4  (2 option, 2 freeform)
  scripted leftover: 0

  Checks:
    [PASS] >= 3 questions asked
    [PASS] script fully consumed
    [PASS] both option and freeform answers exercised
    [PASS] final response references at least one user answer

  QUALITY CHECK PASSED
```

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
