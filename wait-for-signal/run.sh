#!/usr/bin/env bash
# Run this example: ./wait-for-signal/run.sh
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "wait-for-signal" "$@"
