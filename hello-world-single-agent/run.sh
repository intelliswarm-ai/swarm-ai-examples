#!/usr/bin/env bash
# Run this example: ./hello-world-single-agent/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "bare-minimum" "$@"
