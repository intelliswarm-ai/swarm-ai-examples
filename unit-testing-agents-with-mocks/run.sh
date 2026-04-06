#!/usr/bin/env bash
# Run this example: ./unit-testing-agents-with-mocks/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "agent-testing" "$@"
