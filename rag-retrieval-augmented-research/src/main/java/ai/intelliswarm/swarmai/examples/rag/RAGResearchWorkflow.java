package ai.intelliswarm.swarmai.examples.rag;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.knowledge.InMemoryKnowledge;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) Research Workflow
 *
 * Demonstrates the Knowledge interface for grounding agent answers in a
 * curated document corpus rather than relying solely on the LLM's parametric
 * memory.  The RAG pattern implemented here has two stages:
 *
 *   1. RETRIEVE  A specialist agent queries an InMemoryKnowledge base using
 *                the Knowledge interface's query() and search() methods to
 *                find the most relevant passages for the user's question.
 *   2. GENERATE  A report-writing agent synthesizes the retrieved passages
 *                into a coherent, evidence-grounded report, citing the
 *                knowledge-base sources for every claim.
 *
 * Key framework features showcased:
 *   - Knowledge interface:          query(), search(), addSource()
 *   - InMemoryKnowledge:            simple substring-matching knowledge store
 *   - Agent.builder().knowledge():  binds a Knowledge instance to an agent
 *   - Swarm.builder().knowledge():  binds a Knowledge instance to the swarm
 *   - PermissionLevel.READ_ONLY:    retriever agent cannot mutate state
 *   - PermissionLevel.WORKSPACE_WRITE: writer agent can produce output files
 *   - SEQUENTIAL process:           retrieval must complete before synthesis
 *
 * Usage:
 *   java -jar swarmai-framework.jar rag-research "What are the key differences between AI agent frameworks?"
 */
@Component
public class RAGResearchWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(RAGResearchWorkflow.class);

    @org.springframework.beans.factory.annotation.Value("${swarmai.workflow.model:o3-mini}")
    private String workflowModel;

    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public RAGResearchWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        // Accept a custom query from the CLI, or fall back to a sensible default
        String query = args.length > 0
                ? String.join(" ", args)
                : "What are the key differences between AI agent frameworks?";

        logger.info("Starting RAG Research Workflow");
        logger.info("Query: {}", query);

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("rag-research");
        metrics.start();

        ChatClient chatClient = chatClientBuilder.build();

        // =================================================================
        // STEP 1 — Build the knowledge base
        //
        // In a production system this would be backed by a vector database
        // (Pinecone, Weaviate, pgvector, etc.).  Here we use the built-in
        // InMemoryKnowledge, which performs simple substring matching, and
        // populate it with curated paragraphs about AI agent frameworks.
        // =================================================================

        Knowledge knowledgeBase = buildKnowledgeBase();
        logger.info("Knowledge base populated with sample documents");


        // =================================================================
        // STEP 2 — Define agents
        //
        // Retriever:  has the Knowledge instance which provides query() and
        //             search() methods for retrieval.
        //             READ_ONLY — it must not modify state.
        //
        // Writer:     receives the retriever's output via task dependency.
        //             WORKSPACE_WRITE — it produces the final report file.
        // =================================================================

        Agent dataValidator = Agent.builder()
                .role("Retrieval Completeness Validator")
                .goal("After retrieval completes but before the writer synthesizes the answer, " +
                      "inspect the retrieved passages and assess whether the context is sufficient " +
                      "to answer the user's query. Categorize each aspect of the query as [OK], " +
                      "[MISSING], [PARTIAL], or [STALE] with respect to retrieved evidence. Produce " +
                      "a 'Data Completeness Report' the writer consults when marking evidence gaps.")
                .backstory("You are a rigorous evidence auditor. You read retrieved passages against " +
                          "the user's query and flag coverage gaps. You never let a writer proceed " +
                          "without knowing which sub-questions lack evidence. You recommend PROCEED, " +
                          "PROCEED-WITH-CAVEATS, or HALT based on retrieval completeness.")
                .chatClient(chatClient)
                .knowledge(knowledgeBase)
                .maxTurns(1)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .maxRpm(15)
                .temperature(0.1)
                .build();

        Agent retriever = Agent.builder()
                .role("Knowledge Retrieval Specialist")
                .goal("Search the knowledge base exhaustively for passages that are relevant " +
                      "to the user's query. Retrieve ALL matching passages, quote them " +
                      "verbatim, and record the source identifier for each one. " +
                      "Do NOT paraphrase or summarize at this stage — preserve the " +
                      "original text so the downstream writer can cite it accurately.")
                .backstory("You are an information retrieval expert with a decade of " +
                           "experience in knowledge management and library science. " +
                           "You know how to formulate multiple search queries to " +
                           "maximize recall — varying keywords, using synonyms, and " +
                           "breaking a complex question into sub-questions. You never " +
                           "stop after a single search; you keep probing until you are " +
                           "confident that no relevant passage has been missed.")
                .chatClient(chatClient)
                .knowledge(knowledgeBase)
                .maxTurns(3)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .maxRpm(15)
                .temperature(0.1)
                .build();

        Agent writer = Agent.builder()
                .role("Evidence-Based Report Writer")
                .goal("Synthesize the retrieved passages into a comprehensive, well-structured " +
                      "report that directly answers the user's query. Every factual claim " +
                      "MUST be grounded in a passage from the knowledge base and cite " +
                      "the source identifier in brackets, e.g. [source: architecture-patterns]. " +
                      "If the retrieved evidence is insufficient to answer part of the query, " +
                      "state that explicitly rather than speculating.")
                .backstory("You are a senior technical writer who specialises in producing " +
                           "evidence-based reports for engineering leadership. You never " +
                           "introduce information that is not backed by the provided evidence. " +
                           "You structure your reports with an executive summary, detailed " +
                           "findings sections, and a bibliography of sources used.")
                .chatClient(chatClient)
                .maxTurns(1)
                .permissionMode(PermissionLevel.WORKSPACE_WRITE)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .maxRpm(10)
                .temperature(0.3)
                .build();

        // =================================================================
        // STEP 3 — Define tasks
        //
        // The retrieval task runs first.  Its output (raw passages with
        // source IDs) is automatically passed to the report task via the
        // dependsOn relationship — this is the "augmented" part of RAG.
        // =================================================================

        Task retrievalTask = Task.builder()
                .description(String.format(
                        "Search the knowledge base for information about the following query:\n" +
                        "\"%s\"\n\n" +
                        "INSTRUCTIONS:\n" +
                        "1. Break the query into sub-topics and search for each one separately\n" +
                        "2. Use the semantic_search tool with varied keywords and phrases\n" +
                        "3. For every passage you find, record it verbatim along with its source ID\n" +
                        "4. Attempt at least 3 different search queries to maximise coverage\n" +
                        "5. De-duplicate overlapping passages but keep the most complete version\n\n" +
                        "Retrieve ALL relevant passages and cite your sources.", query))
                .expectedOutput("Numbered list of retrieved passages, each with:\n" +
                        "- Source identifier\n" +
                        "- Full verbatim text of the passage\n" +
                        "- Relevance note (why this passage relates to the query)")
                .agent(retriever)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();

        Task validationTask = Task.builder()
                .description(String.format(
                        "Inspect the passages retrieved for the query:\n\"%s\"\n\n" +
                        "CHECK whether the retrieved evidence is sufficient to answer the query. " +
                        "For each sub-topic implied by the query, categorize coverage as:\n" +
                        "- [OK]      — retrieved passages directly support an answer\n" +
                        "- [MISSING] — no retrieved passage covers this sub-topic\n" +
                        "- [PARTIAL] — some coverage but not enough for a confident answer\n" +
                        "- [STALE]   — passage exists but appears outdated\n\n" +
                        "PRODUCE a 'Data Completeness Report' that the writer consults when " +
                        "acknowledging limitations. End with a PROCEED / PROCEED-WITH-CAVEATS / HALT " +
                        "recommendation so the writer knows whether to answer confidently or flag " +
                        "insufficient evidence.",
                        query))
                .expectedOutput("Markdown 'Data Completeness Report' categorizing each query sub-topic " +
                               "as [OK]/[MISSING]/[PARTIAL]/[STALE] with a PROCEED/PROCEED-WITH-CAVEATS/" +
                               "HALT recommendation")
                .agent(dataValidator)
                .dependsOn(retrievalTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(90000)
                .build();

        Task reportTask = Task.builder()
                .description("Using the retrieved information from the knowledge base, write a " +
                        "comprehensive report that answers the original query.\n\n" +
                        "REQUIRED SECTIONS:\n" +
                        "1. Executive Summary (3-5 bullet points with key takeaways)\n" +
                        "2. Detailed Findings (organized by theme, every claim cites a source)\n" +
                        "3. Comparative Analysis (if applicable — tables or side-by-side comparisons)\n" +
                        "4. Gaps and Limitations (what the knowledge base did NOT cover)\n" +
                        "5. Sources Used (list all knowledge-base source IDs referenced)\n\n" +
                        "GROUNDING RULES:\n" +
                        "- Ground EVERY claim in evidence from the knowledge base\n" +
                        "- Use inline citations: [source: <id>]\n" +
                        "- If evidence is missing for a sub-topic, say so explicitly\n" +
                        "- Do NOT introduce external information not present in the retrieved passages")
                .expectedOutput("Professional research report in markdown with inline citations " +
                        "and a sources section")
                .agent(writer)
                .dependsOn(retrievalTask)
                .dependsOn(validationTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/rag_research_report.md")
                .maxExecutionTime(180000)
                .build();

        // =================================================================
        // STEP 4 — Assemble and run the swarm
        //
        // SEQUENTIAL process ensures retrieval completes before generation.
        // The knowledge base is also attached at the swarm level so that
        // any shared context or future agents can access it.
        // =================================================================

        Swarm swarm = Swarm.builder()
                .id("rag-research")
                .agent(retriever)
                .agent(dataValidator)
                .agent(writer)
                .task(retrievalTask)
                .task(validationTask)
                .task(reportTask)
                .knowledge(knowledgeBase)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .maxRpm(15)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        logger.info("=".repeat(80));
        logger.info("RAG RESEARCH WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Process: SEQUENTIAL (retrieve then generate)");
        logger.info("Knowledge base: {} (query/search via Knowledge interface)",
                knowledgeBase.getClass().getSimpleName());
        logger.info("=".repeat(80));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("query", query);

        long startTime = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(inputs);
        long duration = (System.currentTimeMillis() - startTime) / 1000;

        metrics.stop();

        // =================================================================
        // RESULTS
        // =================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("RAG RESEARCH WORKFLOW COMPLETED");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Duration: {} seconds", duration);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("\n{}", result.getTokenUsageSummary(workflowModel));
        logger.info("\nFinal Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("rag-research", "RAG knowledge retrieval with evidence-grounded report", result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startTime,
                3, 3, "SEQUENTIAL", "rag-retrieval-augmented-research");
        }

        metrics.report();
    }

    // =====================================================================
    // Knowledge Base Population
    //
    // Each document represents a curated passage about AI agent frameworks.
    // In production you would ingest PDFs, web pages, or database records
    // via a document loader and chunking pipeline.  Here we hard-code a
    // small corpus to keep the example self-contained.
    // =====================================================================

    private Knowledge buildKnowledgeBase() {
        InMemoryKnowledge kb = new InMemoryKnowledge();

        kb.addSource("architecture-patterns",
                "AI agent frameworks generally follow one of three architecture patterns: " +
                "single-agent loops, multi-agent orchestration, or graph-based workflows. " +
                "Single-agent loops (e.g., ReAct) give one LLM a set of tools and let it " +
                "reason in a think-act-observe cycle. Multi-agent orchestration frameworks " +
                "such as CrewAI and SwarmAI assign distinct roles to multiple agents and " +
                "coordinate them via sequential, hierarchical, or parallel processes. " +
                "Graph-based frameworks like LangGraph model the workflow as a state machine " +
                "where nodes are agents or functions and edges represent transitions.",
                Map.of("topic", "architecture", "category", "overview"));

        kb.addSource("tool-integration",
                "Tool integration is a critical differentiator among agent frameworks. " +
                "LangChain provides a large catalog of pre-built tool wrappers (search, " +
                "calculators, databases) but requires manual plumbing. AutoGen focuses on " +
                "code-execution tools and sandboxed runtime environments. CrewAI and SwarmAI " +
                "offer declarative tool registration with built-in permission levels " +
                "(READ_ONLY, WORKSPACE_WRITE, SYSTEM_ADMIN) and health checking via " +
                "ToolHealthChecker. Frameworks that support Model Context Protocol (MCP) " +
                "can dynamically discover and bind tools at runtime, reducing boilerplate.",
                Map.of("topic", "tools", "category", "comparison"));

        kb.addSource("orchestration-models",
                "Orchestration models define how agents collaborate. Sequential pipelines " +
                "pass output from one agent to the next — simple but inflexible. " +
                "Hierarchical models introduce a manager agent that delegates sub-tasks to " +
                "specialists and consolidates their outputs; this mirrors real-world team " +
                "structures and is well-suited for research workflows. Parallel fan-out " +
                "processes run independent agents concurrently and merge results, improving " +
                "throughput for tasks like competitive analysis where each company can be " +
                "analyzed simultaneously. SwarmAI supports all three via ProcessType enum.",
                Map.of("topic", "orchestration", "category", "comparison"));

        kb.addSource("memory-and-context",
                "Handling long contexts is an ongoing challenge. Most frameworks support " +
                "short-term memory (conversation history within a task) and long-term memory " +
                "(persisted across sessions). LangChain offers BufferMemory, SummaryMemory, " +
                "and VectorStoreMemory abstractions. CrewAI and SwarmAI use CompactionConfig " +
                "to automatically summarize conversation history when token counts exceed a " +
                "threshold, preserving the most recent turns in full while compacting older " +
                "context. RAG (Retrieval-Augmented Generation) provides a complementary " +
                "approach: instead of keeping everything in context, the agent queries an " +
                "external knowledge base on demand and injects only the relevant passages.",
                Map.of("topic", "memory", "category", "deep-dive"));

        kb.addSource("observability-and-cost",
                "Enterprise adoption of agent frameworks depends heavily on observability " +
                "and cost control. LangSmith (for LangChain) and Arize Phoenix provide " +
                "trace-level visibility into LLM calls. SwarmAI includes a built-in " +
                "ObservabilityHelper, DecisionTracer for workflow replay, and BudgetTracker " +
                "that enforces token and dollar limits per workflow. BudgetPolicy can be set " +
                "to WARN or HALT when thresholds are exceeded, giving operators granular " +
                "control over spend. Frameworks without built-in budget controls risk " +
                "runaway costs in production, especially in multi-turn agent loops.",
                Map.of("topic", "observability", "category", "enterprise"));

        kb.addSource("rag-pattern-details",
                "The RAG (Retrieval-Augmented Generation) pattern addresses two LLM " +
                "limitations: knowledge cutoff and hallucination. In a RAG pipeline the " +
                "user's query is first used to retrieve relevant documents from a knowledge " +
                "store (vector database, keyword index, or hybrid). The retrieved passages " +
                "are then injected into the LLM prompt as context, grounding the model's " +
                "response in factual evidence. This reduces hallucination because the model " +
                "can cite specific passages rather than relying on parametric memory. " +
                "Advanced RAG techniques include query rewriting, re-ranking retrieved " +
                "chunks, and multi-hop retrieval where the model iteratively refines its " +
                "search based on intermediate findings.",
                Map.of("topic", "rag", "category", "deep-dive"));

        return kb;
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"rag-research"});
    }
}
