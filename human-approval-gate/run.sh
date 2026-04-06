#!/usr/bin/env bash
# Run this example: ./human-approval-gate/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "human-loop" "$@"
