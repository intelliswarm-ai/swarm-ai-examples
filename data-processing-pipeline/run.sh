#!/usr/bin/env bash
# Run this example: ./data-processing-pipeline/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "data-pipeline" "$@"
