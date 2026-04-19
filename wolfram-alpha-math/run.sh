#!/usr/bin/env bash
# Run this example: ./wolfram-alpha-math/run.sh [question]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "wolfram" "$@"
