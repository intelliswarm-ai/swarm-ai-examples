/* SwarmAI Framework - Copyright (c) 2025 IntelliSwarm.ai (Apache License 2.0) */
package ai.intelliswarm.swarmai.examples.streaming;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEventSse;
import ai.intelliswarm.swarmai.task.Task;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;

/**
 * HTTP/SSE entry point for the streaming demo.
 *
 * <p>Open <a href="http://localhost:8090/streaming.html">streaming.html</a>
 * to see tokens appear in a browser; the page connects to {@code /api/stream}
 * via {@code EventSource} and renders {@code text_delta} events live.
 *
 * <p>Or {@code curl} it directly:
 * <pre>{@code
 *   curl -N "http://localhost:8090/api/stream?topic=a%20robot%20discovering%20emotions"
 * }</pre>
 *
 * <p>The {@code -N} flag (no buffering) is critical for curl to flush the SSE
 * stream as it arrives instead of waiting for EOF.
 *
 * <p>Activated only when the streaming web profile is on so the CLI mode of
 * this example doesn't accidentally bring up Tomcat. Enable with:
 * <pre>{@code
 *   --swarmai.examples.streaming.web=true \
 *   --spring.main.web-application-type=servlet \
 *   --server.port=8090
 * }</pre>
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "swarmai.examples.streaming.web", havingValue = "true")
public class StreamingHttpController {

    private final ChatClient.Builder chatClientBuilder;

    public StreamingHttpController(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    /**
     * SSE endpoint. Builds a one-shot story-writing agent, runs
     * {@link Agent#executeTaskStreaming(Task, java.util.List)} on it, and
     * forwards every event to the HTTP client via {@link AgentEventSse}.
     *
     * <p>The browser receives named events:
     * <pre>{@code
     *   event: agent_started     data: {"agentId":"...","role":"Story Writer",...}
     *   event: text_delta        data: {"agentId":"...","text":"Hello",...}
     *   event: text_delta        data: {"agentId":"...","text":" world",...}
     *   event: agent_finished    data: {"agentId":"...","taskOutput":{...},...}
     * }</pre>
     */
    @GetMapping(path = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestParam(defaultValue = "a robot discovering emotions") String topic) {
        ChatClient chatClient = chatClientBuilder.build();

        Agent storyWriter = Agent.builder()
                .role("Story Writer")
                .goal("Write a vivid 200-word short story on the requested topic.")
                .backstory("You are a tight-prose short-fiction author who writes a complete "
                        + "story in one pass — no preamble or commentary.")
                .chatClient(chatClient)
                .verbose(false)
                .temperature(0.7)
                .maxExecutionTime(300_000)
                .build();

        Task storyTask = Task.builder()
                .description("Write a 200-word story about: " + topic
                        + "\n\nBegin with the first sentence of prose — no title, no preamble.")
                .expectedOutput("A short story in prose, ~200 words.")
                .agent(storyWriter)
                .build();

        // Bridge once — AgentEventSse handles backpressure, client-disconnect
        // teardown, and event-name routing for the EventSource API.
        return AgentEventSse.toSseEmitter(
                storyWriter.executeTaskStreaming(storyTask, Collections.emptyList()));
    }
}
