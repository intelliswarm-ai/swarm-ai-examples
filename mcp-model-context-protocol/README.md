# MCP Research Workflow

Demonstrates external tool integration via the Model Context Protocol (MCP), enabling agents to fetch live web content and search results from MCP-compatible servers.

## Architecture

```
   +---------------------+       +---------------------+
   | MCP Fetch Server    |       | MCP Search Server   |
   | (uvx / npx stdio)  |       | (uvx / npx stdio)   |
   +----------+----------+       +----------+----------+
              |                             |
              +----------+------------------+
                         |
                    MCP stdio transport
                         |
                         v
              +--------------------+
              | Primary Researcher |
              | (MCP tools: fetch, |
              |  brave_search)     |
              +--------+-----------+
                       |
              SEQUENTIAL PROCESS
                       |
                       v
              +--------------------+
              | Analysis           |
              | Specialist         |
              | (no tools)         |
              +--------+-----------+
                       |
                       v
              +--------------------+
              | Report Writer      |
              | (no tools)         |
              +--------------------+
                       |
                       v
              output/mcp_research_report.md
```

## What You'll Learn

- MCP (Model Context Protocol) tool integration via stdio transport
- McpToolAdapter for connecting external MCP servers as SwarmAI tools
- Graceful degradation when MCP servers are unavailable (falls back to LLM knowledge)
- Environment-variable-driven MCP server configuration
- Sequential research pipeline: gather live data, analyze, report
- Source attribution distinguishing [FROM SOURCE] vs [FROM KNOWLEDGE]

## Prerequisites

- Ollama running locally (or OpenAI/Anthropic API key configured)
- For live web data (optional):
  - MCP Fetch server: `pip install mcp-server-fetch` (Python) or `npx @modelcontextprotocol/server-fetch`
  - MCP Brave Search server: `pip install mcp-server-brave-search` (needs `BRAVE_API_KEY`)
- Environment variables in `.env`:
  ```
  MCP_FETCH_ENABLED=true
  MCP_FETCH_CMD=uvx
  MCP_FETCH_ARGS=mcp-server-fetch
  MCP_SEARCH_ENABLED=true
  MCP_SEARCH_CMD=uvx
  MCP_SEARCH_ARGS=mcp-server-brave-search
  ```

## Run

```bash
./scripts/run.sh mcp-research
./scripts/run.sh mcp-research "impact of AI agents on enterprise software 2026"
./scripts/run.sh mcp-research "open source LLM frameworks comparison"
```

## How It Works

On startup, the workflow checks for MCP server configuration via environment variables. If `MCP_FETCH_ENABLED=true`, it connects to the MCP Fetch server via stdio and registers its tools (e.g., `fetch` for downloading web page content). Similarly for Brave Search. These MCP tools are assigned to the Primary Researcher agent, who uses them to search the web and fetch full-text content from the most relevant URLs. The Analysis Specialist then identifies trends, comparisons, and opportunity gaps from the raw research. Finally, the Report Writer synthesizes everything into an executive report. If no MCP tools are configured, agents fall back to their built-in knowledge and clearly mark outputs as `[FROM KNOWLEDGE]`.

## Key Code

```java
// Connect to MCP servers via stdio and register tools dynamically
if ("true".equalsIgnoreCase(mcpFetchEnabled)) {
    String fetchCmd = System.getenv("MCP_FETCH_CMD");
    String fetchArgs = System.getenv("MCP_FETCH_ARGS");
    List<BaseTool> mcpTools = McpToolAdapter.fromServer(
            fetchCmd, fetchArgs.split("\\s+"));
    researchTools.addAll(mcpTools);
}

// Dynamically add MCP tools to the researcher agent
Agent.Builder primaryResearcherBuilder = Agent.builder()
        .role("Senior Research Analyst")
        .chatClient(chatClient);
for (BaseTool tool : researchTools) {
    primaryResearcherBuilder.tool(tool);
}
```

## Output

- `output/mcp_research_report.md` -- Executive research report with:
  - Executive summary (5 key takeaways)
  - Research findings with source URLs
  - Analysis and trends
  - Strategic implications and recommendations
  - Sources and references (all URLs cited)
  - Data quality note (live sources vs LLM knowledge)

## Customization

- Add more MCP servers by setting additional environment variables (e.g., `MCP_DB_ENABLED`, `MCP_DB_CMD`)
- Replace MCP stdio transport with HTTP transport by modifying the `McpToolAdapter.fromServer()` calls
- Increase `maxTurns` on the Primary Researcher (default 3) for deeper web crawling
- Add a fact-checking agent between analysis and reporting for higher confidence
- Switch process type from `SEQUENTIAL` to `PARALLEL` if primary research and analysis can run independently

## YAML DSL

MCP tool integration requires programmatic setup that is not yet available in the YAML DSL. The research workflow pattern is available in YAML -- see [`research-pipeline.yaml`](../../../../../resources/workflows/research-pipeline.yaml).
