#!/usr/bin/env bash
#
# SwarmAI Workflow Benchmark Runner
# Runs specified workflows and collects metrics for comparison.
#
# Usage:
#   ./scripts/benchmark.sh [workflow1] [workflow2] ...
#   ./scripts/benchmark.sh                          # runs default set
#   ./scripts/benchmark.sh --all                    # runs all workflows
#
# Metrics are written to output/<workflow>_metrics.json by each workflow.
# This script collects them into output/benchmark_summary.json at the end.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_DIR/output"
JAR="$PROJECT_DIR/target/swarmai-examples-1.0.0-SNAPSHOT.jar"

# Default workflows to benchmark (ones that don't need external API keys)
DEFAULT_WORKFLOWS=(
    "codebase-analysis"
    "data-pipeline"
)

# All workflows
ALL_WORKFLOWS=(
    "stock-analysis"
    "competitive-analysis"
    "due-diligence"
    "iterative-memo"
    "codebase-analysis"
    "web-research"
    "data-pipeline"
    "self-improving"
    "enterprise-governed"
    "pentest-swarm"
    "competitive-swarm"
    "investment-swarm"
    "audited-research"
    "governed-pipeline"
    "secure-ops"
)

# Parse arguments
if [ $# -eq 0 ]; then
    WORKFLOWS=("${DEFAULT_WORKFLOWS[@]}")
elif [ "$1" = "--all" ]; then
    WORKFLOWS=("${ALL_WORKFLOWS[@]}")
else
    WORKFLOWS=("$@")
fi

echo "========================================"
echo "  SwarmAI Benchmark Runner"
echo "========================================"
echo "  Workflows: ${WORKFLOWS[*]}"
echo "  Output:    $OUTPUT_DIR"
echo "========================================"
echo ""

# Ensure JAR is built
if [ ! -f "$JAR" ]; then
    echo "Building project..."
    cd "$PROJECT_DIR" && mvn package -DskipTests -q
fi

mkdir -p "$OUTPUT_DIR"

# Run each workflow and capture metrics
RESULTS=()
for workflow in "${WORKFLOWS[@]}"; do
    echo "--- Running: $workflow ---"
    START_TIME=$(date +%s%3N)

    # Run workflow (timeout after 10 minutes)
    timeout 600 java -jar "$JAR" "$workflow" 2>&1 | tee "$OUTPUT_DIR/${workflow}_output.log" || {
        echo "WARNING: $workflow failed or timed out"
    }

    END_TIME=$(date +%s%3N)
    WALL_TIME=$((END_TIME - START_TIME))

    # Check if metrics file was produced
    METRICS_FILE="$OUTPUT_DIR/${workflow}_metrics.json"
    if [ -f "$METRICS_FILE" ]; then
        echo "  Metrics: $METRICS_FILE"
        RESULTS+=("$METRICS_FILE")
    else
        echo "  WARNING: No metrics file produced for $workflow"
    fi

    echo "  Wall time: ${WALL_TIME}ms"
    echo ""
done

# Aggregate into summary
echo "========================================"
echo "  Generating Benchmark Summary"
echo "========================================"

SUMMARY_FILE="$OUTPUT_DIR/benchmark_summary.json"

# Use python if available, otherwise jq, otherwise raw concatenation
if command -v python3 &>/dev/null; then
    python3 -c "
import json, glob, os

metrics_files = glob.glob('$OUTPUT_DIR/*_metrics.json')
summary = {'benchmarkTimestamp': '$(date -Iseconds)', 'workflows': {}}

for f in sorted(metrics_files):
    if os.path.basename(f) == 'benchmark_summary.json':
        continue
    try:
        with open(f) as fh:
            data = json.load(fh)
            name = data.get('workflowName', os.path.basename(f))
            summary['workflows'][name] = {
                'executionTimeSec': data.get('executionTimeSec', 0),
                'totalTokens': data.get('totalTokens', 0),
                'estimatedCostUsd': data.get('estimatedCostUsd', 0),
                'totalToolCalls': data.get('totalToolCalls', 0),
                'totalTurnsUsed': data.get('totalTurnsUsed', 0),
                'compactionEvents': data.get('compactionEvents', 0),
                'toolCallsDenied': data.get('toolCallsDenied', 0),
            }
    except Exception as e:
        print(f'  Warning: Failed to parse {f}: {e}')

with open('$SUMMARY_FILE', 'w') as fh:
    json.dump(summary, fh, indent=2)

# Print summary table
print()
print(f\"{'Workflow':<25} {'Time(s)':<10} {'Tokens':<12} {'Cost(\$)':<10} {'Tools':<8} {'Turns':<8} {'Compact':<8}\")
print('-' * 81)
for name, m in summary['workflows'].items():
    print(f\"{name:<25} {m['executionTimeSec']:<10.1f} {m['totalTokens']:<12} {m['estimatedCostUsd']:<10.4f} {m['totalToolCalls']:<8} {m['totalTurnsUsed']:<8} {m['compactionEvents']:<8}\")
print()
"
else
    echo "  python3 not found — metrics files are in $OUTPUT_DIR/*_metrics.json"
fi

echo "Summary written to: $SUMMARY_FILE"
echo "Individual metrics in: $OUTPUT_DIR/<workflow>_metrics.json"
echo ""
echo "Done."
