# Notion Workspace Search Example

Exercises **`NotionTool`** — a knowledge-base agent searches your Notion workspace, retrieves a
page, and answers questions grounded in the page content (with URL citation).

## Prerequisites

**API key (required):**

| Env var        | How to get it                                                          |
|----------------|------------------------------------------------------------------------|
| `NOTION_TOKEN` | Create an internal integration at https://www.notion.so/profile/integrations and copy the secret. |

```bash
export NOTION_TOKEN=secret_yourNotionIntegrationToken
```

**Notion is cloud-only — no Docker image** (it's a SaaS product). After creating the integration you
**must explicitly share at least one page or database with it**, otherwise every API call returns
403 with a "Can't find the requested resource" error. In Notion:

1. Open the page / database you want the agent to access.
2. Click the `⋯` menu → `Add connections` → select your integration.

The tool surfaces 403 errors with that exact hint.

**Infrastructure:** none — calls go to `api.notion.com`.

## Run

```bash
./run.sh notion                       # default: query='goals'
./run.sh notion "Q2 roadmap"
./run.sh notion "sprint retrospective"
./run.sh notion "API docs"
```

## What this proves about the tool

- `operation=search` handles workspace-wide search; `filter_type=page|database` narrows it.
- `operation=retrieve_page` normalises hyphenated UUIDs and returns both metadata + body blocks.
- Body blocks (paragraphs, headings, to-dos, bulleted lists) round-trip to clean markdown — the
  LLM sees `# Q2 goals`, `[x] Done item`, `[ ] Open item`, not raw Notion rich-text JSON.
- `operation=query_database` supports raw Notion filter JSON for power users.
- Missing token OR unshared pages produce actionable errors (not stack traces).
