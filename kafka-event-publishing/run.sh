#!/usr/bin/env bash
# Run this example: ./kafka-event-publishing/run.sh [topic]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "kafka" "$@"
