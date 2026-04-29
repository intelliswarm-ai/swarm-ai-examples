package ai.intelliswarm.swarmai.examples.mcpserver;

import ai.intelliswarm.swarmai.agent.Agent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.Map;
import java.util.function.Function;

/**
 * Defines a sample {@link Agent} bean that the MCP server will publish.
 *
 * <p>Demonstrates the second granularity of MCP exposure: alongside individual
 * {@link ai.intelliswarm.swarmai.tool.base.BaseTool}s, an entire agent (its
 * persona + tools + LLM) becomes a single callable MCP tool. The MCP client
 * gives a {@code task} string and the agent figures out the steps.
 *
 * <p>Gated by {@code swarmai.examples.mcp-server.docs-assistant.enabled} so the
 * other 50+ examples in the repo don't have an extra @Bean Agent floating in
 * their context. Defaulted to {@code true} because the {@code mcp-server-host}
 * example is the only one that wires through to this config — flip false if
 * you want a tool-only catalog for testing.
 */
@Configuration
@ConditionalOnProperty(prefix = "swarmai.examples.mcp-server.docs-assistant",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocsAssistantConfig {

    @Bean
    public Agent docsAssistant(ChatClient.Builder chatClientBuilder, CurrentTimeTool currentTimeTool) {
        return Agent.builder()
                .role("Docs Assistant")
                .goal("Answer questions about the SwarmAI framework, including capabilities, examples, "
                        + "and how to wire common patterns. When the user asks a time-sensitive "
                        + "question, call current_time to ground the answer in actual now.")
                .backstory("You are a senior developer-relations engineer who has memorised the "
                        + "framework's docs. You give concise, accurate answers with code snippets "
                        + "where useful. You prefer short bullet lists over long prose.")
                .chatClient(chatClientBuilder.build())
                .tool(currentTimeTool)
                .build();
    }

    /**
     * Bridge {@link CurrentTimeTool} into Spring AI's tool-calling layer.
     *
     * <p>Spring AI looks up tool callbacks by bean name when the LLM emits a
     * {@code tool_calls} array — so this {@code @Bean} MUST be named exactly
     * {@code current_time} to match {@link CurrentTimeTool#getFunctionName()}.
     * Without this bridge, the LLM advertises the tool to OpenAI but the
     * framework can't dispatch the resulting call (you get
     * "No ToolCallback found for tool name: current_time").
     */
    @Bean
    @Description("Returns the current time in a specified IANA timezone (defaults to UTC).")
    public Function<CurrentTimeTool.Request, String> current_time(CurrentTimeTool tool) {
        return request -> {
            Map<String, Object> params = request.zone() != null
                    ? Map.of("zone", request.zone())
                    : Map.of();
            return tool.execute(params).toString();
        };
    }
}
