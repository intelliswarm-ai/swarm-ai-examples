#!/usr/bin/env bash
# Run this example: ./reliability-substrate/run.sh
# Offline self-test of every reliability primitive shipped in 1.0.24.
# No API keys required.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
DEMO="${DEMO:-1}" exec ./run.sh "reliability-substrate" "$@"
