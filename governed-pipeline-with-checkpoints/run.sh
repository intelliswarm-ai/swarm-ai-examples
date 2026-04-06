#!/usr/bin/env bash
# Run this example: ./governed-pipeline-with-checkpoints/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "governed-pipeline" "$@"
