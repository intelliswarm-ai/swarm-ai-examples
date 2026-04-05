package ai.intelliswarm.swarmai.examples.yamlgovernance;

import ai.intelliswarm.swarmai.dsl.SwarmLoader;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * YAML Governance Pipeline — complete workflow defined in YAML with zero Java logic.
 *
 * <h2>What This Proves</h2>
 * Operations teams can define and modify governed workflows WITHOUT writing Java code.
 * The YAML file {@code workflows/yaml-governance-pipeline.yaml} contains:
 * <ul>
 *   <li><b>Agents</b> with roles, goals, temperature, permission levels</li>
 *   <li><b>Tasks</b> with dependencies, conditions, output files</li>
 *   <li><b>Budget</b> enforcement (200K tokens, $3 max)</li>
 *   <li><b>Governance gates</b> (approval required between research and writing)</li>
 *   <li><b>Tool hooks</b> (audit + rate-limit on researcher)</li>
 *   <li><b>Workflow hooks</b> (logging at start/end, checkpoints after tasks)</li>
 *   <li><b>Template variables</b> (topic, tenantId, outputDir substituted at load)</li>
 *   <li><b>Task conditions</b> (report task skipped if research doesn't contain 'MARKET')</li>
 * </ul>
 *
 * <h2>The Point</h2>
 * <pre>
 *   BEFORE (Java):  ~80 lines of builder chains, compiled into JAR, deployed
 *   AFTER  (YAML):  1 YAML file + 3 lines of Java to load and run
 *                    → Operations team can modify agents, budget, gates without recompiling
 * </pre>
 *
 * <h2>YAML Features Demonstrated</h2>
 * <pre>
 *   swarm:
 *     budget:         maxTokens, maxCostUsd, onExceeded
 *     agents:         role, goal, backstory, maxTurns, temperature, permissionMode, toolHooks
 *     tasks:          description, expectedOutput, agent, dependsOn, condition, outputFile, outputFormat
 *     governance:     approvalGates with trigger, policy, timeout
 *     hooks:          BEFORE_WORKFLOW (log), AFTER_TASK (checkpoint), AFTER_WORKFLOW (log)
 * </pre>
 */
@Component
public class YamlGovernancePipelineWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(YamlGovernancePipelineWorkflow.class);

    private final SwarmLoader swarmLoader;

    public YamlGovernancePipelineWorkflow(SwarmLoader swarmLoader) {
        this.swarmLoader = swarmLoader;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "AI governance frameworks";
        String tenantId = args.length > 1 ? args[1] : "ops-team";

        logger.info("\n" + "=".repeat(80));
        logger.info("YAML GOVERNANCE PIPELINE");
        logger.info("=".repeat(80));
        logger.info("Source:   workflows/yaml-governance-pipeline.yaml");
        logger.info("Topic:    {}", topic);
        logger.info("Tenant:   {}", tenantId);
        logger.info("Java:     3 lines (load + kickoff + print)");
        logger.info("Features: budget, governance gates, tool hooks, workflow hooks,");
        logger.info("          task conditions, output files, template variables");
        logger.info("=".repeat(80));

        // ── That's it. The entire workflow is in YAML. ─────────────────

        Swarm swarm = swarmLoader.load(
                "workflows/yaml-governance-pipeline.yaml",
                Map.of(
                        "topic", topic,
                        "tenantId", tenantId,
                        "outputDir", "output"
                ));

        logger.info("\nCompiled from YAML: {} agents, {} tasks, process={}",
                swarm.getAgents().size(),
                swarm.getTasks().size(),
                swarm.getProcessType());

        SwarmOutput output = swarm.kickoff(Map.of());

        // ── Results ────────────────────────────────────────────────────

        logger.info("\n" + "=".repeat(80));
        logger.info("YAML PIPELINE RESULTS");
        logger.info("=".repeat(80));
        logger.info("Success:  {}", output.isSuccessful());
        logger.info("Tasks:    {}", output.getTaskOutputs().size());
        logger.info("Tokens:   {}", output.getTotalTokens());

        for (var taskOutput : output.getTaskOutputs()) {
            logger.info("  [{}] {} chars", taskOutput.getTaskId(),
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0);
        }

        logger.info("\n--- Report ---\n{}", output.getFinalOutput());
        logger.info("=".repeat(80));
        logger.info("Zero Java logic — modify the YAML file and re-run without recompiling.");
    }
}
