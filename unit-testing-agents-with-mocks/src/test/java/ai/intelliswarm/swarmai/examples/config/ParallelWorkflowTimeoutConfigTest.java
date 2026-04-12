package ai.intelliswarm.swarmai.examples.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelWorkflowTimeoutConfigTest {

    @Test
    void parallelWorkflowsSetSafeTimeoutAndConcurrencyLimits() throws IOException {
        assertHasTimeoutAndConcurrencyConfig(
                "stock-market-analysis/src/main/java/ai/intelliswarm/swarmai/examples/stock/StockAnalysisWorkflow.java");
        assertHasTimeoutAndConcurrencyConfig(
                "codebase-analysis-workflow/src/main/java/ai/intelliswarm/swarmai/examples/codebase/CodebaseAnalysisWorkflow.java");
        assertHasTimeoutAndConcurrencyConfig(
                "investment-due-diligence/src/main/java/ai/intelliswarm/swarmai/examples/duediligence/DueDiligenceWorkflow.java");
    }

    private static void assertHasTimeoutAndConcurrencyConfig(String sourcePath) throws IOException {
        String source = Files.readString(Path.of(sourcePath));

        assertTrue(source.contains(".config(\"perTaskTimeoutSeconds\", 900)"),
                () -> sourcePath + " must set perTaskTimeoutSeconds=900 to avoid heavy-task timeout failures");
        assertTrue(source.contains(".config(\"maxConcurrentLlmCalls\", 2)"),
                () -> sourcePath + " must cap maxConcurrentLlmCalls=2 to stabilize local-LLM parallel workflows");
    }
}
