# SwarmAI Quickstart Template

**The minimum viable SwarmAI application.** Clone it, `mvn spring-boot:run`, see an agent solve a
math word problem by calling the built-in calculator tool — no API keys, no network. Then swap in
whichever tool you actually care about.

Four files are load-bearing:

```
quickstart-template/
├── pom.xml                                                      ← two SwarmAI deps + Spring Boot starter
├── src/main/java/com/example/swarmai/QuickstartApplication.java ← ~40 lines, the whole app
└── src/main/resources/application.yml                           ← Ollama (default) / OpenAI profile
```

## Run in 60 seconds

### Option A — local Ollama (free, no API keys)

```bash
# 1. Start Ollama with a model
ollama run mistral

# 2. In another shell:
mvn spring-boot:run
```

### Option B — OpenAI (recommended for sharing demos)

`application.yml` defaults the `openai` profile to **`gpt-4o-mini`** — same workflow, ~95% cheaper than `gpt-4o`. Override the model with `OPENAI_MODEL=gpt-4o` when you want the bigger one.

1. Open `pom.xml` and replace:
   ```xml
   <artifactId>spring-ai-starter-model-ollama</artifactId>
   ```
   with:
   ```xml
   <artifactId>spring-ai-starter-model-openai</artifactId>
   ```
2. Run:
   ```bash
   export SPRING_AI_OPENAI_API_KEY=sk-...
   mvn spring-boot:run -Dspring-boot.run.profiles=openai
   ```

Expected output — the agent explains the purchase math and prints a final dollar amount, with the
calculator expression it evaluated cited inline.

## Swap the tool

Open `QuickstartApplication.java`. Change **two things**:

1. The `import` for the tool
2. The `CommandLineRunner` parameter type and the `.tools(List.of(...))` call

The rest of the file (Agent/Task/Swarm wiring) doesn't change.

All of these ship in `swarmai-tools:1.0.7`. Pick one, update the import + constructor
parameter, and you're done.

| Tool                         | Import from package                            | Extra config required                              |
|------------------------------|------------------------------------------------|----------------------------------------------------|
| `CalculatorTool` *(default)* | `ai.intelliswarm.swarmai.tool.common`          | none                                               |
| `WebSearchTool`              | `ai.intelliswarm.swarmai.tool.common`          | any of `ALPHA_VANTAGE_API_KEY`, `GOOGLE_*`, etc.   |
| `HttpRequestTool`            | `ai.intelliswarm.swarmai.tool.common`          | none                                               |
| `FileReadTool`               | `ai.intelliswarm.swarmai.tool.common`          | none                                               |
| `WikipediaTool`              | `ai.intelliswarm.swarmai.tool.research`        | none                                               |
| `ArxivTool`                  | `ai.intelliswarm.swarmai.tool.research`        | none                                               |
| `WolframAlphaTool`           | `ai.intelliswarm.swarmai.tool.research`        | `WOLFRAM_APPID`                                    |
| `OpenWeatherMapTool`         | `ai.intelliswarm.swarmai.tool.data`            | `OPENWEATHER_API_KEY`                              |
| `JiraTool`                   | `ai.intelliswarm.swarmai.tool.productivity`    | `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN`    |
| `KafkaProducerTool`          | `ai.intelliswarm.swarmai.tool.messaging`       | `KAFKA_BOOTSTRAP_SERVERS`                          |
| `S3Tool`                     | `ai.intelliswarm.swarmai.tool.cloud`           | AWS creds (env or `~/.aws/credentials`)            |
| `ImageGenerationTool`        | `ai.intelliswarm.swarmai.tool.vision`          | `OPENAI_API_KEY`                                   |
| `PineconeVectorTool`         | `ai.intelliswarm.swarmai.tool.vector`          | `PINECONE_API_KEY`, `PINECONE_INDEX_HOST`          |
| `OpenApiToolkit`             | `ai.intelliswarm.swarmai.tool.integrations`    | none (feed it any OpenAPI 3.x spec URL)            |
| `SpringDataRepositoryTool`   | `ai.intelliswarm.swarmai.tool.data.repository` | a running `JpaRepository` in your app              |

For each tool, the corresponding top-level example directory (e.g. `../jira-ticket-management/`)
shows the full prompt + task wording you'd want — copy those into your `Task.builder().description(...)`.

## What's next

- **Deeper examples** — `../jira-ticket-management/`, `../stock-market-analysis/`, `../rag-retrieval-augmented-research/` and others show multi-agent patterns, parallel swarms, human-in-the-loop gates, and more.
- **Framework docs** — https://github.com/IntelliSwarm-ai/swarm-ai for the full API reference.
- **Maven Central** — check https://central.sonatype.com/namespace/ai.intelliswarm for the latest published version, and bump `swarmai.version` in `pom.xml`.
