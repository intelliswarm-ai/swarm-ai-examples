#!/usr/bin/env bash
# Run this example: ./codex-skill-creation/run.sh [GAP DESCRIPTION...]
# Default gap is a haversine-distance computation. Supply your own to drive
# Codex toward a different skill.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "codex-skill-creation" "$@"
