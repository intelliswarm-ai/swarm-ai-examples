package ai.intelliswarm.swarmai.examples.governedpipeline;

import ai.intelliswarm.swarmai.process.ProcessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Governed Pipeline Workflow - Process Type")
class GovernedPipelineWorkflowProcessTypeTest {

    @Test
    @DisplayName("Uses hierarchical process type required by manager-plus-specialists flow")
    void usesHierarchicalProcessType() {
        assertEquals(
                ProcessType.HIERARCHICAL,
                GovernedPipelineWorkflow.WORKFLOW_PROCESS_TYPE,
                "This workflow should use HIERARCHICAL process type; COMPOSITE is unsupported here"
        );
    }
}
