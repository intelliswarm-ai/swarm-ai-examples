/*
 * SwarmAI Framework
 * Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 *
 * Licensed under the MIT License. See LICENSE file for details.
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
import ai.intelliswarm.swarmai.examples.enterprise.EnterpriseSelfImprovingWorkflow;
import ai.intelliswarm.swarmai.examples.competitive.CompetitiveResearchSwarm;
import ai.intelliswarm.swarmai.examples.investment.InvestmentAnalysisSwarm;
import ai.intelliswarm.swarmai.examples.pentest.DistributedPentestWorkflow;
import ai.intelliswarm.swarmai.examples.auditedresearch.AuditedResearchWorkflow;
import ai.intelliswarm.swarmai.examples.governedpipeline.GovernedPipelineWorkflow;
import ai.intelliswarm.swarmai.examples.secureops.SecureOpsWorkflow;
import ai.intelliswarm.swarmai.examples.rag.RAGResearchWorkflow;
import ai.intelliswarm.swarmai.examples.basics.BareMinimumExample;
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
import ai.intelliswarm.swarmai.examples.deeprl.DeepRLWorkflow;
import ai.intelliswarm.swarmai.examples.deeprl.DeepRLBenchmark;
import ai.intelliswarm.swarmai.examples.enterprise.GovernedEnterpriseWorkflow;
import ai.intelliswarm.swarmai.examples.distributed.DistributedGoalWorkflow;
import ai.intelliswarm.swarmai.examples.distributed.DistributedIntelligenceWorkflow;
import ai.intelliswarm.swarmai.examples.mapreduce.MapReduceWorkflow;
import ai.intelliswarm.swarmai.examples.vulnpatcher.VulnPatcherWorkflow;
import ai.intelliswarm.swarmai.examples.yamldsl.YamlDslWorkflow;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class SwarmAIWorkflowRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SwarmAIWorkflowRunner.class);

    /** Workflows that require external data sources (web search, SEC filings) to produce meaningful results. */
    private static final Set<String> REQUIRES_EXTERNAL_DATA = Set.of(
            "stock-analysis", "competitive-analysis", "due-diligence",
            "iterative-memo", "web-research", "self-improving",
            "enterprise-governed", "competitive-swarm", "investment-swarm",
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
    private final EnterpriseSelfImprovingWorkflow enterpriseSelfImprovingWorkflow;
    private final DistributedPentestWorkflow distributedPentestWorkflow;
    private final CompetitiveResearchSwarm competitiveResearchSwarm;
    private final InvestmentAnalysisSwarm investmentAnalysisSwarm;
    private final AuditedResearchWorkflow auditedResearchWorkflow;
    private final GovernedPipelineWorkflow governedPipelineWorkflow;
    private final SecureOpsWorkflow secureOpsWorkflow;
    private final RAGResearchWorkflow ragResearchWorkflow;
    private final BareMinimumExample bareMinimumExample;
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
    private final DeepRLWorkflow deepRLWorkflow;
    private final DeepRLBenchmark deepRLBenchmark;
    private final GovernedEnterpriseWorkflow governedEnterpriseWorkflow;
    private final DistributedGoalWorkflow distributedGoalWorkflow;
    private final DistributedIntelligenceWorkflow distributedIntelligenceWorkflow;
    private final MapReduceWorkflow mapReduceWorkflow;
    private final VulnPatcherWorkflow vulnPatcherWorkflow;
    private final YamlDslWorkflow yamlDslWorkflow;

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
            EnterpriseSelfImprovingWorkflow enterpriseSelfImprovingWorkflow,
            DistributedPentestWorkflow distributedPentestWorkflow,
            CompetitiveResearchSwarm competitiveResearchSwarm,
            InvestmentAnalysisSwarm investmentAnalysisSwarm,
            AuditedResearchWorkflow auditedResearchWorkflow,
            GovernedPipelineWorkflow governedPipelineWorkflow,
            SecureOpsWorkflow secureOpsWorkflow,
            RAGResearchWorkflow ragResearchWorkflow,
            BareMinimumExample bareMinimumExample,
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
            DeepRLWorkflow deepRLWorkflow,
            DeepRLBenchmark deepRLBenchmark,
            GovernedEnterpriseWorkflow governedEnterpriseWorkflow,
            DistributedGoalWorkflow distributedGoalWorkflow,
            DistributedIntelligenceWorkflow distributedIntelligenceWorkflow,
            MapReduceWorkflow mapReduceWorkflow,
            VulnPatcherWorkflow vulnPatcherWorkflow,
            YamlDslWorkflow yamlDslWorkflow,
            WebSearchTool webSearchTool) {
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
        this.enterpriseSelfImprovingWorkflow = enterpriseSelfImprovingWorkflow;
        this.distributedPentestWorkflow = distributedPentestWorkflow;
        this.competitiveResearchSwarm = competitiveResearchSwarm;
        this.investmentAnalysisSwarm = investmentAnalysisSwarm;
        this.auditedResearchWorkflow = auditedResearchWorkflow;
        this.governedPipelineWorkflow = governedPipelineWorkflow;
        this.secureOpsWorkflow = secureOpsWorkflow;
        this.ragResearchWorkflow = ragResearchWorkflow;
        this.bareMinimumExample = bareMinimumExample;
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
        this.deepRLWorkflow = deepRLWorkflow;
        this.deepRLBenchmark = deepRLBenchmark;
        this.governedEnterpriseWorkflow = governedEnterpriseWorkflow;
        this.distributedGoalWorkflow = distributedGoalWorkflow;
        this.distributedIntelligenceWorkflow = distributedIntelligenceWorkflow;
        this.mapReduceWorkflow = mapReduceWorkflow;
        this.vulnPatcherWorkflow = vulnPatcherWorkflow;
        this.yamlDslWorkflow = yamlDslWorkflow;
        this.webSearchTool = webSearchTool;
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
            case "enterprise-governed":
                governedEnterpriseWorkflow.run(workflowArgs);
                break;
            case "enterprise-self-improving":
                enterpriseSelfImprovingWorkflow.run(workflowArgs);
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
            case "bare-minimum":
                bareMinimumExample.run(workflowArgs);
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
            case "deep-rl":
                String rlTopic = workflowArgs.length > 0 ? workflowArgs[0] : "AI agent frameworks";
                int rlRuns = workflowArgs.length > 1 ? Integer.parseInt(workflowArgs[1]) : 5;
                deepRLWorkflow.run(rlTopic, rlRuns);
                break;
            case "deep-rl-benchmark":
                int numTopics = workflowArgs.length > 0 ? Integer.parseInt(workflowArgs[0]) : 10;
                int maxIter = workflowArgs.length > 1 ? Integer.parseInt(workflowArgs[1]) : 3;
                deepRLBenchmark.run(numTopics, maxIter);
                break;
            case "distributed-goal":
                distributedGoalWorkflow.run(workflowArgs);
                break;
            case "distributed-intelligence":
                distributedIntelligenceWorkflow.run(workflowArgs);
                break;
            case "map-reduce":
                mapReduceWorkflow.run(workflowArgs);
                break;
            case "vuln-patcher":
                String repoUrl = workflowArgs.length > 0 ? workflowArgs[0] : "https://github.com/example/vulnerable-app";
                vulnPatcherWorkflow.run(repoUrl);
                break;
            case "yaml-dsl":
                String yamlTopic = workflowArgs.length > 0 ? workflowArgs[0] : "AI Safety";
                yamlDslWorkflow.run(yamlTopic);
                break;
            case "yaml-dsl-inline":
                String inlineTopic = workflowArgs.length > 0 ? workflowArgs[0] : "AI Safety";
                yamlDslWorkflow.runInline(inlineTopic);
                break;
            case "yaml-dsl-composite":
                String compositeTopic = workflowArgs.length > 0 ? workflowArgs[0] : "AI Safety";
                yamlDslWorkflow.runComposite(compositeTopic);
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

    private void showUsage() {
        System.out.println("SwarmAI Framework - Multi-Agent Workflow System");
        System.out.println("===============================================");
        System.out.println();
        System.out.println("Usage: java -jar swarmai-framework.jar <workflow-type> [options]");
        System.out.println();
        System.out.println("Basic examples (start here):");
        System.out.println("  bare-minimum [TOPIC]         - Simplest possible: 1 agent, 1 task, no tools");
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
        System.out.println("Distributed processing:");
        System.out.println("  distributed-goal <TOPIC> [N] - RAFT-based multi-node goal execution (N nodes, default 3)");
        System.out.println("  distributed-intelligence <T> - Distributed intelligence sharing: skills, rules, insights via RAFT");
        System.out.println("  map-reduce <TOPIC>           - Map-reduce: 4 parallel analysts → 1 reducer (PARALLEL process)");
        System.out.println();
        System.out.println("Enterprise workflows:");
        System.out.println("  enterprise-governed <QUERY>  - Multi-tenancy + budget + governance + SPI extensions");
        System.out.println("  enterprise-self-improving <Q> - Self-improving + tenancy + budget + governance gates");
        System.out.println("  vuln-patcher <REPO_URL>      - Security vulnerability scanner + auto-patcher (SELF_IMPROVING)");
        System.out.println();
        System.out.println("Composite examples (combining multiple framework features):");
        System.out.println("  audited-research <QUERY>     - Multi-turn + tool hooks + permissions + decision tracing");
        System.out.println("  governed-pipeline <QUERY>    - Composite process + checkpoints + budget + Mermaid diagram");
        System.out.println("  secure-ops <QUERY>           - Tiered permissions, compliance hooks, full observability");
        System.out.println();
        System.out.println("Deep RL (neural network policy learning):");
        System.out.println("  deep-rl [TOPIC] [RUNS]       - DQN-powered self-improving workflow (default: 5 runs)");
        System.out.println("  deep-rl-benchmark [N] [ITER] - Production benchmark: N topics (default 10), ITER iterations each");
        System.out.println();
        System.out.println("YAML DSL (declarative workflows):");
        System.out.println("  yaml-dsl [TOPIC]             - Load and run a YAML workflow definition");
        System.out.println("  yaml-dsl-inline [TOPIC]      - Run an inline YAML workflow (no file)");
        System.out.println("  yaml-dsl-composite [TOPIC]   - Run a composite multi-stage YAML pipeline");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar swarmai-framework.jar bare-minimum");
        System.out.println("  java -jar swarmai-framework.jar tool-calling \"What is 15% of 2340?\"");
        System.out.println("  java -jar swarmai-framework.jar agent-handoff \"quantum computing\"");
        System.out.println("  java -jar swarmai-framework.jar customer-support \"I was charged twice\"");
        System.out.println("  java -jar swarmai-framework.jar evaluator-optimizer \"Write about microservices\"");
        System.out.println("  java -jar swarmai-framework.jar rag-research \"AI agent framework differences\"");
        System.out.println("  java -jar swarmai-framework.jar stock-analysis TSLA");
        System.out.println("  java -jar swarmai-framework.jar competitive-analysis \"AI trends 2026\"");
        System.out.println("  java -jar swarmai-framework.jar governed-pipeline \"AI infrastructure market 2026\"");
        System.out.println("  java -jar swarmai-framework.jar deep-rl \"AI agents\" 10");
        System.out.println("  java -jar swarmai-framework.jar deep-rl-benchmark 50 3");
        System.out.println();
    }
}
