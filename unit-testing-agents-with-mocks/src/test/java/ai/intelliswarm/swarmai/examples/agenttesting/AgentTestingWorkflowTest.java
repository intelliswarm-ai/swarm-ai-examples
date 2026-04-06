package ai.intelliswarm.swarmai.examples.agenttesting;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the Agent Testing & Evaluation workflow.
 *
 * Demonstrates how to test agent configurations, task dependencies,
 * tool hooks, score parsing logic, and metrics lifecycle WITHOUT
 * calling any LLM. All ChatClient instances are Mockito mocks.
 */
@DisplayName("Agent Testing Workflow - Unit Tests")
class AgentTestingWorkflowTest {

    private final ChatClient mockChatClient = mock(ChatClient.class);

    // =========================================================================
    // Test 1: Agent builder configuration
    // =========================================================================

    @Test
    @DisplayName("Agent builder creates agent with correct role, goal, permissions, and hooks")
    void testAgentBuilderConfiguration() {
        ToolHook dummyHook = new ToolHook() {};

        Agent writer = Agent.builder()
                .role("Content Writer")
                .goal("Write accurate articles")
                .backstory("Experienced technical writer")
                .chatClient(mockChatClient)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(dummyHook)
                .build();

        assertEquals("Content Writer", writer.getRole());
        assertEquals("Write accurate articles", writer.getGoal());
        assertEquals(PermissionLevel.READ_ONLY, writer.getPermissionMode());
        assertEquals(1, writer.getToolHooks().size());
        assertTrue(writer.getTools().isEmpty(), "Writer should have no tools");
    }

    // =========================================================================
    // Test 2: Task dependency chain
    // =========================================================================

    @Test
    @DisplayName("Task dependencies are correctly wired: evaluate depends on write")
    void testTaskDependencyChain() {
        Agent writer = Agent.builder()
                .role("Writer").goal("Write").backstory("Writer")
                .chatClient(mockChatClient).build();

        Agent evaluator = Agent.builder()
                .role("Evaluator").goal("Evaluate").backstory("Evaluator")
                .chatClient(mockChatClient).build();

        Task writeTask = Task.builder()
                .description("Write an article")
                .expectedOutput("Article text")
                .agent(writer)
                .build();

        Task evaluateTask = Task.builder()
                .description("Evaluate the article")
                .expectedOutput("Scored rubric")
                .agent(evaluator)
                .dependsOn(writeTask)
                .build();

        // writeTask has no dependencies
        assertTrue(writeTask.getDependencyTaskIds().isEmpty(),
                "Write task should have no dependencies");

        // evaluateTask depends on writeTask
        assertEquals(1, evaluateTask.getDependencyTaskIds().size());
        assertEquals(writeTask.getId(), evaluateTask.getDependencyTaskIds().get(0));

        // evaluateTask is assigned to the evaluator
        assertEquals(evaluator, evaluateTask.getAgent());
    }

    // =========================================================================
    // Test 3: ToolHook denies blocked input
    // =========================================================================

    @Test
    @DisplayName("Content filter hook denies tool calls containing blocked terms")
    void testToolHookDeniesBlockedInput() {
        ToolHook filterHook = AgentTestingWorkflow.buildContentFilterHook();

        ToolHookContext ctx = ToolHookContext.before(
                "web_search",
                Map.of("query", "fetch proprietary competitor data"),
                "writer-agent",
                "test-workflow"
        );

        ToolHookResult result = filterHook.beforeToolUse(ctx);

        assertEquals(ToolHookResult.Action.DENY, result.action());
        assertNotNull(result.message());
        assertTrue(result.message().contains("proprietary"),
                "Deny message should reference the blocked term");
    }

    // =========================================================================
    // Test 4: ToolHook allows normal input
    // =========================================================================

    @Test
    @DisplayName("Content filter hook allows tool calls with normal input")
    void testToolHookAllowsNormalInput() {
        ToolHook filterHook = AgentTestingWorkflow.buildContentFilterHook();

        ToolHookContext ctx = ToolHookContext.before(
                "web_search",
                Map.of("query", "latest trends in cloud computing"),
                "writer-agent",
                "test-workflow"
        );

        ToolHookResult result = filterHook.beforeToolUse(ctx);

        assertEquals(ToolHookResult.Action.ALLOW, result.action());
        assertNull(result.message());
    }

    // =========================================================================
    // Test 5: Score parsing logic
    // =========================================================================

    @Test
    @DisplayName("parseScores extracts all five criteria from evaluator output")
    void testScoreParsingLogic() {
        String evaluatorOutput = """
                Accuracy: 8/10 - Claims are well-supported by evidence
                Completeness: 7/10 - Covers main points but misses edge cases
                Clarity: 9/10 - Very well-structured and readable
                Evidence: 6/10 - Could use more specific data points
                Relevance: 10/10 - Stays tightly focused on the topic
                OVERALL: Good article with room for improvement in evidence.
                """;

        Map<String, Integer> scores = AgentTestingWorkflow.parseScores(evaluatorOutput);

        assertEquals(5, scores.size(), "Should extract all 5 criteria");
        assertEquals(8, scores.get("Accuracy"));
        assertEquals(7, scores.get("Completeness"));
        assertEquals(9, scores.get("Clarity"));
        assertEquals(6, scores.get("Evidence"));
        assertEquals(10, scores.get("Relevance"));

        double average = scores.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        assertEquals(8.0, average, 0.01, "Average should be 8.0");
    }

    @Test
    @DisplayName("parseScores handles null and missing criteria gracefully")
    void testScoreParsingWithNullInput() {
        Map<String, Integer> scores = AgentTestingWorkflow.parseScores(null);
        assertTrue(scores.isEmpty(), "Null input should return empty map");

        Map<String, Integer> partial = AgentTestingWorkflow.parseScores("Accuracy: 5/10 - ok");
        assertEquals(1, partial.size(), "Should extract only the criteria present");
        assertEquals(5, partial.get("Accuracy"));
    }

    // =========================================================================
    // Test 6: Metrics collector lifecycle
    // =========================================================================

    @Test
    @DisplayName("WorkflowMetricsCollector start/stop lifecycle completes without exceptions")
    void testMetricsCollectorLifecycle() {
        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("test-workflow");

        assertDoesNotThrow(metrics::start, "start() should not throw");
        assertDoesNotThrow(metrics::stop, "stop() should not throw");

        Map<String, Object> collected = metrics.collect();
        assertNotNull(collected);
        assertEquals("test-workflow", collected.get("workflowName"));
        assertNotNull(collected.get("executionTimeMs"), "Should have execution time");
        assertEquals(0, collected.get("totalToolCalls"), "No tool calls expected");
    }
}
