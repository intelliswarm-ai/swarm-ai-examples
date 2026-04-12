package ai.intelliswarm.swarmai.examples.agenttesting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Conversation Memory Workflow - Regression Tests")
class ConversationMemoryWorkflowRegressionTest {

    @Test
    @DisplayName("Recall task does not depend on collector task across separate swarms")
    void recallTaskDoesNotDeclareCrossSwarmDependency() throws IOException {
        Path workflowSource = Path.of(
                "conversation-memory-persistence/src/main/java/ai/intelliswarm/swarmai/examples/memorypersistence/ConversationMemoryWorkflow.java"
        );

        String source = Files.readString(workflowSource);

        assertFalse(
                source.contains(".dependsOn(researchTask)"),
                "Cross-swarm task dependency found. Recall swarm tasks must not dependOn tasks from collector swarm."
        );
    }
}
