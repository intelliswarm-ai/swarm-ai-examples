#!/usr/bin/env bash
# Run this example: ./enterprise-self-improving-with-governance/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "enterprise-governed" "$@"
