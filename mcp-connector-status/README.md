# MCP Connector Status (SwarmAI 1.0.24)

Prints the financial MCP connector catalog under a configurable license edition + feature-flag set. Demonstrates Phase 8's license-aware filtering and Phase 15's credential binding **without making any real HTTP calls**.

**No API key required.** Uses an in-memory fake license + in-memory demo credentials.

## What it demonstrates

- 11 predefined financial MCP connectors in `FinancialMcpConnectors`: LSEG LFA, S&P Capital IQ, FactSet, PitchBook, Chronograph, Daloopa, Morningstar, Moody's, MT Newswires, Aiera, Egnyte
- `McpConnectorRegistry.statusReport()` — why each connector is or isn't active under your license
- `McpConnectorBinder.statusReport()` — credential availability per connector
- License-tier gating: COMMUNITY → no connectors; TEAM → Egnyte; BUSINESS → mid-tier paid sources; ENTERPRISE → premium institutional sources

## Run

```bash
# Show what an ENTERPRISE license with every feature flag would activate
./mcp-connector-status/run.sh ENTERPRISE all

# Show only Egnyte under a TEAM license
./mcp-connector-status/run.sh TEAM mcp.egnyte

# Show two BUSINESS-tier connectors
./mcp-connector-status/run.sh BUSINESS mcp.daloopa,mcp.morningstar

# Show that COMMUNITY edition activates none
./mcp-connector-status/run.sh COMMUNITY
```

## To actually call live MCP endpoints

The example uses in-memory credentials for demonstration. To wire a real activation pipeline against live endpoints, configure these env vars (recognised by `McpCredentialResolver.FromEnvironment`):

```bash
# Bearer tokens
SWARM_MCP_LSEG_LFA_BEARER=...
SWARM_MCP_CHRONOGRAPH_BEARER=...
SWARM_MCP_DALOOPA_BEARER=...
SWARM_MCP_MORNINGSTAR_BEARER=...
SWARM_MCP_MT_NEWSWIRES_BEARER=...

# API keys
SWARM_MCP_FACTSET_API_KEY=...
SWARM_MCP_FACTSET_API_KEY_HEADER=X-FactSet-Key   # optional, defaults to X-API-Key

# OAuth (current access token; refresh-token plumbing is a follow-up)
SWARM_MCP_SP_CAPITAL_IQ_OAUTH_TOKEN=...
SWARM_MCP_PITCHBOOK_OAUTH_TOKEN=...
SWARM_MCP_MOODYS_OAUTH_TOKEN=...
SWARM_MCP_AIERA_OAUTH_TOKEN=...
SWARM_MCP_EGNYTE_OAUTH_TOKEN=...
```

Then swap `McpCredentialResolver.InMemory` for `new McpCredentialResolver.FromEnvironment()` in the example source.
