#!/usr/bin/env bash
# Run this example: ANTHROPIC_API_KEY=sk-ant-... ./llm-client-spi/run.sh
# Same task, two agents: Spring AI baseline vs AnthropicLlmClient SPI side-by-side.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
DEMO="${DEMO:-1}" exec ./run.sh "llm-client-spi" "$@"
