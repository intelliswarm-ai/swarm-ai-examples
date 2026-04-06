#!/usr/bin/env bash
# Run this example: ./web-search-research-pipeline/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "web-research" "$@"
