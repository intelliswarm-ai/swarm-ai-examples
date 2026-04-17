/*
 * SwarmAI Framework
 * Copyright (c) 2025 IntelliSwarm.ai (Apache License 2.0)
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai;

import ai.intelliswarm.swarmai.examples.duediligence.DueDiligenceWorkflow;
import ai.intelliswarm.swarmai.examples.iterative.IterativeInvestmentMemoWorkflow;
import ai.intelliswarm.swarmai.examples.mcpresearch.McpResearchWorkflow;
import ai.intelliswarm.swarmai.examples.research.CompetitiveAnalysisWorkflow;
import ai.intelliswarm.swarmai.examples.stock.StockAnalysisWorkflow;
import ai.intelliswarm.swarmai.examples.codebase.CodebaseAnalysisWorkflow;
import ai.intelliswarm.swarmai.examples.webresearch.WebResearchWorkflow;
import ai.intelliswarm.swarmai.examples.datapipeline.DataPipelineWorkflow;
import ai.intelliswarm.swarmai.examples.selfimproving.SelfImprovingWorkflow;
import ai.intelliswarm.swarmai.examples.selfevolving.SelfEvolvingSwarmWorkflow;
import ai.intelliswarm.swarmai.examples.competitive.CompetitiveResearchSwarm;
import ai.intelliswarm.swarmai.examples.investment.InvestmentAnalysisSwarm;
import ai.intelliswarm.swarmai.examples.pentest.DistributedPentestWorkflow;
import ai.intelliswarm.swarmai.examples.auditedresearch.AuditedResearchWorkflow;
import ai.intelliswarm.swarmai.examples.governedpipeline.GovernedPipelineWorkflow;
import ai.intelliswarm.swarmai.examples.secureops.SecureOpsWorkflow;
import ai.intelliswarm.swarmai.examples.rag.RAGResearchWorkflow;
import ai.intelliswarm.swarmai.examples.basics.ToolCallingExample;
import ai.intelliswarm.swarmai.examples.basics.AgentHandoffExample;
import ai.intelliswarm.swarmai.examples.basics.ContextVariablesExample;
import ai.intelliswarm.swarmai.examples.basics.MultiTurnExample;
import ai.intelliswarm.swarmai.examples.streaming.StreamingWorkflow;
import ai.intelliswarm.swarmai.examples.customersupport.CustomerSupportWorkflow;
import ai.intelliswarm.swarmai.examples.errorhandling.ErrorHandlingWorkflow;
import ai.intelliswarm.swarmai.examples.memorypersistence.ConversationMemoryWorkflow;
import ai.intelliswarm.swarmai.examples.humanloop.HumanInTheLoopWorkflow;
import ai.intelliswarm.swarmai.examples.multiprovider.MultiProviderWorkflow;
import ai.intelliswarm.swarmai.examples.evaluator.EvaluatorOptimizerWorkflow;
import ai.intelliswarm.swarmai.examples.agenttesting.AgentTestingWorkflow;
import ai.intelliswarm.swarmai.examples.agentchat.AgentDebateWorkflow;
import ai.intelliswarm.swarmai.examples.multilanguage.MultiLanguageWorkflow;
import ai.intelliswarm.swarmai.examples.scheduled.ScheduledMonitoringWorkflow;
import ai.intelliswarm.swarmai.examples.visualization.WorkflowVisualizationExample;
import ai.intelliswarm.swarmai.judge.ImprovementAggregator;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SwarmAIWorkflowRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SwarmAIWorkflowRunner.class);

    /** Workflows that require external data sources (web search, SEC filings) to produce meaningful results. */
    private static final Set<String> REQUIRES_EXTERNAL_DATA = Set.of(
            "stock-analysis", "competitive-analysis", "due-diligence",
            "iterative-memo", "web-research", "self-improving",
            "competitive-swarm", "investment-swarm",
            "audited-research", "governed-pipeline"
    );

    @Value("${swarmai.studio-keep-alive:true}")
    private boolean studioKeepAlive;

    private final ApplicationContext applicationContext;
    private final WebSearchTool webSearchTool;

    private final CompetitiveAnalysisWorkflow competitiveAnalysisWorkflow;
    private final StockAnalysisWorkflow stockAnalysisWorkflow;
    private final DueDiligenceWorkflow dueDiligenceWorkflow;
    private final McpResearchWorkflow mcpResearchWorkflow;
    private final IterativeInvestmentMemoWorkflow iterativeInvestmentMemoWorkflow;
    private final CodebaseAnalysisWorkflow codebaseAnalysisWorkflow;
    private final WebResearchWorkflow webResearchWorkflow;
    private final DataPipelineWorkflow dataPipelineWorkflow;
    private final SelfImprovingWorkflow selfImprovingWorkflow;
    private final SelfEvolvingSwarmWorkflow selfEvolvingSwarmWorkflow;
    private final DistributedPentestWorkflow distributedPentestWorkflow;
    private final CompetitiveResearchSwarm competitiveResearchSwarm;
    private final InvestmentAnalysisSwarm investmentAnalysisSwarm;
    private final AuditedResearchWorkflow auditedResearchWorkflow;
    private final GovernedPipelineWorkflow governedPipelineWorkflow;
    private final SecureOpsWorkflow secureOpsWorkflow;
    private final RAGResearchWorkflow ragResearchWorkflow;
    private final ToolCallingExample toolCallingExample;
    private final AgentHandoffExample agentHandoffExample;
    private final ContextVariablesExample contextVariablesExample;
    private final MultiTurnExample multiTurnExample;
    private final StreamingWorkflow streamingWorkflow;
    private final CustomerSupportWorkflow customerSupportWorkflow;
    private final ErrorHandlingWorkflow errorHandlingWorkflow;
    private final ConversationMemoryWorkflow conversationMemoryWorkflow;
    private final HumanInTheLoopWorkflow humanInTheLoopWorkflow;
    private final MultiProviderWorkflow multiProviderWorkflow;
    private final EvaluatorOptimizerWorkflow evaluatorOptimizerWorkflow;
    private final AgentTestingWorkflow agentTestingWorkflow;
    private final AgentDebateWorkflow agentDebateWorkflow;
    private final MultiLanguageWorkflow multiLanguageWorkflow;
    private final ScheduledMonitoringWorkflow scheduledMonitoringWorkflow;
    private final WorkflowVisualizationExample workflowVisualizationExample;
    private final LLMJudge judge;
    private final ImprovementAggregator aggregator;

    public SwarmAIWorkflowRunner(
            ApplicationContext applicationContext,
            CompetitiveAnalysisWorkflow competitiveAnalysisWorkflow,
            StockAnalysisWorkflow stockAnalysisWorkflow,
            DueDiligenceWorkflow dueDiligenceWorkflow,
            McpResearchWorkflow mcpResearchWorkflow,
            IterativeInvestmentMemoWorkflow iterativeInvestmentMemoWorkflow,
            CodebaseAnalysisWorkflow codebaseAnalysisWorkflow,
            WebResearchWorkflow webResearchWorkflow,
            DataPipelineWorkflow dataPipelineWorkflow,
            SelfImprovingWorkflow selfImprovingWorkflow,
            SelfEvolvingSwarmWorkflow selfEvolvingSwarmWorkflow,
            DistributedPentestWorkflow distributedPentestWorkflow,
            CompetitiveResearchSwarm competitiveResearchSwarm,
            InvestmentAnalysisSwarm investmentAnalysisSwarm,
            AuditedResearchWorkflow auditedResearchWorkflow,
            GovernedPipelineWorkflow governedPipelineWorkflow,
            SecureOpsWorkflow secureOpsWorkflow,
            RAGResearchWorkflow ragResearchWorkflow,
            ToolCallingExample toolCallingExample,
            AgentHandoffExample agentHandoffExample,
            ContextVariablesExample contextVariablesExample,
            MultiTurnExample multiTurnExample,
            StreamingWorkflow streamingWorkflow,
            CustomerSupportWorkflow customerSupportWorkflow,
            ErrorHandlingWorkflow errorHandlingWorkflow,
            ConversationMemoryWorkflow conversationMemoryWorkflow,
            HumanInTheLoopWorkflow humanInTheLoopWorkflow,
            MultiProviderWorkflow multiProviderWorkflow,
            EvaluatorOptimizerWorkflow evaluatorOptimizerWorkflow,
            AgentTestingWorkflow agentTestingWorkflow,
            AgentDebateWorkflow agentDebateWorkflow,
            MultiLanguageWorkflow multiLanguageWorkflow,
            ScheduledMonitoringWorkflow scheduledMonitoringWorkflow,
            WorkflowVisualizationExample workflowVisualizationExample,
            WebSearchTool webSearchTool,
            LLMJudge judge,
            ImprovementAggregator aggregator) {
        this.applicationContext = applicationContext;
        this.competitiveAnalysisWorkflow = competitiveAnalysisWorkflow;
        this.stockAnalysisWorkflow = stockAnalysisWorkflow;
        this.dueDiligenceWorkflow = dueDiligenceWorkflow;
        this.mcpResearchWorkflow = mcpResearchWorkflow;
        this.iterativeInvestmentMemoWorkflow = iterativeInvestmentMemoWorkflow;
        this.codebaseAnalysisWorkflow = codebaseAnalysisWorkflow;
        this.webResearchWorkflow = webResearchWorkflow;
        this.dataPipelineWorkflow = dataPipelineWorkflow;
        this.selfImprovingWorkflow = selfImprovingWorkflow;
        this.selfEvolvingSwarmWorkflow = selfEvolvingSwarmWorkflow;
        this.distributedPentestWorkflow = distributedPentestWorkflow;
        this.competitiveResearchSwarm = competitiveResearchSwarm;
        this.investmentAnalysisSwarm = investmentAnalysisSwarm;
        this.auditedResearchWorkflow = auditedResearchWorkflow;
        this.governedPipelineWorkflow = governedPipelineWorkflow;
        this.secureOpsWorkflow = secureOpsWorkflow;
        this.ragResearchWorkflow = ragResearchWorkflow;
        this.toolCallingExample = toolCallingExample;
        this.agentHandoffExample = agentHandoffExample;
        this.contextVariablesExample = contextVariablesExample;
        this.multiTurnExample = multiTurnExample;
        this.streamingWorkflow = streamingWorkflow;
        this.customerSupportWorkflow = customerSupportWorkflow;
        this.errorHandlingWorkflow = errorHandlingWorkflow;
        this.conversationMemoryWorkflow = conversationMemoryWorkflow;
        this.humanInTheLoopWorkflow = humanInTheLoopWorkflow;
        this.multiProviderWorkflow = multiProviderWorkflow;
        this.evaluatorOptimizerWorkflow = evaluatorOptimizerWorkflow;
        this.agentTestingWorkflow = agentTestingWorkflow;
        this.agentDebateWorkflow = agentDebateWorkflow;
        this.multiLanguageWorkflow = multiLanguageWorkflow;
        this.scheduledMonitoringWorkflow = scheduledMonitoringWorkflow;
        this.workflowVisualizationExample = workflowVisualizationExample;
        this.webSearchTool = webSearchTool;
        this.judge = judge;
        this.aggregator = aggregator;
    }

    @Override
    public void run(String... args) throws Exception {
        // Filter out Spring Boot args (--spring.*, --logging.*, etc.)
        java.util.List<String> filteredArgs = new java.util.ArrayList<>();
        for (String arg : args) {
            if (!arg.startsWith("--spring.") && !arg.startsWith("--logging.") && !arg.startsWith("--swarmai.")) {
                filteredArgs.add(arg);
            }
        }

        if (filteredArgs.isEmpty()) {
            showUsage();
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
            return;
        }

        String workflowType = filteredArgs.get(0).toLowerCase();
        String[] workflowArgs = filteredArgs.subList(1, filteredArgs.size()).toArray(new String[0]);

        if (REQUIRES_EXTERNAL_DATA.contains(workflowType)) {
            var health = ToolHealthChecker.checkAll(List.of(webSearchTool));
            var webSearchHealth = health.get(webSearchTool.getFunctionName());
            if (webSearchHealth != null && !webSearchHealth.healthy()) {
                logger.error("");
                logger.error("==========================================================");
                logger.error("  CANNOT RUN: {} requires external data sources", workflowType);
                logger.error("  web_search tool is not operational: {}", webSearchHealth.issues());
                logger.error("");
                logger.error("  Configure at least one search API key:");
                logger.error("    export ALPHA_VANTAGE_API_KEY=<your-key>");
                logger.error("  or run a workflow that doesn't need external data:");
                logger.error("    codebase-analysis, data-pipeline, pentest-swarm");
                logger.error("==========================================================");
                logger.error("");
                System.exit(1);
            }
        }

        switch (workflowType) {
            case "competitive-analysis":
                competitiveAnalysisWorkflow.run(workflowArgs);
                break;
            case "stock-analysis":
                stockAnalysisWorkflow.run(workflowArgs);
                break;
            case "due-diligence":
                dueDiligenceWorkflow.run(workflowArgs);
                break;
            case "mcp-research":
                mcpResearchWorkflow.run(workflowArgs);
                break;
            case "iterative-memo":
                iterativeInvestmentMemoWorkflow.run(workflowArgs);
                break;
            case "codebase-analysis":
                codebaseAnalysisWorkflow.run(workflowArgs);
                break;
            case "web-research":
                webResearchWorkflow.run(workflowArgs);
                break;
            case "data-pipeline":
                dataPipelineWorkflow.run(workflowArgs);
                break;
            case "self-improving":
                selfImprovingWorkflow.run(workflowArgs);
                break;
            case "self-evolving":
                selfEvolvingSwarmWorkflow.run(workflowArgs);
                break;
            case "pentest-swarm":
                distributedPentestWorkflow.run(workflowArgs);
                break;
            case "competitive-swarm":
                competitiveResearchSwarm.run(workflowArgs);
                break;
            case "investment-swarm":
                investmentAnalysisSwarm.run(workflowArgs);
                break;
            case "audited-research":
                auditedResearchWorkflow.run(workflowArgs);
                break;
            case "governed-pipeline":
                governedPipelineWorkflow.run(workflowArgs);
                break;
            case "secure-ops":
                secureOpsWorkflow.run(workflowArgs);
                break;
            case "rag-research":
                ragResearchWorkflow.run(workflowArgs);
                break;
            case "tool-calling":
                toolCallingExample.run(workflowArgs);
                break;
            case "agent-handoff":
                agentHandoffExample.run(workflowArgs);
                break;
            case "context-variables":
                contextVariablesExample.run(workflowArgs);
                break;
            case "multi-turn":
                multiTurnExample.run(workflowArgs);
                break;
            case "streaming":
                streamingWorkflow.run(workflowArgs);
                break;
            case "customer-support":
                customerSupportWorkflow.run(workflowArgs);
                break;
            case "error-handling":
                errorHandlingWorkflow.run(workflowArgs);
                break;
            case "memory":
                conversationMemoryWorkflow.run(workflowArgs);
                break;
            case "human-loop":
                humanInTheLoopWorkflow.run(workflowArgs);
                break;
            case "multi-provider":
                multiProviderWorkflow.run(workflowArgs);
                break;
            case "evaluator-optimizer":
                evaluatorOptimizerWorkflow.run(workflowArgs);
                break;
            case "agent-testing":
                agentTestingWorkflow.run(workflowArgs);
                break;
            case "agent-debate":
                agentDebateWorkflow.run(workflowArgs);
                break;
            case "multi-language":
                multiLanguageWorkflow.run(workflowArgs);
                break;
            case "scheduled":
                scheduledMonitoringWorkflow.run(workflowArgs);
                break;
            case "visualization":
                workflowVisualizationExample.run(workflowArgs);
                break;
            case "judge-all":
                runAllWithJudge();
                break;
            case "customer-support-app":
                // This is a persistent REST API service — keep the server alive
                logger.info("");
                logger.info("==========================================================");
                logger.info("  Customer Support API is running!");
                logger.info("  Base URL: http://localhost:8080/api/support");
                logger.info("  Web UI:   http://localhost:8080");
                logger.info("");
                logger.info("  Try:");
                logger.info("    curl -X POST http://localhost:8080/api/support/chat \\");
                logger.info("      -H 'Content-Type: application/json' \\");
                logger.info("      -d '{\"message\": \"I need help with billing\"}'");
                logger.info("");
                logger.info("  Press Ctrl+C to stop.");
                logger.info("==========================================================");
                logger.info("");
                Thread.currentThread().join();
                return; // skip the exit logic below
            case "rag-app":
                // RAG Knowledge Base — persistent REST API service
                logger.info("");
                logger.info("==========================================================");
                logger.info("  RAG Knowledge Base is running!");
                logger.info("  API:    http://localhost:8080/api/rag");
                logger.info("  Web UI: http://localhost:8080/rag.html");
                logger.info("");
                logger.info("  Quick start:");
                logger.info("    1. Open http://localhost:8080/rag.html");
                logger.info("    2. Click 'Pre-load Sample Data' to ingest demo documents");
                logger.info("    3. Ask: 'What orchestration patterns does SwarmAI support?'");
                logger.info("");
                logger.info("  Press Ctrl+C to stop.");
                logger.info("==========================================================");
                logger.info("");
                Thread.currentThread().join();
                return; // skip the exit logic below
            default:
                System.err.println("Unknown workflow type: " + workflowType);
                showUsage();
                System.exit(1);
        }

        // When Studio keep-alive is enabled, keep the server alive so users can inspect results
        if (studioKeepAlive) {
            logger.info("");
            logger.info("==========================================================");
            logger.info("  SwarmAI Studio is running at: http://localhost:8080/studio");
            logger.info("  Workflow complete. Inspect results in the Studio UI.");
            logger.info("  Press Ctrl+C to stop the server.");
            logger.info("==========================================================");
            logger.info("");
            // Block the CommandLineRunner thread — the web server stays alive on its own threads
            Thread.currentThread().join();
        } else {
            // No Studio — shut down the Spring context and exit the JVM cleanly.
            // Without this, the embedded web server (Tomcat) keeps the JVM alive indefinitely.
            logger.info("Workflow complete. Shutting down.");
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }

    /**
     * Runs all batch-compatible workflows sequentially with LLM judge evaluation.
     * Skips interactive workflows (human-loop), persistent services (REST APIs),
     * and workflows requiring special infrastructure.
     */
    private void runAllWithJudge() {
        // judge-all is the one entry point where judging is intentional, so auto-enable here
        // (casual per-example runs keep the judge off to avoid surprise API costs).
        judge.getConfig().setEnabled(true);
        if (!judge.isAvailable()) {
            logger.error("LLM Judge is not available. Set OPENAI_API_KEY (or ANTHROPIC_API_KEY) " +
                    "in your .env or environment, then re-run `judge-all`.");
            System.exit(1);
        }

        // Workflows to run in order: name -> description (for logging)
        Map<String, Runnable> workflows = new LinkedHashMap<>();
        workflows.put("tool-calling", () -> tryRun("tool-calling", () -> toolCallingExample.run()));
        workflows.put("agent-handoff", () -> tryRun("agent-handoff", () -> agentHandoffExample.run()));
        workflows.put("context-variables", () -> tryRun("context-variables", () -> contextVariablesExample.run()));
        workflows.put("multi-turn", () -> tryRun("multi-turn", () -> multiTurnExample.run()));
        workflows.put("streaming", () -> tryRun("streaming", () -> streamingWorkflow.run()));
        workflows.put("customer-support", () -> tryRun("customer-support", () -> customerSupportWorkflow.run()));
        workflows.put("error-handling", () -> tryRun("error-handling", () -> errorHandlingWorkflow.run()));
        workflows.put("memory", () -> tryRun("memory", () -> conversationMemoryWorkflow.run()));
        workflows.put("evaluator-optimizer", () -> tryRun("evaluator-optimizer", () -> evaluatorOptimizerWorkflow.run()));
        workflows.put("agent-testing", () -> tryRun("agent-testing", () -> agentTestingWorkflow.run()));
        workflows.put("agent-debate", () -> tryRun("agent-debate", () -> agentDebateWorkflow.run()));
        workflows.put("multi-language", () -> tryRun("multi-language", () -> multiLanguageWorkflow.run()));
        workflows.put("visualization", () -> tryRun("visualization", () -> workflowVisualizationExample.run()));
        workflows.put("rag-research", () -> tryRun("rag-research", () -> ragResearchWorkflow.run()));
        workflows.put("codebase-analysis", () -> tryRun("codebase-analysis", () -> codebaseAnalysisWorkflow.run()));
        workflows.put("data-pipeline", () -> tryRun("data-pipeline", () -> dataPipelineWorkflow.run()));
        workflows.put("stock-analysis", () -> tryRun("stock-analysis", () -> stockAnalysisWorkflow.run()));
        workflows.put("competitive-analysis", () -> tryRun("competitive-analysis", () -> competitiveAnalysisWorkflow.run()));
        workflows.put("due-diligence", () -> tryRun("due-diligence", () -> dueDiligenceWorkflow.run()));
        workflows.put("audited-research", () -> tryRun("audited-research", () -> auditedResearchWorkflow.run()));
        workflows.put("governed-pipeline", () -> tryRun("governed-pipeline", () -> governedPipelineWorkflow.run()));
        workflows.put("secure-ops", () -> tryRun("secure-ops", () -> secureOpsWorkflow.run()));
        workflows.put("self-improving", () -> tryRun("self-improving", () -> selfImprovingWorkflow.run()));
        // pentest-swarm, investment-swarm excluded from default judge-all batch —
        // they consistently score poorly (Run 8: 45 and 30) because they depend on specialized
        // infrastructure (live network targets, live financial APIs) that the regression harness
        // doesn't provide. Run them manually when the infrastructure is in place.
        workflows.put("competitive-swarm", () -> tryRun("competitive-swarm", () -> competitiveResearchSwarm.run()));

        logger.info("\n" + "=".repeat(80));
        logger.info("JUDGE-ALL: Running {} workflows with LLM evaluation", workflows.size());
        logger.info("Judge: {} ({})", judge.isAvailable() ? "ENABLED" : "DISABLED", "OpenAI/Anthropic");
        logger.info("=".repeat(80));

        int passed = 0, failed = 0, total = workflows.size();
        for (Map.Entry<String, Runnable> entry : workflows.entrySet()) {
            String name = entry.getKey();
            logger.info("\n>>> [{}/{}] Running: {}", passed + failed + 1, total, name);
            try {
                entry.getValue().run();
                passed++;
                logger.info("<<< {} DONE", name);
            } catch (Exception e) {
                failed++;
                logger.error("<<< {} FAILED: {}", name, e.getMessage());
            }
        }

        logger.info("\n" + "=".repeat(80));
        logger.info("JUDGE-ALL COMPLETE: {}/{} passed, {}/{} failed", passed, total, failed, total);
        logger.info("Results saved in each example's judge-results/ directory");
        logger.info("=".repeat(80));

        // Phase 2: Auto-aggregate findings into a submittable improvements file
        try {
            logger.info("\n" + "=".repeat(80));
            logger.info("AGGREGATING improvements for submission to intelliswarm.ai");
            logger.info("=".repeat(80));

            boolean autoSubmit = Boolean.parseBoolean(
                    System.getenv().getOrDefault("SWARMAI_AUTO_SUBMIT", "false"));
            java.nio.file.Path examplesRoot = java.nio.file.Paths.get(
                    System.getProperty("user.dir"));

            java.nio.file.Path output = aggregator.aggregate(examplesRoot, autoSubmit);
            if (output != null) {
                logger.info("Improvements file: {}", output);
                if (!autoSubmit) {
                    logger.info("To auto-submit on next run, set: SWARMAI_AUTO_SUBMIT=true");
                }
            }
        } catch (Exception e) {
            logger.error("Aggregation failed: {}", e.getMessage(), e);
        }
    }

    private void tryRun(String name, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            throw new RuntimeException("Workflow " + name + " failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void showUsage() {
        System.out.println("SwarmAI Framework - Multi-Agent Workflow System");
        System.out.println("===============================================");
        System.out.println();
        System.out.println("Usage: java -jar swarmai-framework.jar <workflow-type> [options]");
        System.out.println();
        System.out.println("Basic examples (start here):");
        System.out.println("  tool-calling [PROBLEM]       - Single agent with a CalculatorTool");
        System.out.println("  agent-handoff [TOPIC]        - Two agents: researcher output feeds into editor");
        System.out.println("  context-variables [TOPIC]    - Three agents sharing context via inputs map");
        System.out.println("  multi-turn [TOPIC]           - Single agent with maxTurns=5 and auto-compaction");
        System.out.println();
        System.out.println("Feature examples:");
        System.out.println("  streaming [TOPIC]            - Reactive multi-turn output with progress hooks");
        System.out.println("  rag-research <QUERY>         - RAG: knowledge retrieval + evidence-grounded report");
        System.out.println("  customer-support [QUERY]     - Routing + handoff with SwarmGraph conditional edges");
        System.out.println("  error-handling               - Tool failure recovery, budget enforcement, timeouts");
        System.out.println("  memory [TOPIC]               - Shared memory persistence across agents and tasks");
        System.out.println("  human-loop [TOPIC]           - Approval gates, checkpoints, revision loops");
        System.out.println("  multi-provider [TOPIC]       - Same task across temperatures and model variants");
        System.out.println("  evaluator-optimizer [TOPIC]  - Generate, evaluate, optimize loop with quality gate");
        System.out.println("  agent-testing [TOPIC]        - Evaluate agent output quality with scored report card");
        System.out.println("  agent-debate [PROPOSITION]   - Two agents debate a topic, a judge declares the winner");
        System.out.println("  scheduled [TOPIC]            - Recurring monitoring agent with trend detection");
        System.out.println("  multi-language [TOPIC]       - Parallel multilingual analysis + cross-cultural synthesis");
        System.out.println("  visualization                - Build 4 graph topologies, generate Mermaid diagrams");
        System.out.println();
        System.out.println("Applications (persistent services):");
        System.out.println("  customer-support-app         - REST API: chat, products, orders, tickets (runs on :8080)");
        System.out.println("  rag-app                      - RAG knowledge base: ingest docs, semantic search, multi-agent Q&A");
        System.out.println();
        System.out.println("Production workflows:");
        System.out.println("  stock-analysis <TICKER>      - Financial stock analysis (default: AAPL)");
        System.out.println("  competitive-analysis <QUERY> - Multi-agent research on any topic");
        System.out.println("  due-diligence <TICKER>       - Comprehensive company due diligence");
        System.out.println("  mcp-research <QUERY>         - Research using MCP tools (web fetch/search)");
        System.out.println("  iterative-memo <TICKER> [N]  - Iterative investment memo with review loop");
        System.out.println("  codebase-analysis [PATH]     - Analyze codebase architecture and dependencies");
        System.out.println("  web-research <QUERY>         - Deep web research with scraping and fact-checking");
        System.out.println("  data-pipeline [FILE]         - AI-powered data profiling and insights");
        System.out.println("  self-improving <QUERY>       - Generates new tools at runtime");
        System.out.println("  pentest-swarm <QUERY>        - Distributed pentest with parallel agents");
        System.out.println("  competitive-swarm <QUERY>    - Parallel company analysis with shared skills");
        System.out.println("  investment-swarm <QUERY>     - Multi-company investment analysis");
        System.out.println();
        System.out.println("Composite examples (combining multiple framework features):");
        System.out.println("  audited-research <QUERY>     - Multi-turn + tool hooks + permissions + decision tracing");
        System.out.println("  governed-pipeline <QUERY>    - Composite process + checkpoints + budget + Mermaid diagram");
        System.out.println("  secure-ops <QUERY>           - Tiered permissions, compliance hooks, full observability");
        System.out.println();
        System.out.println("LLM Judge evaluation:");
        System.out.println("  judge-all                    - Run ALL examples with LLM-as-Judge evaluation");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar swarmai-framework.jar tool-calling \"What is 15% of 2340?\"");
        System.out.println("  java -jar swarmai-framework.jar agent-handoff \"quantum computing\"");
        System.out.println("  java -jar swarmai-framework.jar customer-support \"I was charged twice\"");
        System.out.println("  java -jar swarmai-framework.jar evaluator-optimizer \"Write about microservices\"");
        System.out.println("  java -jar swarmai-framework.jar rag-research \"AI agent framework differences\"");
        System.out.println("  java -jar swarmai-framework.jar stock-analysis TSLA");
        System.out.println("  java -jar swarmai-framework.jar competitive-analysis \"AI trends 2026\"");
        System.out.println("  java -jar swarmai-framework.jar governed-pipeline \"AI infrastructure market 2026\"");
        System.out.println();
    }
}
