package ai.intelliswarm.swarmai.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures the LLM-as-Judge evaluation of a workflow's FRAMEWORK performance.
 *
 * Scores focus on framework orchestration quality, not underlying LLM text quality.
 * Results are date-stamped so multiple runs can be compared over time to track
 * framework improvement or degradation across releases.
 */
public class JudgeResult {

    private String workflowName;
    private String judgeModel;
    private String workflowModel;  // The LLM used by the workflow (e.g., "mistral:latest")
    private String timestamp;
    private String runDate;

    // Framework-centric scores (0-100)
    private int overallScore;
    private int orchestration;
    private int taskDecomposition;
    private int toolIntegration;
    private int errorHandling;
    private int observability;
    private int architecturePattern;

    // Output assessment scores (0-100)
    private int outputQuality;
    private int goalAchievement;
    private int frameworkValueAdd;
    private int outputImprovementPotential;
    private String baselineComparison;

    // Qualitative
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> frameworkIssues;
    private List<String> exampleIssues;
    private List<String> improvements;
    private String verdict;

    // Workflow metadata
    private long executionTimeMs;
    private int agentCount;
    private int taskCount;
    private String processType;
    private boolean successful;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public JudgeResult() {
        this.timestamp = Instant.now().toString();
    }

    /** Save with date stamp for longitudinal tracking. */
    public void save(File outputDir, String dateStamp) throws IOException {
        outputDir.mkdirs();
        this.runDate = dateStamp;
        File file = new File(outputDir, workflowName + "_judge_result_" + dateStamp + ".json");
        MAPPER.writeValue(file, toMap());
    }

    /** Save without date stamp (backwards compatibility). */
    public void save(File outputDir) throws IOException {
        save(outputDir, java.time.LocalDate.now().toString());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workflowName", workflowName);
        map.put("judgeModel", judgeModel);
        map.put("workflowModel", workflowModel);
        map.put("timestamp", timestamp);
        map.put("runDate", runDate);
        map.put("successful", successful);

        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("overall", overallScore);
        scores.put("orchestration", orchestration);
        scores.put("taskDecomposition", taskDecomposition);
        scores.put("toolIntegration", toolIntegration);
        scores.put("errorHandling", errorHandling);
        scores.put("observability", observability);
        scores.put("architecturePattern", architecturePattern);
        map.put("frameworkScores", scores);

        Map<String, Object> outputScores = new LinkedHashMap<>();
        outputScores.put("outputQuality", outputQuality);
        outputScores.put("goalAchievement", goalAchievement);
        outputScores.put("frameworkValueAdd", frameworkValueAdd);
        outputScores.put("outputImprovementPotential", outputImprovementPotential);
        map.put("outputScores", outputScores);
        map.put("baselineComparison", baselineComparison);

        map.put("verdict", verdict);
        map.put("strengths", strengths);
        map.put("weaknesses", weaknesses);
        map.put("frameworkIssues", frameworkIssues);
        map.put("exampleIssues", exampleIssues);
        map.put("improvements", improvements);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("executionTimeMs", executionTimeMs);
        meta.put("agentCount", agentCount);
        meta.put("taskCount", taskCount);
        meta.put("processType", processType);
        map.put("workflowMetadata", meta);

        return map;
    }

    // --- Getters and Setters ---

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }

    public String getJudgeModel() { return judgeModel; }
    public void setJudgeModel(String judgeModel) { this.judgeModel = judgeModel; }

    public String getWorkflowModel() { return workflowModel; }
    public void setWorkflowModel(String workflowModel) { this.workflowModel = workflowModel; }

    public String getTimestamp() { return timestamp; }
    public String getRunDate() { return runDate; }

    public int getOverallScore() { return overallScore; }
    public void setOverallScore(int overallScore) { this.overallScore = overallScore; }

    public int getOrchestration() { return orchestration; }
    public void setOrchestration(int orchestration) { this.orchestration = orchestration; }

    public int getTaskDecomposition() { return taskDecomposition; }
    public void setTaskDecomposition(int taskDecomposition) { this.taskDecomposition = taskDecomposition; }

    public int getToolIntegration() { return toolIntegration; }
    public void setToolIntegration(int toolIntegration) { this.toolIntegration = toolIntegration; }

    public int getErrorHandling() { return errorHandling; }
    public void setErrorHandling(int errorHandling) { this.errorHandling = errorHandling; }

    public int getObservability() { return observability; }
    public void setObservability(int observability) { this.observability = observability; }

    public int getArchitecturePattern() { return architecturePattern; }
    public void setArchitecturePattern(int architecturePattern) { this.architecturePattern = architecturePattern; }

    public int getOutputQuality() { return outputQuality; }
    public void setOutputQuality(int outputQuality) { this.outputQuality = outputQuality; }

    public int getGoalAchievement() { return goalAchievement; }
    public void setGoalAchievement(int goalAchievement) { this.goalAchievement = goalAchievement; }

    public int getFrameworkValueAdd() { return frameworkValueAdd; }
    public void setFrameworkValueAdd(int frameworkValueAdd) { this.frameworkValueAdd = frameworkValueAdd; }

    public int getOutputImprovementPotential() { return outputImprovementPotential; }
    public void setOutputImprovementPotential(int outputImprovementPotential) { this.outputImprovementPotential = outputImprovementPotential; }

    public String getBaselineComparison() { return baselineComparison; }
    public void setBaselineComparison(String baselineComparison) { this.baselineComparison = baselineComparison; }

    public List<String> getStrengths() { return strengths; }
    public void setStrengths(List<String> strengths) { this.strengths = strengths; }

    public List<String> getWeaknesses() { return weaknesses; }
    public void setWeaknesses(List<String> weaknesses) { this.weaknesses = weaknesses; }

    public List<String> getFrameworkIssues() { return frameworkIssues; }
    public void setFrameworkIssues(List<String> frameworkIssues) { this.frameworkIssues = frameworkIssues; }

    public List<String> getExampleIssues() { return exampleIssues; }
    public void setExampleIssues(List<String> exampleIssues) { this.exampleIssues = exampleIssues; }

    public List<String> getImprovements() { return improvements; }
    public void setImprovements(List<String> improvements) { this.improvements = improvements; }

    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public int getAgentCount() { return agentCount; }
    public void setAgentCount(int agentCount) { this.agentCount = agentCount; }

    public int getTaskCount() { return taskCount; }
    public void setTaskCount(int taskCount) { this.taskCount = taskCount; }

    public String getProcessType() { return processType; }
    public void setProcessType(String processType) { this.processType = processType; }

    public boolean isSuccessful() { return successful; }
    public void setSuccessful(boolean successful) { this.successful = successful; }
}
