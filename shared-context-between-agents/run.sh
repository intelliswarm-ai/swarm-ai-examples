#!/usr/bin/env bash
# Run this example: ./shared-context-between-agents/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "context-variables" "$@"
