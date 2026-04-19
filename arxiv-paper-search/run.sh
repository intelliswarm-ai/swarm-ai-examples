#!/usr/bin/env bash
# Run this example: ./arxiv-paper-search/run.sh [topic]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "arxiv" "$@"
