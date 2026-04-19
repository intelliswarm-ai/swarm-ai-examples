#!/usr/bin/env bash
# Bring up a WireMock-backed Jira Cloud mock so the example can run without a
# real Atlassian account. Mappings stubbed in ./wiremock/mappings.
# Usage: ./docker-up.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

docker compose up -d
echo "Waiting for WireMock to become healthy..."
for _ in $(seq 1 30); do
  status=$(docker inspect -f '{{.State.Health.Status}}' swarmai-jira-mock 2>/dev/null || echo starting)
  [ "$status" = "healthy" ] && break
  sleep 2
done
if [ "$(docker inspect -f '{{.State.Health.Status}}' swarmai-jira-mock)" != "healthy" ]; then
  echo "Jira mock did not become healthy within 60s" >&2
  exit 1
fi

cat <<EOF
Ready. Jira mock at http://localhost:8080

Set these in your env or .env before running the example:
  JIRA_BASE_URL=http://localhost:8080
  JIRA_EMAIL=mock@swarmai.local
  JIRA_API_TOKEN=mock-token

Then:
  ./run.sh jira "project = ACME AND status = \"In Progress\""
EOF
