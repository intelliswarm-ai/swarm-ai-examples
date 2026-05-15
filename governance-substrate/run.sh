#!/usr/bin/env bash
# Run this example: ./governance-substrate/run.sh
# Offline self-test of the Phase-2 governance primitives shipped in 1.0.24.
# No API keys required.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
DEMO="${DEMO:-1}" exec ./run.sh "governance-substrate" "$@"
