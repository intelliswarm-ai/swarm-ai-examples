#!/usr/bin/env bash
# Run this example: ./notion-workspace-search/run.sh [query]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "notion" "$@"
