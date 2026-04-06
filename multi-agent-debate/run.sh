#!/usr/bin/env bash
# Run this example: ./multi-agent-debate/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "agent-debate" "$@"
