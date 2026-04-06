#!/usr/bin/env bash
# Run this example: ./customer-support-routing/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "customer-support" "$@"
