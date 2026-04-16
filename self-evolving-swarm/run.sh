#!/usr/bin/env bash
# Run this example: ./self-evolving-swarm/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "self-evolving" "$@"
