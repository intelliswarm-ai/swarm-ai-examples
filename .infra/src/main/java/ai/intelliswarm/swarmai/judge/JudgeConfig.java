package ai.intelliswarm.swarmai.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the LLM-as-Judge evaluation system.
 * Bound from swarmai.judge.* in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "swarmai.judge")
public class JudgeConfig {

    private boolean enabled = true;
    private String provider = "openai";    // "openai" or "anthropic"
    private String model = "gpt-4o";
    private String openaiApiKey = "";
    private String anthropicApiKey = "";
    private String workflowModel = "mistral:latest";  // LLM used by workflows
    private String outputDir = "judge-results";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getOpenaiApiKey() { return openaiApiKey; }
    public void setOpenaiApiKey(String openaiApiKey) { this.openaiApiKey = openaiApiKey; }

    public String getAnthropicApiKey() { return anthropicApiKey; }
    public void setAnthropicApiKey(String anthropicApiKey) { this.anthropicApiKey = anthropicApiKey; }

    public String getWorkflowModel() { return workflowModel; }
    public void setWorkflowModel(String workflowModel) { this.workflowModel = workflowModel; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public boolean isAvailable() {
        if (!enabled) return false;
        if ("openai".equalsIgnoreCase(provider)) {
            return openaiApiKey != null && !openaiApiKey.isBlank();
        }
        if ("anthropic".equalsIgnoreCase(provider)) {
            return anthropicApiKey != null && !anthropicApiKey.isBlank();
        }
        return false;
    }
}
