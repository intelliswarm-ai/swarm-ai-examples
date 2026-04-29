package ai.intelliswarm.swarmai.examples.mcpserver;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * A trivial BaseTool that returns the current time in a caller-specified timezone.
 *
 * <p>Used by the {@code mcp-server-host} example to demonstrate that any
 * {@link BaseTool} bean is automatically published as an MCP tool when
 * {@code swarmai.mcp.server.enabled=true}. Picked deliberately because it has
 * no external API dependencies and runs anywhere.
 */
@Component
public class CurrentTimeTool implements BaseTool {

    @Override
    public String getFunctionName() {
        return "current_time";
    }

    @Override
    public String getDescription() {
        return "Returns the current time. Optional 'zone' parameter accepts an IANA "
                + "zone id like 'Europe/Athens', 'America/New_York', or 'UTC'. "
                + "Defaults to UTC.";
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Object rawZone = parameters != null ? parameters.get("zone") : null;
        String zoneStr = rawZone != null ? rawZone.toString() : "UTC";
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneStr);
        } catch (Exception e) {
            return "Invalid zone '" + zoneStr + "'. Use an IANA id like 'UTC' or 'Europe/Athens'.";
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        return now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "zone", Map.of(
                                "type", "string",
                                "description", "IANA zone id (e.g. 'UTC', 'Europe/Athens'). Defaults to UTC.")),
                "required", List.of()
        );
    }

    @Override
    public String getCategory() {
        return "computation";
    }

    @Override
    public List<String> getTags() {
        return List.of("time", "utility", "no-network");
    }

    /**
     * Schema for Spring AI's tool-calling layer. Mirrors the JSON schema returned
     * by {@link #getParameterSchema()} but as a Java record so {@code @Description}
     * + parameter naming flow into the OpenAI tool descriptor automatically.
     */
    public record Request(
            @com.fasterxml.jackson.annotation.JsonProperty(required = false)
            String zone) {}
}
