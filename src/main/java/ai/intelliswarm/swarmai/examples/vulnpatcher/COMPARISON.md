# vuln-patcher: SwarmAI vs Original (LangChain4j) Comparison

## Architecture Comparison

| Aspect | Original (Quarkus+LangChain4j) | SwarmAI Migration |
|--------|-------------------------------|-------------------|
| **Framework** | Quarkus 3.8 + LangChain4j 0.31 | Spring Boot 3.4 + SwarmAI 1.0 |
| **Orchestration** | Custom LLMOrchestrator.java (~200 lines) | `Swarm.builder()` (declarative) |
| **Agent definition** | 3 Java classes (~150 lines each) | 3 `Agent.builder()` calls or YAML |
| **Workflow config** | Hardcoded in Java | YAML DSL (`vuln-patcher.yaml`) |
| **Total orchestration code** | ~400 lines Java | ~80 lines YAML + ~60 lines Java |
| **LLM provider** | Ollama (local) | Any Spring AI provider (OpenAI, Anthropic, Ollama) |

## Feature Comparison

| Feature | Original | SwarmAI | Advantage |
|---------|----------|---------|-----------|
| Budget tracking | None | $10/500K token HARD_STOP | SwarmAI |
| Governance gates | None | Approval before PR creation | SwarmAI |
| Tool permissions | None | Scanner=READ_ONLY, Engineer=WRITE | SwarmAI |
| Review loop | Fixed 3-pass | Iterative until approved | SwarmAI |
| Audit trail | Prometheus metrics | Full audit + observability | SwarmAI |
| Configuration | Java code | YAML DSL (zero-code) | SwarmAI |
| Vulnerability sources | 6 (CVE, GHSA, OSV, Snyk, OSS-Index, OVAL) | 2 (CVE, OSV) + extensible | Original |
| Git providers | GitHub, GitLab, Bitbucket | GitHub (extensible) | Original |
| Reactive streaming | SSE via Quarkus Mutiny | Spring Events (internal) | Original |
| Health checks | SmallRye Health | Spring Actuator | Tie |
| Metrics | Micrometer + Prometheus | Micrometer + Prometheus | Tie |

## Quantitative Metrics (To Be Measured)

| Metric | Original | SwarmAI | Winner |
|--------|----------|---------|--------|
| Lines of code (orchestration) | ~400 | ~140 | TBD |
| Lines of code (total app) | ~2,500 | ~500 | TBD |
| Setup time (hours) | 4+ (Quarkus, Ollama, 6 sources) | 1 (Spring Boot, 2 API keys) | TBD |
| Execution time (avg) | TBD | TBD | TBD |
| Token usage (avg) | TBD | TBD | TBD |
| Cost per scan | TBD | TBD | TBD |
| Success rate (10 repos) | TBD | TBD | TBD |
| Quality score (LLM judge) | TBD | TBD | TBD |

## Gaps Found in SwarmAI (Improvement Backlog)

| # | Gap | Priority | Status |
|---|-----|----------|--------|
| 1 | No GHSA (GitHub Security Advisory) tool | HIGH | TODO |
| 2 | No Snyk/OSS-Index tools | MEDIUM | TODO |
| 3 | No SSE streaming for real-time progress | MEDIUM | TODO |
| 4 | No GitLab/Bitbucket PR tools | LOW | TODO |
| 5 | Large code diffs may exceed context window | HIGH | TODO |

## How to Run the Comparison

```bash
# 1. Run original vuln-patcher
cd D:\Intelliswarm.ai\vuln-patcher
./mvnw quarkus:dev
# POST http://localhost:8080/api/v1/vulnpatcher/scan with repo URL
# Record: time, tokens (from Ollama logs), output quality

# 2. Run SwarmAI vuln-patcher  
cd D:\Intelliswarm.ai\swarm-ai-examples
export OPENAI_API_KEY=sk-...  # or configure Ollama
mvn compile exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.vulnpatcher.VulnPatcherWorkflow"
# Record: time, tokens (from budget tracker), output quality

# 3. Score both outputs with LLM judge
# Use GPT-4 + Claude to score vulnerability detection accuracy and patch quality
```
