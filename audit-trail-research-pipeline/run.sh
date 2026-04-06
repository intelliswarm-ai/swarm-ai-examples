#!/usr/bin/env bash
# Run this example: ./audit-trail-research-pipeline/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "audited-research" "$@"
