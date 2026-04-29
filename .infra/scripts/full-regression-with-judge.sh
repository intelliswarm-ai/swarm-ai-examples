#!/usr/bin/env bash
#
# SwarmAI Full Regression + LLM-as-Judge + Cost
# =============================================
#
# Runs the full curated `judge-all` workflow batch against a fast cloud model,
# scores every workflow with the LLM judge, then aggregates pass/fail, dimension
# scores, token usage, and dollar cost — keyed to the framework version under
# test.
#
# Reproducibility — pin the version you want to test:
#   ./full-regression-with-judge.sh --version 1.0.13
#   SWARMAI_VERSION=1.0.13 ./full-regression-with-judge.sh
# (without --version it reads <swarmai.version> from pom.xml)
#
# Defaults:
#   workflow model : gpt-4.1-mini   (a bit faster than gpt-4o-mini)
#   judge model    : gpt-4o-mini    (low-cost scoring)
#   profile        : openai-mini    (skips Ollama; needs OPENAI_API_KEY in .env)
#
# Override via env:
#   OPENAI_WORKFLOW_MODEL=gpt-4o-mini ./full-regression-with-judge.sh
#   SWARMAI_JUDGE_MODEL=gpt-4o        ./full-regression-with-judge.sh
#
# Output:
#   output/regression/<ts>-<version>-judge-all.log    full stdout/stderr
#   output/regression/<ts>-<version>-report.md        human-readable report
#   output/regression/regression-history.tsv          one row per run
#
# Full docs: docs/internal/REGRESSION_HARNESS.md
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
OUT_DIR="$PROJECT_DIR/output/regression"
mkdir -p "$OUT_DIR"

# --- Parse args --------------------------------------------------------------
CLI_VERSION=""
WORKFLOWS_FILTER=""
while [ $# -gt 0 ]; do
    case "$1" in
        --version) CLI_VERSION="$2"; shift 2 ;;
        --version=*) CLI_VERSION="${1#--version=}"; shift ;;
        --workflows) WORKFLOWS_FILTER="$2"; shift 2 ;;
        --workflows=*) WORKFLOWS_FILTER="${1#--workflows=}"; shift ;;
        -h|--help)
            sed -n '2,30p' "$0" | sed 's/^# *//'
            exit 0 ;;
        *)
            echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

# --- Resolve version: --version > $SWARMAI_VERSION > pom property ------------
POM_VERSION=$(grep -m1 -oE '<swarmai\.version>[^<]+' "$PROJECT_DIR/pom.xml" | sed 's/.*>//')
VERSION="${CLI_VERSION:-${SWARMAI_VERSION:-$POM_VERSION}}"

WORKFLOW_MODEL="${OPENAI_WORKFLOW_MODEL:-gpt-4.1-mini}"
JUDGE_MODEL="${SWARMAI_JUDGE_MODEL:-gpt-4o-mini}"
PROFILE="openai-mini"
OVERALL_TIMEOUT_SEC="${OVERALL_TIMEOUT_SEC:-7200}"   # 2 h hard cap

# Filename-safe slug of version (turn 1.0.14-SNAPSHOT into 1.0.14-SNAPSHOT)
VERSION_SLUG=$(echo "$VERSION" | tr '/' '_')
TS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
LOG="$OUT_DIR/${TS}-${VERSION_SLUG}-judge-all.log"
REPORT="$OUT_DIR/${TS}-${VERSION_SLUG}-report.md"
HISTORY="$OUT_DIR/regression-history.tsv"

# --- Pricing (USD per 1M tokens) ---------------------------------------------
# Update here when OpenAI changes pricing.
declare -A PRICE_IN PRICE_OUT
PRICE_IN["gpt-4.1-mini"]=0.40;  PRICE_OUT["gpt-4.1-mini"]=1.60
PRICE_IN["gpt-4o-mini"]=0.15;   PRICE_OUT["gpt-4o-mini"]=0.60
PRICE_IN["gpt-4o"]=2.50;        PRICE_OUT["gpt-4o"]=10.00
PRICE_IN["gpt-4.1"]=2.00;       PRICE_OUT["gpt-4.1"]=8.00
PRICE_IN["o3-mini"]=1.10;       PRICE_OUT["o3-mini"]=4.40

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
info() { echo -e "${GREEN}[REGRESSION]${NC} $*"; }
warn() { echo -e "${YELLOW}[REGRESSION]${NC} $*"; }
fail() { echo -e "${RED}[REGRESSION]${NC} $*"; }
phase(){ echo -e "${CYAN}>>>${NC} $*"; }

# --- Preflight ---------------------------------------------------------------
if [ ! -f "$PROJECT_DIR/.env" ]; then
    fail ".env not found at $PROJECT_DIR/.env — OPENAI_API_KEY required."; exit 1
fi
if ! grep -q "^OPENAI_API_KEY=" "$PROJECT_DIR/.env"; then
    fail "OPENAI_API_KEY missing from .env"; exit 1
fi

# Verify the requested framework version is installed in the local m2 — Maven
# would otherwise try to fetch it from a remote repo (and SNAPSHOTs from your
# own laptop never live in a remote repo).
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"
CORE_JAR="$M2_REPO/ai/intelliswarm/swarmai-core/$VERSION/swarmai-core-${VERSION}.jar"
if [ ! -f "$CORE_JAR" ]; then
    fail "swarmai-core $VERSION not found in $M2_REPO"
    fail "  expected: $CORE_JAR"
    fail "  → from the swarm-ai/ tree run: mvn install -DskipTests"
    fail "  → or pin the version with --version <X> after installing it"
    exit 1
fi

START_EPOCH=$(date +%s)
START_HUMAN=$(date -u +%Y-%m-%dT%H:%M:%SZ)

info "==============================================================="
info "  SwarmAI Full Regression + Judge"
info "==============================================================="
info "  Framework version : $VERSION"
info "  Workflow model    : $WORKFLOW_MODEL    (\$${PRICE_IN[$WORKFLOW_MODEL]:-?}/M in, \$${PRICE_OUT[$WORKFLOW_MODEL]:-?}/M out)"
info "  Judge model       : $JUDGE_MODEL       (\$${PRICE_IN[$JUDGE_MODEL]:-?}/M in, \$${PRICE_OUT[$JUDGE_MODEL]:-?}/M out)"
info "  Profile           : $PROFILE"
info "  Started           : $START_HUMAN"
info "  Log               : $LOG"
info "==============================================================="

# --- Build once (with pinned framework version) ------------------------------
info "Building project (mvn package -DskipTests -Dswarmai.version=$VERSION)..."
( cd "$PROJECT_DIR" && mvn package -DskipTests -Dswarmai.version="$VERSION" -q ) || {
    fail "Build failed for swarmai.version=$VERSION"; exit 1
}

# --- Run judge-all -----------------------------------------------------------
phase "Running judge-all (timeout: ${OVERALL_TIMEOUT_SEC}s)"
set +e
JUDGE_ALL_FILTER_ARG=""
if [ -n "$WORKFLOWS_FILTER" ]; then
    JUDGE_ALL_FILTER_ARG="--swarmai.judge-all.workflows=$WORKFLOWS_FILTER"
    info "Subset filter: $WORKFLOWS_FILTER"
fi
SPRING_PROFILES_ACTIVE="$PROFILE" \
OPENAI_WORKFLOW_MODEL="$WORKFLOW_MODEL" \
SKIP_BUILD=1 \
timeout --kill-after=60s "$OVERALL_TIMEOUT_SEC" \
  "$PROJECT_DIR/run.sh" judge-all \
    --swarmai.judge.enabled=true \
    --swarmai.judge.model="$JUDGE_MODEL" \
    --swarmai.judge.workflow-model="$WORKFLOW_MODEL" \
    $JUDGE_ALL_FILTER_ARG \
    > "$LOG" 2>&1
RUN_EXIT=$?
set -e
END_EPOCH=$(date +%s)
DURATION=$(( END_EPOCH - START_EPOCH ))

if [ $RUN_EXIT -eq 0 ]; then
    info "judge-all completed in ${DURATION}s"
else
    warn "judge-all exited with code $RUN_EXIT after ${DURATION}s — continuing to aggregate partial results"
fi

# --- Aggregate everything in one Python pass --------------------------------
# Python reads the run log + judge JSON files, computes token totals, costs,
# and renders the markdown report directly. Returns one tab-separated summary
# line on stdout for bash to capture for the history TSV.
PIN_W="${PRICE_IN[$WORKFLOW_MODEL]:-0}"; POUT_W="${PRICE_OUT[$WORKFLOW_MODEL]:-0}"
PIN_J="${PRICE_IN[$JUDGE_MODEL]:-0}";   POUT_J="${PRICE_OUT[$JUDGE_MODEL]:-0}"

SUMMARY=$(python3 - <<PY
import json, re, statistics
from pathlib import Path
from collections import defaultdict

LOG     = Path("$LOG")
REPORT  = Path("$REPORT")
PROJECT = Path("$PROJECT_DIR")
START_E = $START_EPOCH
VERSION = "$VERSION"
WORKFLOW_MODEL = "$WORKFLOW_MODEL"
JUDGE_MODEL    = "$JUDGE_MODEL"
START_HUMAN    = "$START_HUMAN"
DURATION       = $DURATION
RUN_EXIT       = $RUN_EXIT
PIN_W, POUT_W  = $PIN_W, $POUT_W
PIN_J, POUT_J  = $PIN_J, $POUT_J

# 1. Workflow tokens (exact, from Spring AI usage log lines)
log = LOG.read_text(errors="ignore")
prompt_toks     = sum(int(m) for m in re.findall(r"(\d+) prompt tokens",     log))
completion_toks = sum(int(m) for m in re.findall(r"(\d+) completion tokens", log))

# 2. Judge JSON files written during this run
judge_files = sorted(
    f for f in PROJECT.glob("*/judge-results/*_judge_result_*.json")
    if f.stat().st_mtime >= START_E
)

by_workflow = {}
fw_dims  = defaultdict(list)
out_dims = defaultdict(list)
for f in judge_files:
    try:
        d = json.loads(f.read_text())
    except Exception:
        continue
    name = d.get("workflowName", "?")
    fs   = d.get("frameworkScores", {})
    os_  = d.get("outputScores", {})
    overall = fs.get("overall")
    if overall is None: continue
    ts = d.get("timestamp", "")
    prev = by_workflow.get(name)
    if prev and ts <= prev["timestamp"]: continue
    by_workflow[name] = {
        "overall": overall, "framework": fs, "output": os_,
        "verdict": (d.get("verdict","") or "").replace("|","·").replace("\n"," ")[:140],
        "exec_ms": d.get("workflowMetadata",{}).get("executionTimeMs", 0),
        "successful": d.get("successful", False),
        "timestamp": ts,
    }

for w in by_workflow.values():
    for k, v in w["framework"].items():
        if isinstance(v,(int,float)) and k != "overall": fw_dims[k].append(v)
    for k, v in w["output"].items():
        if isinstance(v,(int,float)): out_dims[k].append(v)

overall_scores = [w["overall"] for w in by_workflow.values()]
mean_overall   = statistics.mean(overall_scores)   if overall_scores else 0
median_overall = statistics.median(overall_scores) if overall_scores else 0
num_judged     = len(by_workflow)

# 3. Failed workflows from log
failed = re.findall(r"<<< (\S+) FAILED: (.+)", log)
failed = {n: msg.strip() for n, msg in failed}

# 4. Cost calculation
def cost(p_in, p_out, pin, pout):
    return (p_in/1e6)*pin + (p_out/1e6)*pout
judge_prompt_toks     = num_judged * 2500
judge_completion_toks = num_judged * 500
workflow_cost = cost(prompt_toks, completion_toks, PIN_W, POUT_W)
judge_cost    = cost(judge_prompt_toks, judge_completion_toks, PIN_J, POUT_J)
total_cost    = workflow_cost + judge_cost

# 5. Render markdown
out = []
A = out.append
A("# SwarmAI Full Regression Report\n")
A(f"- **Framework version:** \`{VERSION}\`")
A(f"- **Run started:**       {START_HUMAN}")
A(f"- **Duration:**          {DURATION}s ({DURATION//60}m {DURATION%60}s)")
A(f"- **Workflow model:**    \`{WORKFLOW_MODEL}\`")
A(f"- **Judge model:**       \`{JUDGE_MODEL}\`")
A(f"- **Workflows passed:**  {num_judged} judged + {len(failed)} failed = {num_judged+len(failed)} invoked")
A(f"- **Run exit code:**     {RUN_EXIT}")
A(f"- **Log:**               \`{LOG}\`")
A("")

A("## Failed workflows\n")
if failed:
    A("| Workflow | Failure reason |"); A("|---|---|")
    for n, msg in sorted(failed.items()):
        A(f"| \`{n}\` | {msg[:140].replace('|','·')} |")
else:
    A("_All judged workflows passed._")
A("")

A("## Per-workflow judge scores (FRAMEWORK overall)\n")
A("| Workflow | Overall (/100) | Exec ms | Verdict |")
A("|---|---:|---:|---|")
for name in sorted(by_workflow.keys()):
    w = by_workflow[name]
    A(f"| \`{name}\` | **{w['overall']}** | {w['exec_ms']:,} | {w['verdict']} |")
A("")

A("## Framework dimension averages\n")
A("| Dimension | Mean | Min | Max | n |"); A("|---|---:|---:|---:|---:|")
for k in sorted(fw_dims.keys()):
    v = fw_dims[k]
    A(f"| {k} | {statistics.mean(v):.1f} | {min(v)} | {max(v)} | {len(v)} |")
A("")

A("## Output dimension averages\n")
A("| Dimension | Mean | Min | Max | n |"); A("|---|---:|---:|---:|---:|")
for k in sorted(out_dims.keys()):
    v = out_dims[k]
    A(f"| {k} | {statistics.mean(v):.1f} | {min(v)} | {max(v)} | {len(v)} |")
A("")

A("## Aggregate score\n")
A(f"- **Workflows judged:** {num_judged}")
A(f"- **Mean overall:**     {mean_overall:.1f} / 100")
A(f"- **Median overall:**   {median_overall:.1f} / 100")
if overall_scores:
    A(f"- **Min:**              {min(overall_scores)}")
    A(f"- **Max:**              {max(overall_scores)}")
A("")

A("## Cost breakdown (USD)\n")
A("| Component | Model | Input tok | Output tok | Cost |"); A("|---|---|---:|---:|---:|")
A(f"| Workflows | \`{WORKFLOW_MODEL}\` | {prompt_toks:,} | {completion_toks:,} | \${workflow_cost:.4f} |")
A(f"| Judge     | \`{JUDGE_MODEL}\` | {judge_prompt_toks:,} | {judge_completion_toks:,} | \${judge_cost:.4f} |")
A(f"| **TOTAL** |   |   |   | **\${total_cost:.4f}** |")
A("")
A("_Workflow counts are exact (Spring AI usage logs); judge counts estimated at ~2500 in / 500 out per call._")

REPORT.write_text("\n".join(out))

# 6. Emit summary line for bash to capture (TAB-separated)
print(f"{num_judged}\t{prompt_toks}\t{completion_toks}\t{judge_prompt_toks}\t{judge_completion_toks}\t{workflow_cost:.4f}\t{judge_cost:.4f}\t{total_cost:.4f}\t{mean_overall:.1f}")
PY
)

IFS=$'\t' read -r NUM_JUDGED WORKFLOW_PROMPT_TOKENS WORKFLOW_COMPLETION_TOKENS \
    JUDGE_PROMPT_TOKENS JUDGE_COMPLETION_TOKENS \
    WORKFLOW_COST JUDGE_COST TOTAL_COST MEAN_SCORE <<< "$SUMMARY"

info "Found $NUM_JUDGED judge result files from this run"

# --- Append to history TSV ---------------------------------------------------
if [ ! -f "$HISTORY" ]; then
    printf "timestamp\tversion\tworkflow_model\tjudge_model\tnum_judged\tmean_score\tduration_sec\tworkflow_cost_usd\tjudge_cost_usd\ttotal_cost_usd\trun_exit\n" > "$HISTORY"
fi
printf "%s\t%s\t%s\t%s\t%d\t%s\t%d\t%s\t%s\t%s\t%d\n" \
    "$START_HUMAN" "$VERSION" "$WORKFLOW_MODEL" "$JUDGE_MODEL" \
    "$NUM_JUDGED" "${MEAN_SCORE:-0}" "$DURATION" \
    "$WORKFLOW_COST" "$JUDGE_COST" "$TOTAL_COST" "$RUN_EXIT" >> "$HISTORY"

# --- Print summary -----------------------------------------------------------
echo ""
info "==============================================================="
info "  Regression complete in ${DURATION}s"
info "==============================================================="
info "  Version           : $VERSION"
info "  Workflows judged  : $NUM_JUDGED"
info "  Mean score        : ${MEAN_SCORE:-0}/100"
info "  Workflow tokens   : ${WORKFLOW_PROMPT_TOKENS} in / ${WORKFLOW_COMPLETION_TOKENS} out → \$$WORKFLOW_COST"
info "  Judge tokens (est): ${JUDGE_PROMPT_TOKENS} in / ${JUDGE_COMPLETION_TOKENS} out → \$$JUDGE_COST"
info "  TOTAL COST        : \$$TOTAL_COST"
info "  Report            : $REPORT"
info "  History           : $HISTORY"
info "==============================================================="

if [ $RUN_EXIT -ne 0 ]; then
    warn "judge-all exited non-zero — inspect $LOG"
    exit $RUN_EXIT
fi
