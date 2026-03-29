FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
COPY target/swarmai-examples-*.jar app.jar

ENV SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
ENV SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=mistral:7b
ENV WORKFLOW=stock-analysis
ENV WORKFLOW_ARGS=AAPL
ENV JAVA_OPTS="-Xmx2g"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar $WORKFLOW $WORKFLOW_ARGS"]
