#!/usr/bin/env bash
# Run this example: ./codebase-analysis-workflow/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "codebase-analysis" "$@"
