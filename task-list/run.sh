#!/usr/bin/env bash
# Run this example: ./task-list/run.sh
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "task-list" "$@"
