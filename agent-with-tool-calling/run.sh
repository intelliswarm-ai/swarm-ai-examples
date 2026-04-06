#!/usr/bin/env bash
# Run this example: ./agent-with-tool-calling/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "tool-calling" "$@"
