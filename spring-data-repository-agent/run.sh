#!/usr/bin/env bash
# Run this example: ./spring-data-repository-agent/run.sh [question]
#
# --swarmai.examples.spring-data.enabled=true activates this example's @ConditionalOnProperty
# beans. JPA auto-configs are filtered by @ImportAutoConfiguration (respects profile
# excludes), so we also pass:
#   --spring.autoconfigure.exclude=<everything-except-JPA>  — replaces profile exclude list
#                                                            so HibernateJpaAutoConfiguration
#                                                            can activate.
#   --spring.jpa.hibernate.ddl-auto=update                 — Hibernate creates the Customer
#                                                            table in the shared H2 file.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
SPRING_DATA_EXCLUDES="\
org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,\
org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreAutoConfiguration,\
org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration,\
org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration,\
org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration,\
org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration,\
org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration,\
org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration,\
org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration,\
org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
exec ./run.sh "spring-data" \
    --swarmai.examples.spring-data.enabled=true \
    "--spring.autoconfigure.exclude=${SPRING_DATA_EXCLUDES}" \
    --spring.jpa.hibernate.ddl-auto=update \
    "$@"
