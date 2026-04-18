package ai.intelliswarm.examples.demorecorder;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Activate with {@code swarmai.demo.record=true} (or env {@code SWARMAI_DEMO_RECORD=true}).
 * When inactive, the recorder bean is not registered and has zero overhead.
 */
@ConfigurationProperties(prefix = "swarmai.demo")
public class DemoRecorderProperties {

    private boolean record = false;
    private String slug;
    private String model;
    private String modelDisplayName;
    private String provider;
    private String outDir = "demos";
    private String frameworkVersion;
    private String frameworkGitSha;
    private Double temperature = 0.0;
    private Long seed = 42L;
    private Double topP = 1.0;
    private Integer maxTokens = 2048;

    public boolean isRecord() { return record; }
    public void setRecord(boolean record) { this.record = record; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getModelDisplayName() { return modelDisplayName; }
    public void setModelDisplayName(String modelDisplayName) { this.modelDisplayName = modelDisplayName; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getOutDir() { return outDir; }
    public void setOutDir(String outDir) { this.outDir = outDir; }
    public String getFrameworkVersion() { return frameworkVersion; }
    public void setFrameworkVersion(String frameworkVersion) { this.frameworkVersion = frameworkVersion; }
    public String getFrameworkGitSha() { return frameworkGitSha; }
    public void setFrameworkGitSha(String frameworkGitSha) { this.frameworkGitSha = frameworkGitSha; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
}
