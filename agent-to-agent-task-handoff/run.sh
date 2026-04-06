#!/usr/bin/env bash
# Run this example: ./agent-to-agent-task-handoff/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "agent-handoff" "$@"
