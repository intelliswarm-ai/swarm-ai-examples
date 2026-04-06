#!/usr/bin/env bash
# Run this example: ./stock-market-analysis/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "stock-analysis" "$@"
