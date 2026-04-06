#!/usr/bin/env bash
# Run this example: ./security-penetration-testing-swarm/run.sh [args...]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "pentest-swarm" "$@"
