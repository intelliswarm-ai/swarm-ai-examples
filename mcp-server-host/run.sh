#!/usr/bin/env bash
#
# MCP Server Host example
# =======================
# Two modes:
#   ./run.sh                 catalog mode — boots the framework with the MCP server
#                            enabled, lists what would be published, and round-trips
#                            a real tool call through the framework's plumbing.
#   ./run.sh --serve         live MCP server over stdio. Logs go to stderr so the
#                            stdout pipe stays clean for the MCP protocol. This is
#                            the form Claude Desktop / Cursor / Cline launch.
#
# Both modes set swarmai.mcp.server.enabled=true so the McpServerHost bean actually
# starts. The catalog-mode introspection requires the bean to exist.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

mode="catalog"
extra_args=()
for a in "$@"; do
    case "$a" in
        --serve|serve)             mode="serve" ;;
        --client-demo|client-demo) mode="catalog"; extra_args+=("--client-demo") ;;
        *)                         extra_args+=("$a") ;;
    esac
done

# Delegate to the project-level run.sh — it handles ollama/openai profile selection,
# detects the JAR, and forwards arguments. We just push the right MCP-server flags.
cd "$PROJECT_DIR"

if [ "$mode" = "serve" ]; then
    # In serve mode the JVM's stdout is the MCP wire; nothing else may write there.
    # SPRING_MAIN_BANNER_MODE=off suppresses Spring's startup banner; the redirect of
    # all logger output to stderr is handled by Spring Boot's default channel for
    # stderr in production profiles + an explicit override below.
    exec ./run.sh mcp-server --serve \
        --spring.main.banner-mode=off \
        --logging.console-output=stderr \
        --swarmai.mcp.server.enabled=true \
        --swarmai.mcp.server.name=swarmai-example \
        --swarmai.mcp.server.version=1.0 \
        --swarmai.mcp.server.instructions="Tools and agents from the SwarmAI mcp-server-host example." \
        "${extra_args[@]}"
else
    # Catalog mode — print to stdout normally; users see the live catalog and a
    # ready-to-paste config for Claude Desktop.
    exec ./run.sh mcp-server \
        --swarmai.mcp.server.enabled=true \
        --swarmai.mcp.server.name=swarmai-example \
        --swarmai.mcp.server.version=1.0 \
        "${extra_args[@]}"
fi
