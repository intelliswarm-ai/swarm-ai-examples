#!/usr/bin/env bash
# Run this example: ./background-tasks/run.sh
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "background-tasks" "$@"
