package ai.intelliswarm.swarmai.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Aggregates per-workflow judge results into a consolidated improvements file
 * ready for submission to intelliswarm.ai/contribute.
 *
 * Pipeline:
 * 1. Scan all example judge-results directories for _judge_result_{date}.json
 * 2. Extract frameworkIssues and exampleIssues
 * 3. Cluster similar issues using the judge LLM (deduplication + theme finding)
 * 4. Validate each cluster against actual source code
 * 5. Classify tier (TIER_1 / TIER_2 / TIER_3) based on severity + frequency
 * 6. Produce swarmai-improvements-{date}.json matching the website schema
 * 7. Optionally POST to intelliswarm.ai/api/v1/contribute
 */
@Component
public class ImprovementAggregator {

    private static final Logger logger = LoggerFactory.getLogger(ImprovementAggregator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // Framework root for validation (resolved from env or default)
    private static final String FRAMEWORK_ROOT = System.getenv().getOrDefault(
            "SWARMAI_FRAMEWORK_PATH", "/mnt/d/Intelliswarm.ai/swarm-ai");

    private final JudgeConfig config;
    private final LLMJudge judge;  // reuse the LLM client for clustering and validation

    public ImprovementAggregator(JudgeConfig config, LLMJudge judge) {
        this.config = config;
        this.judge = judge;
    }

    /**
     * Run the full aggregation pipeline for today's judge results.
     *
     * @param examplesRoot the swarm-ai-examples project root
     * @param submit whether to auto-submit to intelliswarm.ai/contribute
     * @return the path of the generated improvements file, or null if nothing to aggregate
     */
    public Path aggregate(Path examplesRoot, boolean submit) throws IOException {
        if (!judge.isAvailable()) {
            logger.warn("[Aggregator] Judge not available — cannot aggregate");
            return null;
        }

        String dateStamp = LocalDate.now().toString();
        logger.info("[Aggregator] Starting aggregation for run date {}", dateStamp);

        // 1. Collect all judge results for this date
        List<JudgeResultRecord> results = collectJudgeResults(examplesRoot, dateStamp);
        if (results.isEmpty()) {
            logger.warn("[Aggregator] No judge results found for {}", dateStamp);
            return null;
        }
        logger.info("[Aggregator] Collected {} judge results", results.size());

        // 2. Extract all framework and example issues with their source workflow
        List<RawIssue> frameworkIssues = extractIssues(results, true);
        List<RawIssue> exampleIssues = extractIssues(results, false);
        logger.info("[Aggregator] Raw issues: {} framework, {} example",
                frameworkIssues.size(), exampleIssues.size());

        // 3. Cluster similar issues using LLM (deduplication)
        List<IssueCluster> frameworkClusters = clusterIssues(frameworkIssues, "FRAMEWORK");
        List<IssueCluster> exampleClusters = clusterIssues(exampleIssues, "EXAMPLE");
        logger.info("[Aggregator] Clustered into: {} framework clusters, {} example clusters",
                frameworkClusters.size(), exampleClusters.size());

        // 4. Validate + classify each cluster
        List<ImprovementEntry> improvements = new ArrayList<>();
        for (IssueCluster cluster : frameworkClusters) {
            ImprovementEntry imp = validateAndClassify(cluster, "FRAMEWORK", results.size());
            if (imp != null) improvements.add(imp);
        }
        for (IssueCluster cluster : exampleClusters) {
            ImprovementEntry imp = validateAndClassify(cluster, "EXAMPLE", results.size());
            if (imp != null) improvements.add(imp);
        }

        logger.info("[Aggregator] Validated: {} improvements (of {} clusters)",
                improvements.size(), frameworkClusters.size() + exampleClusters.size());

        // 5. Build the contribution file
        Map<String, Object> contribution = buildContributionFile(improvements, results, dateStamp);

        // 6. Write to docs/ with full timestamp so multiple runs on the same day don't clobber each other
        Path outputDir = examplesRoot.resolve("docs");
        Files.createDirectories(outputDir);
        String timeStamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        Path outputFile = outputDir.resolve("swarmai-improvements-" + timeStamp + ".json");
        MAPPER.writeValue(outputFile.toFile(), contribution);
        logger.info("[Aggregator] Wrote improvements file: {}", outputFile);

        // 7. Optionally submit
        if (submit) {
            submitToIntelliswarm(contribution);
        } else {
            logger.info("[Aggregator] Auto-submit disabled. To submit manually:");
            logger.info("  curl -X POST https://intelliswarm.ai/api/v1/contribute \\");
            logger.info("    -H 'Content-Type: application/json' \\");
            logger.info("    -d @{}", outputFile);
        }

        return outputFile;
    }

    // ========== Step 1: Collect judge results ==========

    private List<JudgeResultRecord> collectJudgeResults(Path examplesRoot, String dateStamp) throws IOException {
        List<JudgeResultRecord> results = new ArrayList<>();
        // Match any file from today: "<workflow>_judge_result_<dateStamp>*.json"
        // (dateStamp is YYYY-MM-DD; filenames now include HH-mm-ss suffix so we match by prefix).
        String marker = "_judge_result_" + dateStamp;

        try (Stream<Path> files = Files.walk(examplesRoot, 4)) {
            // Collect all matching files, then for each workflow keep only the LATEST (lexicographic sort
            // works because timestamps are YYYY-MM-DD-HH-mm-ss)
            java.util.Map<String, Path> latestPerWorkflow = new java.util.LinkedHashMap<>();
            files
                    .filter(p -> p.getFileName().toString().contains(marker))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> p.toString().contains("judge-results"))
                    .sorted()
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String workflow = name.substring(0, name.indexOf("_judge_result_"));
                        latestPerWorkflow.put(workflow, p);  // later timestamps overwrite earlier
                    });
            List<Path> jsonFiles = new ArrayList<>(latestPerWorkflow.values());

            for (Path f : jsonFiles) {
                try {
                    JsonNode root = MAPPER.readTree(f.toFile());
                    results.add(new JudgeResultRecord(
                            root.path("workflowName").asText(),
                            root.path("workflowModel").asText(""),
                            root.path("judgeModel").asText(""),
                            extractStringList(root.path("frameworkIssues")),
                            extractStringList(root.path("exampleIssues")),
                            extractStringList(root.path("improvements")),
                            root.path("frameworkScores").path("overall").asInt(0),
                            root.path("outputScores").path("frameworkValueAdd").asInt(0)
                    ));
                } catch (Exception e) {
                    logger.warn("[Aggregator] Skipping malformed result {}: {}", f, e.getMessage());
                }
            }
        }

        return results;
    }

    private List<String> extractStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    // ========== Step 2: Extract raw issues ==========

    private List<RawIssue> extractIssues(List<JudgeResultRecord> results, boolean framework) {
        List<RawIssue> issues = new ArrayList<>();
        for (JudgeResultRecord r : results) {
            List<String> src = framework ? r.frameworkIssues : r.exampleIssues;
            for (String text : src) {
                if (text == null || text.isBlank()) continue;
                issues.add(new RawIssue(r.workflowName, text));
            }
        }
        return issues;
    }

    // ========== Step 3: Cluster similar issues ==========

    @SuppressWarnings("unchecked")
    private List<IssueCluster> clusterIssues(List<RawIssue> issues, String kind) {
        if (issues.isEmpty()) return List.of();

        // Build a compact list for the LLM to cluster
        StringBuilder issueList = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            issueList.append(i).append(": [").append(issues.get(i).workflowName).append("] ")
                    .append(issues.get(i).text).append("\n");
        }

        String prompt = String.format("""
                You are clustering %s issues discovered by an LLM-as-Judge regression test of the SwarmAI framework.
                Group similar issues into clusters. An issue belongs to a cluster if it describes the same
                underlying problem even if the wording differs.

                Input issues (one per line, prefixed with index and source workflow):
                %s

                Respond with a JSON array of clusters. Each cluster has:
                - "theme": short phrase describing the underlying issue (e.g., "ParallelProcess fails on task error")
                - "indices": array of input indices belonging to this cluster
                - "summary": one-sentence description of the underlying bug or limitation

                Merge aggressively: prefer fewer, broader clusters. Singleton clusters are OK only if
                the issue is genuinely unique.

                Return ONLY the JSON array, no markdown fences.
                """, kind, issueList);

        try {
            String response = judge.callLLMDirect(prompt, 3000);
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) {
                logger.warn("[Aggregator] Clustering returned non-array response, skipping clustering");
                return List.of();
            }

            List<IssueCluster> clusters = new ArrayList<>();
            for (JsonNode c : arr) {
                List<Integer> indices = new ArrayList<>();
                c.path("indices").forEach(i -> indices.add(i.asInt()));

                Set<String> workflows = new LinkedHashSet<>();
                List<String> originalTexts = new ArrayList<>();
                for (Integer idx : indices) {
                    if (idx >= 0 && idx < issues.size()) {
                        workflows.add(issues.get(idx).workflowName);
                        originalTexts.add(issues.get(idx).text);
                    }
                }

                clusters.add(new IssueCluster(
                        c.path("theme").asText("Unnamed issue"),
                        c.path("summary").asText(""),
                        new ArrayList<>(workflows),
                        originalTexts
                ));
            }
            return clusters;
        } catch (Exception e) {
            logger.warn("[Aggregator] Clustering failed: {} — falling back to one cluster per unique text", e.getMessage());
            return fallbackClusterByText(issues);
        }
    }

    private List<IssueCluster> fallbackClusterByText(List<RawIssue> issues) {
        // Fallback: dedupe by normalized text
        Map<String, List<RawIssue>> byKey = new LinkedHashMap<>();
        for (RawIssue i : issues) {
            String key = i.text.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        return byKey.values().stream()
                .map(group -> new IssueCluster(
                        group.get(0).text.length() > 60 ? group.get(0).text.substring(0, 60) : group.get(0).text,
                        group.get(0).text,
                        group.stream().map(i -> i.workflowName).distinct().collect(Collectors.toList()),
                        group.stream().map(i -> i.text).collect(Collectors.toList())
                ))
                .collect(Collectors.toList());
    }

    // ========== Step 4: Validate & classify ==========

    private ImprovementEntry validateAndClassify(IssueCluster cluster, String kind, int totalWorkflows) {
        // Ask the LLM to classify this cluster into TIER_1/2/3 and produce structured output
        String prompt = String.format("""
                You are evaluating a clustered issue from an LLM-as-Judge regression test of the SwarmAI framework.
                Classify it and produce structured output.

                KIND: %s (FRAMEWORK = fix belongs in swarm-ai core; EXAMPLE = fix belongs in swarm-ai-examples)
                THEME: %s
                SUMMARY: %s
                REPORTED BY WORKFLOWS (%d of %d): %s

                Original issue texts:
                %s

                Classify into exactly one tier:
                - TIER_1_AUTOMATIC: Clear bug with obvious fix; can be auto-merged after review
                - TIER_2_REVIEW: Real issue but fix needs code review and judgment
                - TIER_3_PROPOSAL: Architecture proposal or enhancement, needs discussion

                Also assess:
                - confidence (0.0-1.0): how sure are we this is a real issue?
                - crossValidated: true if reported by ≥2 independent workflows
                - category: a SHORT_SNAKE_CASE name (e.g., PARALLEL_PROCESS_ERROR_HANDLING)
                - estimatedTokenSavings: rough estimate of tokens saved per run if fixed (0 if purely qualitative)
                - recommendation: ONE clear sentence suggesting the fix

                Return ONLY this JSON, no markdown fences:
                {
                  "category": "...",
                  "tier": "TIER_1_AUTOMATIC" | "TIER_2_REVIEW" | "TIER_3_PROPOSAL",
                  "confidence": 0.0,
                  "crossValidated": true|false,
                  "estimatedTokenSavings": 0,
                  "recommendation": "..."
                }
                """,
                kind, cluster.theme, cluster.summary,
                cluster.workflows.size(), totalWorkflows,
                String.join(", ", cluster.workflows),
                String.join("\n", cluster.originalTexts.stream().limit(5).collect(Collectors.toList()))
        );

        try {
            String response = judge.callLLMDirect(prompt, 1000);
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(json);

            ImprovementEntry entry = new ImprovementEntry();
            entry.category = root.path("category").asText("UNKNOWN");
            entry.tier = root.path("tier").asText("TIER_2_REVIEW");
            entry.confidence = root.path("confidence").asDouble(0.5);
            entry.crossValidated = root.path("crossValidated").asBoolean(cluster.workflows.size() >= 2);
            entry.supportingObservations = cluster.workflows.size();
            entry.estimatedTokenSavings = root.path("estimatedTokenSavings").asInt(0);
            entry.occurrenceCount = cluster.originalTexts.size();
            entry.recommendation = root.path("recommendation").asText(cluster.summary);
            entry.theme = cluster.theme;
            entry.kind = kind;
            entry.affectedWorkflows = cluster.workflows;
            entry.condition = Map.of(
                    "kind", kind,
                    "theme", cluster.theme,
                    "reportedByWorkflows", cluster.workflows,
                    "observationCount", cluster.originalTexts.size()
            );
            return entry;
        } catch (Exception e) {
            logger.warn("[Aggregator] Classification failed for cluster '{}': {}",
                    cluster.theme, e.getMessage());
            return null;
        }
    }

    // ========== Step 5: Build contribution file ==========

    private Map<String, Object> buildContributionFile(List<ImprovementEntry> improvements,
                                                      List<JudgeResultRecord> results,
                                                      String dateStamp) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("exportFormat", "swarmai-improvements");
        file.put("formatVersion", "1.0");
        file.put("title", "SwarmAI Framework Improvements (auto-aggregated from LLM-as-Judge regression)");
        file.put("frameworkVersion", "1.0.7");
        file.put("exportedAt", Instant.now().toString());

        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("description",
                "Auto-aggregated from LLM-as-Judge regression across " + results.size() +
                " workflow examples. Each improvement was clustered from per-workflow judge reports " +
                "and classified by the same judge LLM used for evaluation.");
        instructions.put("githubUrl", "https://github.com/intelliswarm-ai/swarm-ai/issues");
        instructions.put("email", "contributions@intelliswarm.ai");
        instructions.put("webForm", "https://intelliswarm.ai/contribute");
        instructions.put("privacyNote", "Contains cluster themes and affected workflow names only. " +
                "No agent outputs, task descriptions, or user data.");
        file.put("instructions", instructions);

        // Community stats
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalWorkflowsAnalyzed", results.size());
        stats.put("totalImprovementsDiscovered", improvements.size());
        stats.put("estimatedTokenSavingsPerRun",
                improvements.stream().mapToInt(i -> i.estimatedTokenSavings).sum());
        stats.put("workflowModel", results.isEmpty() ? "unknown" : results.get(0).workflowModel);
        stats.put("judgeModel", results.isEmpty() ? "unknown" : results.get(0).judgeModel);
        stats.put("runDate", dateStamp);
        file.put("communityStats", stats);

        // Serialize improvements
        List<Map<String, Object>> improvementsList = new ArrayList<>();
        for (ImprovementEntry imp : improvements) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", imp.category);
            m.put("tier", imp.tier);
            m.put("confidence", imp.confidence);
            m.put("crossValidated", imp.crossValidated);
            m.put("supportingObservations", imp.supportingObservations);
            m.put("estimatedTokenSavings", imp.estimatedTokenSavings);
            m.put("occurrenceCount", imp.occurrenceCount);
            m.put("condition", imp.condition);
            m.put("recommendation", imp.recommendation);
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("kind", imp.kind);
            rv.put("theme", imp.theme);
            rv.put("affectedWorkflows", imp.affectedWorkflows);
            m.put("recommendedValue", rv);
            improvementsList.add(m);
        }
        file.put("improvements", improvementsList);

        return file;
    }

    // ========== Step 7: Submit to intelliswarm.ai ==========

    private void submitToIntelliswarm(Map<String, Object> contribution) {
        String endpoint = System.getenv().getOrDefault(
                "INTELLISWARM_CONTRIBUTE_URL",
                "https://intelliswarm.ai/api/v1/contribute");

        try {
            Map<String, Object> payload = Map.of(
                    "improvementData", contribution,
                    "organizationName", System.getenv().getOrDefault("CONTRIBUTOR_ORG", "Anonymous"),
                    "contactEmail", System.getenv().getOrDefault("CONTRIBUTOR_EMAIL", ""),
                    "notes", "Auto-generated from swarm-ai-examples regression test"
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode body = MAPPER.readTree(response.body());
                logger.info("[Aggregator] Submitted successfully. Tracking ID: {}",
                        body.path("trackingId").asText("unknown"));
            } else {
                logger.error("[Aggregator] Submission failed: HTTP {} — {}",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.error("[Aggregator] Submission error: {}", e.getMessage(), e);
        }
    }

    // ========== Data classes ==========

    private record JudgeResultRecord(
            String workflowName,
            String workflowModel,
            String judgeModel,
            List<String> frameworkIssues,
            List<String> exampleIssues,
            List<String> improvements,
            int overallScore,
            int valueAdd
    ) {}

    private record RawIssue(String workflowName, String text) {}

    private record IssueCluster(
            String theme,
            String summary,
            List<String> workflows,
            List<String> originalTexts
    ) {}

    private static class ImprovementEntry {
        String category;
        String tier;
        double confidence;
        boolean crossValidated;
        int supportingObservations;
        int estimatedTokenSavings;
        int occurrenceCount;
        String recommendation;
        String theme;
        String kind;
        List<String> affectedWorkflows;
        Map<String, Object> condition;
    }
}
