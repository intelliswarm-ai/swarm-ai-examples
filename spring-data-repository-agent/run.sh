#!/usr/bin/env bash
# Run this example: ./spring-data-repository-agent/run.sh [question]
#
# --swarmai.examples.spring-data.enabled=true activates this example's @ConditionalOnProperty
# beans and re-enables the JPA auto-configs that the base application.yml excludes for fast
# startup in other examples.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "spring-data" --swarmai.examples.spring-data.enabled=true "$@"
