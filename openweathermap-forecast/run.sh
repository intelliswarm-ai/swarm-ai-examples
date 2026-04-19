#!/usr/bin/env bash
# Run this example: ./openweathermap-forecast/run.sh [city]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "weather" "$@"
