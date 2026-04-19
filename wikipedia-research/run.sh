#!/usr/bin/env bash
# Run this example: ./wikipedia-research/run.sh [subject]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "wikipedia" "$@"
