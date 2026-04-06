#!/usr/bin/env bash
# Run this example: ./investment-analysis-parallel-swarm/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "investment-swarm" "$@"
