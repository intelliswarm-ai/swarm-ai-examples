#!/usr/bin/env bash
# Run this example: ./jira-ticket-management/run.sh [JQL]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "jira" "$@"
