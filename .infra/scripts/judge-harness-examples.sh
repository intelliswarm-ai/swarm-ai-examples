#!/usr/bin/env bash
#
# judge-harness-examples.sh — run the 11 harness examples shipped in
# the 1.0.19 harness arc and have gpt-4o evaluate the quality of each run.
#
# Per-example rubric is embedded below. The judge gets the example's full
# stdout (truncated to 8KB) plus the rubric, and returns structured JSON:
#
#   { score: 0-100,
#     verdict: PASS | PARTIAL | FAIL,
#     reasons: [...],
#     framework_bugs: [...],
#     example_bugs: [...] }
#
# Output: one summary table at the end + a detailed per-example JSON file
# under output/judge/<example>.json
#
# Usage:
#   ./.infra/scripts/judge-harness-examples.sh                 # judge all 11
#   ./.infra/scripts/judge-harness-examples.sh --list          # show the list
#   ./.infra/scripts/judge-harness-examples.sh task-list       # judge ONE example
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
JUDGE_DIR="$PROJECT_DIR/output/judge"
mkdir -p "$JUDGE_DIR"

# Source .env so OPENAI_API_KEY is available
if [ -f "$PROJECT_DIR/.env" ]; then
    set -o allexport
    # shellcheck disable=SC1091
    . "$PROJECT_DIR/.env"
    set +o allexport
fi

if [ -z "${OPENAI_API_KEY:-}" ]; then
    echo "ERROR: OPENAI_API_KEY is required (in .env or environment)" >&2
    exit 1
fi

JUDGE_MODEL="${JUDGE_MODEL:-gpt-4o}"
WORKFLOW_TIMEOUT_SEC="${WORKFLOW_TIMEOUT_SEC:-240}"

# --- python3-based JSON helpers (we don't depend on jq) ----------------------
# Build the OpenAI request payload from model + system + user (read from env).
build_payload() {
    JUDGE_MODEL="$1" JUDGE_SYS="$2" JUDGE_USER="$3" python3 -c '
import json, os
print(json.dumps({
    "model": os.environ["JUDGE_MODEL"],
    "response_format": {"type": "json_object"},
    "messages": [
        {"role": "system", "content": os.environ["JUDGE_SYS"]},
        {"role": "user",   "content": os.environ["JUDGE_USER"]}
    ],
    "temperature": 0.0,
    "max_tokens": 1500
}))'
}

# Read JSON on stdin and emit a top-level field (default if missing).
json_field() {
    python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    val = d.get(sys.argv[1], sys.argv[2])
    if isinstance(val, (dict, list)):
        print(json.dumps(val))
    else:
        print(val)
except Exception:
    print(sys.argv[2])
' "$1" "${2:-}"
}

# Read JSON on stdin and emit OpenAI-style choices[0].message.content
extract_message_content() {
    python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get("choices", [{}])[0].get("message", {}).get("content", ""))
except Exception:
    pass'
}

# Read JSON on stdin and emit each item of an array field on its own line.
json_array() {
    python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    arr = d.get(sys.argv[1], [])
    if isinstance(arr, list):
        for item in arr:
            print(item if isinstance(item, str) else json.dumps(item))
except Exception:
    pass
' "$1"
}

# --- Colors ---
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${GREEN}[JUDGE]${NC} $*"; }
warn()  { echo -e "${YELLOW}[JUDGE]${NC} $*"; }
fail()  { echo -e "${RED}[JUDGE]${NC} $*"; }
phase() { echo -e "${CYAN}>>>${NC} $*"; }

# --- Per-example rubrics ------------------------------------------------------
# Each rubric is the *evaluation criterion* the judge uses. Be specific about
# the observable behaviour we expect — vague rubrics produce vague verdicts.

declare -A RUBRICS

RUBRICS["task-list"]="Verify the LLM used the task_create and task_update tools to drive a multi-step plan. Expect: (a) at least 4 tasks created via task_create, (b) each task transitioned PROPOSED -> APPROVED -> EXECUTED in order, (c) the final rendered TaskList shows EVERY task as completed (\`[x]\`), (d) the built-in QUALITY CHECK at the end says PASSED. Penalize: tasks left in-progress, status icons wrong, the agent skipping the in-progress step, malformed Markdown in the rendered list."

RUBRICS["system-reminders"]="Verify the harness injected <system-reminder> blocks between turns and the agent visibly responded to them. Expect: (a) 3 conversation turns ran, (b) at least 2 reminders were posted by the harness's harnessTick, (c) BOTH turn 2 and turn 3 user messages contained a <system-reminder> block (the example reports turn2=true turn3=true), (d) the agent's turn 3 response references summary/completion (the 'all-done' reminder steered it), (e) QUALITY CHECK PASSED. Penalize: reminders re-firing on subsequent turns (drain-on-read broken), agent ignoring the reminder content, missing turns."

RUBRICS["ask-user-question"]="Verify the agent paused mid-run via ask_user_question and incorporated user answers verbatim. Expect: (a) the agent called ask_user_question 3 or more times, (b) BOTH option-style and freeform answers were exercised (option>=1 freeform>=1), (c) the scripted answer queue was fully consumed (no leftover), (d) the agent's final plan references at least one of the user's answers verbatim, (e) QUALITY CHECK PASSED. Penalize: agent fabricating values, ignoring user input, only using one answer kind."

RUBRICS["slash-commands"]="Verify the SlashCommandRouter cleanly distinguishes slash commands from plain text. Expect: (a) every routing branch was exercised — at least 3 SkillExecuted, 1 SkillNotFound, 1 PassThrough, (b) /help output lists every registered skill (/help, /summarise, /translate), (c) /summarise produced non-trivial LLM output (>=20 chars, NOT a 'Usage:' string), (d) /translate output differs from the input (proper translation happened), (e) QUALITY CHECK PASSED. Penalize: missing routing branches, /help omitting any registered skill, /translate echoing the input."

RUBRICS["edit-discipline"]="Verify the agent followed the read-before-edit invariant and produced a clean, surgical edit. Expect: (a) the agent called read_file at least once before any successful edit_file, (b) the on-disk file shows version bumped from 1.0.18 to 1.0.19, (c) NO OTHER LINES were modified (every non-version line preserved verbatim), (d) QUALITY CHECK PASSED. Penalize: extra modifications outside the version line, the agent edit-without-read attempts that were NOT recovered, edits that altered formatting/whitespace."

RUBRICS["tool-search"]="Verify the agent had ONLY tool_search loaded but discovered relevant tools by query. Expect: (a) the catalog had multiple tools (8+) with only tool_search exposed, (b) for each of the 3 questions the direct search returned a category-relevant match (csv-analysis, date-arithmetic, math), (c) the agent's reply for at least 2/3 questions referenced an expected tool name from the search results, (d) QUALITY CHECK PASSED. Penalize: agent fabricating tool names not in the catalog, irrelevant top matches, incoherent agent replies."

RUBRICS["typed-message-history"]="Verify every conversation interaction was captured as a typed record with structured fields. Expect: (a) BOTH calculator and current_time tools were exercised at least once, (b) the rendered transcript shows tool calls inlined under assistant messages with correlation IDs (#call_xxx), (c) recorded counts match callback invocations exactly (callbacks=recorded for each tool), (d) the rendered transcript distinguishes [tool_ok] from [tool_err] correctly, (e) QUALITY CHECK PASSED. Penalize: any interaction missing from the typed history, malformed transcript, mismatched call/result counts."

RUBRICS["deferred-tool-loading"]="Verify the multi-turn orchestration loaded tools between turns and the agent invoked them later. Expect: (a) at least 2 turns ran, (b) the tool list visibly mutated between turns (turn 1 had only [tool_search, tool_load]; turn 2+ had additional loaded tools), (c) at least 1 tool load was applied through the loop (loader.applyPendingLoads()=1+ at some point), (d) the loaded tool was actually invoked in a later turn, (e) loop exited cleanly when applyPendingLoads()=0, (f) QUALITY CHECK PASSED. Penalize: tool list never grew, agent never called the loaded tool, infinite loops, no exit signal."

RUBRICS["background-tasks"]="Verify the foreground agent dispatched async work and combined results. Expect: (a) >=1 background task was spawned via background_spawn, (b) at least 1 task reached COMPLETED status with substantial output (>=50 chars), (c) the foreground reply REFERENCES BOTH the background task's topic AND the immediate work the user asked for (e.g. haiku + mitochondria summary), (d) the [completion event] line printed (listener fired), (e) QUALITY CHECK PASSED. Penalize: foreground reply ignoring background result, task failed/cancelled unintentionally, listener didn't fire."

RUBRICS["plan-loop"]="Verify the EvolvingPlan + LlmReplanner + Subagent flow ran. Expect: (a) the plan channel had multiple entries with proposed/approved/executed transitions, (b) at least one human-approval gate fired AND was acted on (approved or rejected), (c) the LlmReplanner produced amendments when triggered, (d) a Subagent spawned and ran, (e) the run completed without uncaught exceptions. Penalize: empty plan, no transitions, replanner never fired when the policy expected it, subagent failed silently."

RUBRICS["self-improving-plan-loop"]="Verify the bandit changed workflow decisions over time. Expect: (a) two LOW-risk action types ran (fastpath rate=0.95, slowpath rate=0.10) for 60 iterations each, (b) early rows show both fastpath and slowpath being PLAN_POLICY-auto-approved, (c) once buckets graduate (>=20 obs, ci<=0.12), the slowpath bucket flips from PLAN_POLICY -> alice (vetoed by the bandit), (d) the final routing tally shows slowpath had MORE human routes than fastpath, (e) bandit state was persisted to disk. Penalize: no graduation event, fastpath was vetoed (false positive), state not persisted, summary numbers incoherent."

# --- Workflow list -----------------------------------------------------------
EXAMPLES=(
    "task-list"
    "system-reminders"
    "ask-user-question"
    "slash-commands"
    "edit-discipline"
    "tool-search"
    "typed-message-history"
    "deferred-tool-loading"
    "background-tasks"
    "plan-loop"
    "self-improving-plan-loop"
)

# --- Argument handling -------------------------------------------------------
STRICT_MODE=false
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --strict) STRICT_MODE=true ;;
        *)        ARGS+=("$arg") ;;
    esac
done

if [ "${ARGS[0]:-}" = "--list" ]; then
    echo "Harness examples to judge:"
    for e in "${EXAMPLES[@]}"; do echo "  $e"; done
    exit 0
fi
if [ -n "${ARGS[0]:-}" ] && [ "${ARGS[0]}" != "--all" ]; then
    # Single-example mode
    EXAMPLES=("${ARGS[0]}")
fi

# Strict mode writes verdicts to a separate directory so we can compare modes
if $STRICT_MODE; then
    JUDGE_DIR="$PROJECT_DIR/output/judge-strict"
    mkdir -p "$JUDGE_DIR"
    info "  STRICT MODE: adversarial rubric framing; verdicts under $JUDGE_DIR"
fi

# --- Run + judge each example -------------------------------------------------
JUDGED_PASS=()
JUDGED_PARTIAL=()
JUDGED_FAIL=()
JUDGED_RUNERR=()

JUDGE_START=$(date +%s)

for example in "${EXAMPLES[@]}"; do
    rubric="${RUBRICS[$example]:-}"
    if [ -z "$rubric" ]; then
        warn "skipping $example — no rubric defined"
        continue
    fi

    phase "Running: $example"
    LOG="$JUDGE_DIR/${example}.run.log"
    set +e
    SKIP_BUILD=1 SPRING_PROFILES_ACTIVE=openai-mini timeout "$WORKFLOW_TIMEOUT_SEC" \
        "$PROJECT_DIR/run.sh" "$example" 2>&1 \
        | grep -v "^[0-9]*:[0-9]*:[0-9]*\." \
        > "$LOG"
    run_rc=$?
    set -e

    if [ $run_rc -ne 0 ]; then
        fail "  RUN ERROR: $example exited $run_rc (see $LOG)"
        JUDGED_RUNERR+=("$example")
        continue
    fi

    # Truncate to ~8KB to fit comfortably in gpt-4o context with the rubric
    output=$(tail -c 8000 "$LOG")

    # Build the judge prompt — python3 to keep JSON safe (no jq dependency).
    if $STRICT_MODE; then
        judge_sys="You are a SKEPTICAL SENIOR ENGINEER critiquing a SwarmAI framework example. Your job is to find what's WRONG, not confirm what's right. The rubric is a baseline checklist — examples that merely tick the boxes should NOT score 100. Score the OUTPUT QUALITY, not just whether the mechanism executed. Be specifically harsh about: (a) generic / boilerplate output that a production user would call shallow, (b) brittle prompts or fragile assumptions the run happens to satisfy, (c) edge cases the run avoids that would expose problems, (d) phrasing or output that suggests the agent is going through the motions rather than reasoning. Distinguish framework bugs (in swarmai-core/swarmai-tools) from example/prompt bugs (in the example file). Use the FULL 0-100 scale: score below 70 if output is generic, below 50 if there are obvious bugs, only 90+ if you genuinely cannot find substantive criticism. Return ONLY valid JSON matching this schema: {\"score\": int 0-100, \"verdict\": \"PASS\"|\"PARTIAL\"|\"FAIL\", \"summary\": string, \"reasons\": [string], \"framework_bugs\": [string], \"example_bugs\": [string], \"weaknesses\": [string], \"would_ship_to_customers\": bool}."
        judge_user="Example name: $example

Baseline rubric (what the mechanism is supposed to do):
$rubric

--- Example stdout (last ~8KB) ---
$output
--- end stdout ---

Critique like a skeptical senior engineer who's been asked 'should we ship this UX to customers?'. Find the WEAKEST aspect. Don't be polite — call out specific weaknesses. Return JSON only."
    else
        judge_sys="You are an expert judge evaluating SwarmAI framework examples. Examine the provided stdout against the supplied rubric. Be specific about what worked and what didn't. Distinguish framework bugs (in swarmai-core/swarmai-tools) from example/prompt bugs (in the example file). Return ONLY valid JSON matching this schema: {\"score\": int 0-100, \"verdict\": \"PASS\"|\"PARTIAL\"|\"FAIL\", \"summary\": string, \"reasons\": [string], \"framework_bugs\": [string], \"example_bugs\": [string]}."
        judge_user="Example name: $example

Rubric:
$rubric

--- Example stdout (last ~8KB) ---
$output
--- end stdout ---

Evaluate now. Return JSON only."
    fi
    judge_payload=$(build_payload "$JUDGE_MODEL" "$judge_sys" "$judge_user")

    info "  Judging with $JUDGE_MODEL..."
    judge_response=$(curl -sS -X POST https://api.openai.com/v1/chat/completions \
        -H "Authorization: Bearer $OPENAI_API_KEY" \
        -H "Content-Type: application/json" \
        -d "$judge_payload")

    # Extract content; on parse failure dump for debugging
    content=$(echo "$judge_response" | extract_message_content)
    if [ -z "$content" ]; then
        fail "  judge call failed for $example; raw response saved"
        echo "$judge_response" > "$JUDGE_DIR/${example}.judge-error.json"
        JUDGED_RUNERR+=("$example")
        continue
    fi

    # Save the parsed verdict
    echo "$content" > "$JUDGE_DIR/${example}.json"

    # Pretty-print headline
    score=$(echo "$content" | json_field score 0)
    verdict=$(echo "$content" | json_field verdict UNKNOWN)
    summary=$(echo "$content" | json_field summary "")

    case "$verdict" in
        PASS)
            info "  $example: $verdict score=$score — $summary"
            JUDGED_PASS+=("$example|$score")
            ;;
        PARTIAL)
            warn "  $example: $verdict score=$score — $summary"
            JUDGED_PARTIAL+=("$example|$score")
            ;;
        FAIL)
            fail "  $example: $verdict score=$score — $summary"
            JUDGED_FAIL+=("$example|$score")
            ;;
        *)
            warn "  $example: unrecognised verdict '$verdict' (see ${example}.json)"
            JUDGED_PARTIAL+=("$example|$score")
            ;;
    esac

    # Print the issues if any
    fb=$(echo "$content" | json_array framework_bugs 2>/dev/null || true)
    eb=$(echo "$content" | json_array example_bugs 2>/dev/null || true)
    if [ -n "$fb" ]; then
        echo "    Framework bugs reported:"
        echo "$fb" | sed 's/^/      - /'
    fi
    if [ -n "$eb" ]; then
        echo "    Example bugs reported:"
        echo "$eb" | sed 's/^/      - /'
    fi
done

# --- Summary ------------------------------------------------------------------
JUDGE_END=$(date +%s)
JUDGE_DUR=$((JUDGE_END - JUDGE_START))

echo
phase "Judge sweep complete in ${JUDGE_DUR}s (model=$JUDGE_MODEL)"
echo
info "  PASS    (${#JUDGED_PASS[@]}):"
for e in "${JUDGED_PASS[@]}"; do echo "    ${e/|/  score=}"; done
warn "  PARTIAL (${#JUDGED_PARTIAL[@]}):"
for e in "${JUDGED_PARTIAL[@]}"; do echo "    ${e/|/  score=}"; done
fail "  FAIL    (${#JUDGED_FAIL[@]}):"
for e in "${JUDGED_FAIL[@]}"; do echo "    ${e/|/  score=}"; done
if [ ${#JUDGED_RUNERR[@]} -gt 0 ]; then
    fail "  RUN ERR (${#JUDGED_RUNERR[@]}):"
    for e in "${JUDGED_RUNERR[@]}"; do echo "    $e"; done
fi
echo
info "Detailed JSON verdicts saved under: $JUDGE_DIR/"
