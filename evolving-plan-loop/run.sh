#!/usr/bin/env bash
# Run this example: ./evolving-plan-loop/run.sh
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "plan-loop" "$@"
