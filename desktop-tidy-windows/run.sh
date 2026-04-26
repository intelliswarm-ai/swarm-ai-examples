#!/usr/bin/env bash
# Run this example: ./desktop-tidy-windows/run.sh [folder]
#
# Asks an agent to tidy the contents of a folder (default: ~/Desktop) by
# proposing a category structure and routing each move through the supervised
# approval gate. Requires Windows + the swarmai.tools.windows tool category.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "desktop-tidy" "$@"
