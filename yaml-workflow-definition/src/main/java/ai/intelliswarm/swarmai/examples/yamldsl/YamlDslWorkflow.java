/*
 * SwarmAI Framework
 * Copyright (c) 2025 IntelliSwarm.ai (Apache License 2.0)
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai.examples.yamldsl;

import ai.intelliswarm.swarmai.dsl.SwarmLoader;
import ai.intelliswarm.swarmai.dsl.compiler.CompiledWorkflow;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * YAML DSL Workflow Example
 *
 * Demonstrates how the YAML DSL reduces a multi-agent workflow from
 * 60+ lines of Java builder chains to a single YAML file and 3 lines of code.
 *
 * <h2>Before (Java builders):</h2>
 * <pre>{@code
 * Agent researcher = Agent.builder()
 *     .id("researcher")
 *     .role("Senior Research Analyst")
 *     .goal("Gather comprehensive information")
 *     .backstory("You are a seasoned research analyst...")
 *     .chatClient(chatClient)
 *     .maxTurns(3)
 *     .temperature(0.7)
 *     .build();
 *
 * Agent analyst = Agent.builder()
 *     .id("analyst")
 *     .role("Data Analyst")
 *     .goal("Analyze findings")
 *     .backstory("You are a data analyst...")
 *     .chatClient(chatClient)
 *     .temperature(0.3)
 *     .build();
 *
 * Agent writer = Agent.builder()
 *     .id("writer")
 *     .role("Technical Writer")
 *     .goal("Produce a report")
 *     .backstory("You are a technical writer...")
 *     .chatClient(chatClient)
 *     .temperature(0.5)
 *     .build();
 *
 * Task gather = Task.builder()
 *     .id("gather")
 *     .description("Research the topic...")
 *     .expectedOutput("A comprehensive research summary")
 *     .agent(researcher)
 *     .build();
 *
 * Task analyze = Task.builder()
 *     .id("analyze")
 *     .description("Analyze the findings...")
 *     .expectedOutput("Structured analysis")
 *     .agent(analyst)
 *     .dependsOn(gather)
 *     .build();
 *
 * Task report = Task.builder()
 *     .id("report")
 *     .description("Write the final report...")
 *     .expectedOutput("Executive report")
 *     .agent(writer)
 *     .dependsOn(analyze)
 *     .outputFormat(OutputFormat.MARKDOWN)
 *     .outputFile("output/report.md")
 *     .build();
 *
 * BudgetPolicy policy = BudgetPolicy.builder()
 *     .maxTotalTokens(100_000)
 *     .maxCostUsd(5.0)
 *     .onExceeded(BudgetAction.WARN)
 *     .build();
 *
 * Swarm swarm = Swarm.builder()
 *     .agent(researcher)
 *     .agent(analyst)
 *     .agent(writer)
 *     .task(gather)
 *     .task(analyze)
 *     .task(report)
 *     .process(ProcessType.SEQUENTIAL)
 *     .budgetPolicy(policy)
 *     .budgetTracker(new InMemoryBudgetTracker(policy))
 *     .eventPublisher(eventPublisher)
 *     .build();
 *
 * SwarmOutput output = swarm.kickoff(Map.of("topic", "AI Safety"));
 * // Total: ~80 lines of Java
 * }</pre>
 *
 * <h2>After (YAML DSL):</h2>
 * <pre>{@code
 * // The workflow is defined in workflows/research-pipeline.yaml
 * Swarm swarm = swarmLoader.load("workflows/research-pipeline.yaml",
 *     Map.of("topic", "AI Safety", "outputDir", "output"));
 * SwarmOutput output = swarm.kickoff(Map.of());
 * // Total: 2 lines of Java + 1 YAML file
 * }</pre>
 */
@Component
public class YamlDslWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(YamlDslWorkflow.class);
    @Autowired private LLMJudge judge;

    private final SwarmLoader swarmLoader;

    public YamlDslWorkflow(SwarmLoader swarmLoader) {
        this.swarmLoader = swarmLoader;
    }

    /**
     * Runs the research pipeline from a YAML definition.
     * That's it — the entire workflow is defined in the YAML file.
     */
    public SwarmOutput run(String topic) throws Exception {
        logger.info("Loading research pipeline from YAML for topic: {}", topic);

        // Load the workflow from YAML with template variables
        Swarm swarm = swarmLoader.load("workflows/research-pipeline.yaml",
                Map.of(
                        "topic", topic,
                        "outputDir", "output"
                ));

        logger.info("Compiled swarm: {} agents, {} tasks, process={}",
                swarm.getAgents().size(),
                swarm.getTasks().size(),
                swarm.getProcessType());

        // Execute the workflow
        SwarmOutput output = swarm.kickoff(Map.of());

        logger.info("Workflow completed successfully: {}", output.isSuccessful());

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("yaml-dsl", "YAML DSL declarative workflow definition with template variables",
                    output.getFinalOutput(), output.isSuccessful(), System.currentTimeMillis(),
                    swarm.getAgents().size(), swarm.getTasks().size(), swarm.getProcessType().name(),
                    "yaml-workflow-definition");
        }

        return output;
    }

    /**
     * Demonstrates loading an inline YAML workflow — useful for
     * dynamically generated workflows or quick prototyping.
     */
    public SwarmOutput runInline(String topic) throws Exception {
        logger.info("Running inline YAML workflow for topic: {}", topic);

        Swarm swarm = swarmLoader.fromYaml("""
                swarm:
                  process: SEQUENTIAL
                  agents:
                    expert:
                      role: "Domain Expert"
                      goal: "Provide expert analysis on %s"
                      backstory: "You are a world-class domain expert"
                      maxTurns: 2
                  tasks:
                    analyze:
                      description: "Provide a comprehensive analysis of %s"
                      expectedOutput: "Expert analysis report"
                      agent: expert
                """.formatted(topic, topic));

        return swarm.kickoff(Map.of());
    }

    /**
     * Runs a COMPOSITE pipeline — multiple process stages chained together.
     * Loaded from workflows/composite-analysis.yaml which defines:
     *   Stage 1: PARALLEL (gather data simultaneously)
     *   Stage 2: HIERARCHICAL (manager coordinates analysis)
     *   Stage 3: ITERATIVE (refine until quality criteria met)
     *
     * Includes compaction config, approval policy, and budget tracking.
     */
    public SwarmOutput runComposite(String topic) throws Exception {
        logger.info("Loading composite pipeline from YAML for topic: {}", topic);

        CompiledWorkflow workflow = swarmLoader.loadWorkflow(
                "workflows/composite-analysis.yaml",
                Map.of(
                        "pipelineName", topic + " Analysis Pipeline",
                        "topic", topic,
                        "outputDir", "output"
                ));

        logger.info("Compiled composite workflow: {} stages", workflow.getStageCount());

        SwarmOutput output = workflow.kickoff(Map.of());

        logger.info("Composite workflow completed: {}", output.isSuccessful());

        return output;
    }
}
