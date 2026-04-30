package ai.intelliswarm.swarmai.examples.gmail;

import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Bridges {@link BrowserTool} into Spring AI's tool-calling layer.
 *
 * <p>An Agent that lists {@code .tool(browserTool)} can advertise the tool to the LLM,
 * but when the LLM emits a {@code tool_calls} array Spring AI dispatches by bean name —
 * the bean MUST be named exactly {@code "browser"} to match
 * {@link BrowserTool#getFunctionName()}. Without this bridge the LLM advertises the
 * tool but the framework can't dispatch the resulting call ("No ToolCallback found
 * for tool name: browser").
 *
 * <p>Gated by {@link ConditionalOnBean} so the bridge only registers when the
 * BrowserTool itself is on the classpath and enabled — otherwise we'd publish a
 * tool callback for a tool that doesn't exist.
 */
@Configuration
@ConditionalOnBean(BrowserTool.class)
public class BrowserToolFunctionConfig {

    @Bean
    @Description("Real-Chromium browser automation. Navigate, click, type, fill forms, "
            + "scrape, screenshot, and evaluate JavaScript on web pages — including pages "
            + "that need JavaScript to render. Page state PERSISTS across calls so multi-step "
            + "flows work (e.g. log in, then navigate, then scrape).")
    public Function<BrowserTool.Request, String> browser(BrowserTool tool) {
        return req -> tool.execute(req.toMap()).toString();
    }
}
