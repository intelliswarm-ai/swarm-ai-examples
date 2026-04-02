package ai.intelliswarm.swarmai.examples.ragapp;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.knowledge.InMemoryKnowledge;
import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.Channels;
import ai.intelliswarm.swarmai.state.CompiledSwarm;
import ai.intelliswarm.swarmai.state.StateSchema;
import ai.intelliswarm.swarmai.state.SwarmGraph;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.common.FileReadTool;
import ai.intelliswarm.swarmai.tool.common.PDFReadTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core RAG (Retrieval-Augmented Generation) engine for a deep knowledge base application.
 *
 * <p>Implements a five-stage multi-agent pipeline built on {@link SwarmGraph}:
 *
 * <pre>
 *   User Query
 *       |
 *       v
 *   [Query Analyzer]     -- rewrites query, generates 3 search variants
 *       |
 *       v
 *   [Retriever]          -- semantic search (VectorKnowledge) + keyword search (InMemoryKnowledge)
 *       |
 *       v
 *   [Relevance Scorer]   -- ranks passages, assigns confidence, filters below threshold
 *       |
 *       v
 *   [Synthesizer]        -- generates answer with inline [source: X] citations
 *       |
 *       v
 *   [Fact Checker]       -- verifies claims against sources, flags unsupported statements
 *       |
 *       v
 *   Final Response with citations + confidence score
 * </pre>
 *
 * <p>The pipeline is compiled once at construction time and reused for every query via
 * {@link CompiledSwarm#kickoff(AgentState)}.  Documents can be ingested from text, files
 * (txt, md, json, csv), and PDFs.  Both an in-memory keyword index and an optional vector
 * store are supported, allowing graceful degradation when no vector database is configured.
 *
 * <p>Conditionally loaded: starts only when {@code swarmai.rag-app.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "swarmai.rag-app", name = "enabled", havingValue = "true")
public class RAGKnowledgeBaseApp {

    private static final Logger logger = LoggerFactory.getLogger(RAGKnowledgeBaseApp.class);

    // =========================================================================
    // Public records
    // =========================================================================

    /** Metadata for an ingested document. */
    public record DocumentInfo(String id, String filename, String contentType,
                               int chunks, int totalChars, Instant ingestedAt) {}

    /** A single search hit with content, source reference, score, and metadata. */
    public record SearchResult(String content, String sourceId, double score,
                               Map<String, Object> metadata) {}

    /** The final response returned by the RAG pipeline. */
    public record RAGResponse(String answer, String sessionId, List<Citation> citations,
                              double confidence, String analysisNotes, long durationMs) {}

    /** A citation linking an answer claim back to a source passage. */
    public record Citation(String sourceId, String passage, double relevance) {}

    /** A single message in a conversation session. */
    public record ConversationMessage(String role, String content, Instant timestamp) {}

    // =========================================================================
    // Dependencies and data stores
    // =========================================================================

    private final ChatClient chatClient;
    private final InMemoryKnowledge memoryKnowledge;
    private final Object vectorKnowledge;   // VectorKnowledge if vectorStore present, else null
    private final PDFReadTool pdfReadTool;
    private final FileReadTool fileReadTool;

    private final ConcurrentHashMap<String, DocumentInfo> documentRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ConversationMessage>> conversations = new ConcurrentHashMap<>();
    private final AtomicLong queryCount = new AtomicLong(0);

    private final CompiledSwarm ragPipeline;
    private final StateSchema stateSchema;

    // =========================================================================
    // Constructor -- builds the SwarmGraph pipeline once
    // =========================================================================

    public RAGKnowledgeBaseApp(
            ChatClient.Builder chatClientBuilder,
            @Autowired(required = false) VectorStore vectorStore,
            PDFReadTool pdfReadTool,
            FileReadTool fileReadTool) {

        this.chatClient = chatClientBuilder.build();
        this.pdfReadTool = pdfReadTool;
        this.fileReadTool = fileReadTool;

        // -- Knowledge stores --
        this.memoryKnowledge = new InMemoryKnowledge();

        if (vectorStore != null) {
            this.vectorKnowledge = createVectorKnowledge(vectorStore);
            logger.info("RAGKnowledgeBaseApp: VectorKnowledge initialized (vector store available)");
        } else {
            this.vectorKnowledge = null;
            logger.info("RAGKnowledgeBaseApp: No vector store configured -- using InMemoryKnowledge only");
        }

        // -- State schema --
        this.stateSchema = StateSchema.builder()
                .channel("question",              Channels.<String>lastWriteWins())
                .channel("session_id",            Channels.<String>lastWriteWins())
                .channel("conversation_context",  Channels.<String>lastWriteWins())
                .channel("search_queries",        Channels.<String>lastWriteWins())
                .channel("retrieved_passages",    Channels.<String>lastWriteWins())
                .channel("scored_passages",       Channels.<String>lastWriteWins())
                .channel("answer",                Channels.<String>lastWriteWins())
                .channel("confidence",            Channels.<String>lastWriteWins())
                .channel("notes",                 Channels.<String>lastWriteWins())
                .allowUndeclaredKeys(true)
                .build();

        // -- Placeholder agent & task (required for SwarmGraph compile validation) --
        Agent placeholderAgent = Agent.builder()
                .role("RAG Pipeline Router")
                .goal("Route queries through the five-stage RAG pipeline")
                .backstory("You coordinate a multi-stage retrieval-augmented generation pipeline.")
                .chatClient(chatClient)
                .build();

        Task placeholderTask = Task.builder()
                .description("Process a knowledge base query through the RAG pipeline")
                .expectedOutput("A cited, fact-checked answer")
                .agent(placeholderAgent)
                .build();

        // -- Build the five-stage pipeline --
        this.ragPipeline = SwarmGraph.create()
                .addAgent(placeholderAgent)
                .addTask(placeholderTask)

                .addEdge(SwarmGraph.START, "analyze_query")

                // ─────────────────────────────────────────────────────────
                // STAGE 1: Query Analyzer
                // Rewrites the user question into 3 search variants to
                // maximize retrieval recall.
                // ─────────────────────────────────────────────────────────
                .addNode("analyze_query", state -> {
                    String question = state.valueOrDefault("question", "");
                    String context = state.valueOrDefault("conversation_context", "");

                    Agent analyzer = Agent.builder()
                            .role("Query Analyzer")
                            .goal("Rewrite the user's question into three distinct search queries "
                                    + "that maximize retrieval coverage: the original query, a "
                                    + "rephrased semantic variant, and a keyword-focused variant.")
                            .backstory("You are an expert information retrieval specialist who "
                                    + "understands that a single query rarely captures all relevant "
                                    + "documents. You generate complementary query variants that "
                                    + "cover synonyms, related concepts, and key terminology. "
                                    + "You consider conversation history for context.")
                            .chatClient(chatClient)
                            .temperature(0.3)
                            .build();

                    String description = "Analyze the following question and produce exactly 3 "
                            + "search query variants, one per line.\n\n"
                            + "Question: " + question + "\n";
                    if (context != null && !context.isBlank()) {
                        description += "\nConversation context:\n" + context + "\n";
                    }
                    description += "\nRules:\n"
                            + "1. Line 1: The original question (cleaned up if needed)\n"
                            + "2. Line 2: A semantically rephrased version using different wording\n"
                            + "3. Line 3: A keyword-focused version (key terms separated by spaces)\n"
                            + "\nOutput ONLY the 3 lines, no numbering, no labels, no explanation.";

                    Task task = Task.builder()
                            .description(description)
                            .expectedOutput("Three search query variants, one per line")
                            .agent(analyzer)
                            .build();

                    TaskOutput output = analyzer.executeTask(task, List.of());
                    String queries = output.getRawOutput() != null
                            ? output.getRawOutput().trim()
                            : question;
                    logger.info("  [analyze_query] Generated {} search variants",
                            queries.split("\n").length);
                    return Map.of("search_queries", queries);
                })

                .addEdge("analyze_query", "retrieve")

                // ─────────────────────────────────────────────────────────
                // STAGE 2: Retriever (pure Java -- no LLM call)
                // Runs each query variant against both knowledge stores,
                // collects and deduplicates results.
                // ─────────────────────────────────────────────────────────
                .addNode("retrieve", state -> {
                    String queriesRaw = state.valueOrDefault("search_queries", "");
                    String[] queries = queriesRaw.split("\n");

                    Set<String> seenContent = new LinkedHashSet<>();
                    StringBuilder passages = new StringBuilder();
                    int passageIndex = 0;

                    for (String q : queries) {
                        String query = q.trim();
                        if (query.isEmpty()) continue;

                        // Search in-memory knowledge (keyword-based)
                        List<String> memResults = memoryKnowledge.search(query, 5);
                        for (String r : memResults) {
                            String normalized = r.trim().toLowerCase();
                            if (normalized.length() > 50) {
                                normalized = normalized.substring(0, 50);
                            }
                            if (seenContent.add(normalized)) {
                                passageIndex++;
                                passages.append("--- Passage ").append(passageIndex).append(" ---\n");
                                passages.append(r.trim()).append("\n\n");
                            }
                        }

                        // Search vector knowledge if available
                        if (vectorKnowledge != null) {
                            try {
                                List<String> vResults = invokeVectorSearch(query, 5);
                                for (String r : vResults) {
                                    String normalized = r.trim().toLowerCase();
                                    if (normalized.length() > 50) {
                                        normalized = normalized.substring(0, 50);
                                    }
                                    if (seenContent.add(normalized)) {
                                        passageIndex++;
                                        passages.append("--- Passage ").append(passageIndex)
                                                .append(" (vector) ---\n");
                                        passages.append(r.trim()).append("\n\n");
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("  [retrieve] Vector search failed for query '{}': {}",
                                        query, e.getMessage());
                            }
                        }
                    }

                    String result = passages.toString();
                    if (result.isBlank()) {
                        result = "No relevant passages found in the knowledge base.";
                    }
                    logger.info("  [retrieve] Found {} unique passages across {} query variants",
                            passageIndex, queries.length);
                    return Map.of("retrieved_passages", result);
                })

                .addEdge("retrieve", "score")

                // ─────────────────────────────────────────────────────────
                // STAGE 3: Relevance Scorer
                // Ranks each retrieved passage for relevance to the
                // original question, filters out weak matches.
                // ─────────────────────────────────────────────────────────
                .addNode("score", state -> {
                    String question = state.valueOrDefault("question", "");
                    String passages = state.valueOrDefault("retrieved_passages", "");

                    if (passages.isBlank() || passages.startsWith("No relevant passages")) {
                        logger.info("  [score] No passages to score");
                        return Map.of("scored_passages",
                                "No relevant passages found in the knowledge base.");
                    }

                    Agent scorer = Agent.builder()
                            .role("Relevance Scorer")
                            .goal("Score each retrieved passage from 0 to 100 for relevance to "
                                    + "the user's question. Filter out passages scoring below 40. "
                                    + "Return only the passages that are genuinely relevant.")
                            .backstory("You are a precision-oriented information quality analyst. "
                                    + "You evaluate passages strictly against the query intent. "
                                    + "A score of 90-100 means directly and fully relevant; "
                                    + "60-89 means partially relevant; 40-59 means marginally "
                                    + "relevant; below 40 means not relevant enough to include.")
                            .chatClient(chatClient)
                            .temperature(0.1)
                            .build();

                    String description = "Score each passage below for relevance to the question.\n\n"
                            + "Question: " + question + "\n\n"
                            + "Retrieved passages:\n" + truncate(passages, 4000) + "\n\n"
                            + "For each passage that scores 40 or above, output:\n"
                            + "PASSAGE: [paste the passage text]\n"
                            + "SCORE: [0-100]\n"
                            + "REASON: [one sentence why it is relevant]\n\n"
                            + "Omit any passage scoring below 40. "
                            + "Order results from highest to lowest score.";

                    Task task = Task.builder()
                            .description(description)
                            .expectedOutput("Scored and filtered passages with PASSAGE/SCORE/REASON format")
                            .agent(scorer)
                            .build();

                    TaskOutput output = scorer.executeTask(task, List.of());
                    String scored = output.getRawOutput() != null
                            ? output.getRawOutput().trim()
                            : "No passages met the relevance threshold.";
                    int passageCount = countOccurrences(scored, "SCORE:");
                    logger.info("  [score] {} passages passed relevance threshold (>= 40)",
                            passageCount);
                    return Map.of("scored_passages", scored);
                })

                .addEdge("score", "synthesize")

                // ─────────────────────────────────────────────────────────
                // STAGE 4: Synthesizer
                // Generates a comprehensive answer with inline citations.
                // ─────────────────────────────────────────────────────────
                .addNode("synthesize", state -> {
                    String question = state.valueOrDefault("question", "");
                    String scoredPassages = state.valueOrDefault("scored_passages", "");
                    String context = state.valueOrDefault("conversation_context", "");

                    Agent synthesizer = Agent.builder()
                            .role("Research Synthesizer")
                            .goal("Generate a comprehensive, well-structured answer to the "
                                    + "user's question using ONLY the provided source passages. "
                                    + "Every factual claim must include an inline citation in "
                                    + "the format [source: passage_N]. If the sources are "
                                    + "insufficient, state that explicitly.")
                            .backstory("You are a senior research analyst who produces evidence-"
                                    + "based answers. You never introduce information not found "
                                    + "in the sources. You organize answers logically, starting "
                                    + "with the most important findings. You are thorough but "
                                    + "concise, and you always cite your sources inline.")
                            .chatClient(chatClient)
                            .temperature(0.4)
                            .build();

                    String description = "Answer the following question based EXCLUSIVELY on "
                            + "the scored source passages below.\n\n"
                            + "Question: " + question + "\n\n";
                    if (context != null && !context.isBlank()) {
                        description += "Conversation context:\n" + context + "\n\n";
                    }
                    description += "Source passages:\n" + truncate(scoredPassages, 4000) + "\n\n"
                            + "Rules:\n"
                            + "1. Ground EVERY claim in a source passage\n"
                            + "2. Use inline citations: [source: passage_N] or [source: <source_id>]\n"
                            + "3. If information is insufficient, say so explicitly\n"
                            + "4. Do NOT introduce external information\n"
                            + "5. Structure the answer with clear sections if the topic warrants it\n"
                            + "6. Be comprehensive but concise";

                    Task task = Task.builder()
                            .description(description)
                            .expectedOutput("A well-cited answer grounded in source passages")
                            .agent(synthesizer)
                            .build();

                    TaskOutput output = synthesizer.executeTask(task, List.of());
                    String answer = output.getRawOutput() != null
                            ? output.getRawOutput().trim()
                            : "Unable to generate an answer from the available sources.";
                    logger.info("  [synthesize] Generated answer ({} chars, {} citations)",
                            answer.length(), countOccurrences(answer, "[source:"));
                    return Map.of("answer", answer);
                })

                .addEdge("synthesize", "fact_check")

                // ─────────────────────────────────────────────────────────
                // STAGE 5: Fact Checker
                // Verifies that every claim in the answer is supported by
                // the source passages and assigns an overall confidence.
                // ─────────────────────────────────────────────────────────
                .addNode("fact_check", state -> {
                    String answer = state.valueOrDefault("answer", "");
                    String scoredPassages = state.valueOrDefault("scored_passages", "");

                    Agent factChecker = Agent.builder()
                            .role("Fact Checker")
                            .goal("Verify every factual claim in the answer against the source "
                                    + "passages. Assign an overall confidence score from 0 to 100. "
                                    + "Flag any claims that are not supported by the sources.")
                            .backstory("You are a meticulous fact-checking editor at a major "
                                    + "research publication. You cross-reference every claim "
                                    + "against primary sources. You are objective and precise. "
                                    + "A confidence of 90+ means all claims are well-supported; "
                                    + "70-89 means mostly supported with minor gaps; "
                                    + "50-69 means partially supported; below 50 means "
                                    + "significant unsupported claims.")
                            .chatClient(chatClient)
                            .temperature(0.1)
                            .build();

                    String description = "Fact-check the following answer against the source passages.\n\n"
                            + "Answer to verify:\n" + truncate(answer, 3000) + "\n\n"
                            + "Source passages:\n" + truncate(scoredPassages, 3000) + "\n\n"
                            + "Respond in EXACTLY this format:\n"
                            + "CONFIDENCE: [0-100]\n"
                            + "SUPPORTED_CLAIMS: [number of verified claims]\n"
                            + "UNSUPPORTED_CLAIMS: [list any unsupported claims, or 'none']\n"
                            + "NOTES: [brief assessment of answer quality and accuracy]";

                    Task task = Task.builder()
                            .description(description)
                            .expectedOutput("CONFIDENCE score, supported/unsupported claim counts, and notes")
                            .agent(factChecker)
                            .build();

                    TaskOutput output = factChecker.executeTask(task, List.of());
                    String raw = output.getRawOutput() != null ? output.getRawOutput().trim() : "";

                    String confidence = extractField(raw, "CONFIDENCE", "50");
                    String notes = extractField(raw, "NOTES", "Fact check completed.");

                    // Include unsupported claims in notes if present
                    String unsupported = extractField(raw, "UNSUPPORTED_CLAIMS", "none");
                    if (!"none".equalsIgnoreCase(unsupported.trim())) {
                        notes += " | Unsupported claims: " + unsupported;
                    }

                    logger.info("  [fact_check] Confidence: {} | {}",
                            confidence, notes.length() > 80 ? notes.substring(0, 80) + "..." : notes);
                    return Map.of("confidence", confidence, "notes", notes);
                })

                .addEdge("fact_check", SwarmGraph.END)
                .stateSchema(stateSchema)
                .compileOrThrow();

        logger.info("RAGKnowledgeBaseApp initialized -- 5-stage SwarmGraph pipeline compiled");
    }

    // =========================================================================
    // 1. Document Ingestion -- Text
    // =========================================================================

    /**
     * Ingest a text document into both the in-memory and (optionally) vector
     * knowledge stores.
     *
     * @param docId   unique identifier for the document
     * @param title   human-readable title (used as filename in DocumentInfo)
     * @param content raw text content to ingest
     * @return metadata about the ingested document
     */
    public DocumentInfo ingestText(String docId, String title, String content) {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("Content must not be blank");
        }

        logger.info("Ingesting text document: id={} title='{}' length={}", docId, title, content.length());

        // Add to in-memory keyword index
        Map<String, Object> metadata = Map.of(
                "title", title != null ? title : docId,
                "type", "text",
                "ingested_at", Instant.now().toString()
        );
        memoryKnowledge.addSource(docId, content, metadata);

        // Add to vector store if available (will be chunked and embedded)
        int chunks = 1;
        if (vectorKnowledge != null) {
            try {
                invokeVectorAddSource(docId, content, metadata);
                // Estimate chunk count: ~500 chars per chunk
                chunks = Math.max(1, content.length() / 500);
                logger.info("  Added to vector knowledge ({} estimated chunks)", chunks);
            } catch (Exception e) {
                logger.warn("  Failed to add to vector knowledge: {}", e.getMessage());
            }
        }

        DocumentInfo info = new DocumentInfo(
                docId, title != null ? title : docId, "text/plain",
                chunks, content.length(), Instant.now()
        );
        documentRegistry.put(docId, info);

        logger.info("  Document '{}' ingested: {} chars, {} chunks", docId, content.length(), chunks);
        return info;
    }

    // =========================================================================
    // 2. Document Ingestion -- File
    // =========================================================================

    /**
     * Ingest a file from the local filesystem. Supports .txt, .md, .pdf, .json, .csv.
     *
     * @param filePath path to the file to ingest
     * @return metadata about the ingested document
     */
    public DocumentInfo ingestFile(Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");

        String filename = filePath.getFileName().toString();
        String extension = getFileExtension(filename).toLowerCase();
        String docId = "file-" + filename.replaceAll("[^a-zA-Z0-9._-]", "_")
                + "-" + System.currentTimeMillis();

        logger.info("Ingesting file: {} (extension: {})", filePath, extension);

        String content;
        String contentType;

        switch (extension) {
            case "pdf" -> {
                contentType = "application/pdf";
                try {
                    Object result = pdfReadTool.execute(
                            Map.of("path", filePath.toAbsolutePath().toString()));
                    content = result != null ? result.toString() : "";
                } catch (Exception e) {
                    throw new RuntimeException("Failed to read PDF file: " + filePath, e);
                }
            }
            case "txt", "md", "json", "csv" -> {
                contentType = switch (extension) {
                    case "md"   -> "text/markdown";
                    case "json" -> "application/json";
                    case "csv"  -> "text/csv";
                    default     -> "text/plain";
                };
                try {
                    Object result = fileReadTool.execute(
                            Map.of("path", filePath.toAbsolutePath().toString()));
                    content = result != null ? result.toString() : "";
                } catch (Exception e) {
                    throw new RuntimeException("Failed to read file: " + filePath, e);
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported file format: " + extension
                            + ". Supported: txt, md, pdf, json, csv");
        }

        if (content.isBlank()) {
            throw new RuntimeException("Extracted content is empty for file: " + filePath);
        }

        // Delegate to text ingestion
        DocumentInfo info = ingestText(docId, filename, content);

        // Update the content type in the registry
        DocumentInfo corrected = new DocumentInfo(
                info.id(), info.filename(), contentType,
                info.chunks(), info.totalChars(), info.ingestedAt()
        );
        documentRegistry.put(docId, corrected);

        logger.info("File '{}' ingested as document '{}': {} chars", filename, docId, content.length());
        return corrected;
    }

    // =========================================================================
    // 3. Query -- THE MAIN RAG PIPELINE
    // =========================================================================

    /**
     * Execute the full 5-stage RAG pipeline for a user question.
     *
     * <ol>
     *   <li>Query Analyzer: generates 3 search query variants</li>
     *   <li>Retriever: searches both knowledge stores (keyword + vector)</li>
     *   <li>Relevance Scorer: ranks and filters passages</li>
     *   <li>Synthesizer: generates cited answer</li>
     *   <li>Fact Checker: verifies claims, assigns confidence</li>
     * </ol>
     *
     * @param sessionId conversation session identifier
     * @param question  the user's question
     * @return a {@link RAGResponse} containing the answer, citations, and confidence
     */
    public RAGResponse query(String sessionId, String question) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(question, "question must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("Question must not be blank");
        }

        long startTime = System.currentTimeMillis();
        queryCount.incrementAndGet();

        logger.info("RAG query: sessionId={} question='{}' ({} chars)",
                sessionId, truncate(question, 60), question.length());

        // Build conversation context from history
        List<ConversationMessage> history = conversations.computeIfAbsent(
                sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        String conversationContext = buildConversationContext(history);

        // Create initial state
        AgentState initialState = AgentState.of(stateSchema, Map.of(
                "question", question,
                "session_id", sessionId,
                "conversation_context", conversationContext
        ));

        // Execute the 5-stage pipeline
        SwarmOutput result = ragPipeline.kickoff(initialState);

        // Extract results from the final state
        String answer = result.getFinalOutput();
        if (answer == null || answer.isBlank()) {
            answer = "I was unable to find a sufficient answer to your question in the "
                    + "knowledge base. Please try rephrasing your question or ensure "
                    + "relevant documents have been ingested.";
        }

        // Parse confidence score
        double confidence = parseConfidence(result);

        // Parse analysis notes
        String notes = parseNotes(result);

        // Extract citations from the answer text
        List<Citation> citations = extractCitations(answer);

        // Store conversation messages
        Instant now = Instant.now();
        history.add(new ConversationMessage("user", question, now));
        history.add(new ConversationMessage("assistant", answer, now));

        long durationMs = System.currentTimeMillis() - startTime;

        RAGResponse response = new RAGResponse(
                answer, sessionId, citations, confidence, notes, durationMs
        );

        logger.info("RAG query complete: sessionId={} confidence={} citations={} duration={}ms",
                sessionId, confidence, citations.size(), durationMs);

        return response;
    }

    // =========================================================================
    // 4. Document Management
    // =========================================================================

    /**
     * List all ingested documents.
     *
     * @return unmodifiable list of document metadata
     */
    public List<DocumentInfo> getDocuments() {
        return List.copyOf(documentRegistry.values());
    }

    /**
     * Remove a document from the knowledge base.
     *
     * @param docId identifier of the document to remove
     * @return true if the document was found and removed
     */
    public boolean deleteDocument(String docId) {
        Objects.requireNonNull(docId, "docId must not be null");

        DocumentInfo removed = documentRegistry.remove(docId);
        if (removed == null) {
            logger.warn("deleteDocument: document '{}' not found", docId);
            return false;
        }

        // Note: InMemoryKnowledge and VectorKnowledge do not currently expose a
        // remove API. The document metadata is removed from the registry so it
        // will not appear in listings. A full rebuild would be needed for a
        // production system with vector store cleanup.
        logger.info("Document '{}' removed from registry (filename: {})", docId, removed.filename());
        return true;
    }

    // =========================================================================
    // 5. Conversation History
    // =========================================================================

    /**
     * Retrieve conversation history for a session.
     *
     * @param sessionId the session to look up
     * @return list of conversation messages, empty if session not found
     */
    public List<ConversationMessage> getConversation(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return conversations.getOrDefault(sessionId, List.of());
    }

    // =========================================================================
    // 6. Statistics
    // =========================================================================

    /**
     * Return operational statistics for the RAG engine.
     *
     * @return map with keys: documentCount, totalChunks, totalChars, queriesProcessed,
     *         activeSessions, vectorStoreAvailable
     */
    public Map<String, Object> getStats() {
        int totalChunks = documentRegistry.values().stream()
                .mapToInt(DocumentInfo::chunks).sum();
        int totalChars = documentRegistry.values().stream()
                .mapToInt(DocumentInfo::totalChars).sum();

        return Map.of(
                "documentCount", documentRegistry.size(),
                "totalChunks", totalChunks,
                "totalChars", totalChars,
                "queriesProcessed", queryCount.get(),
                "activeSessions", conversations.size(),
                "vectorStoreAvailable", vectorKnowledge != null
        );
    }

    // =========================================================================
    // Internal helpers -- VectorKnowledge reflective calls
    //
    // VectorKnowledge is accessed via reflection to avoid a hard compile-time
    // dependency on the class (which requires a VectorStore bean).  This allows
    // the app to start without a vector store while still using it when present.
    // =========================================================================

    private Object createVectorKnowledge(VectorStore vectorStore) {
        try {
            Class<?> clazz = Class.forName("ai.intelliswarm.swarmai.knowledge.VectorKnowledge");
            return clazz.getConstructor(VectorStore.class).newInstance(vectorStore);
        } catch (Exception e) {
            logger.warn("Could not create VectorKnowledge: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeVectorSearch(String query, int limit) {
        try {
            return (List<String>) vectorKnowledge.getClass()
                    .getMethod("search", String.class, int.class)
                    .invoke(vectorKnowledge, query, limit);
        } catch (Exception e) {
            logger.warn("VectorKnowledge.search() failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void invokeVectorAddSource(String sourceId, String content, Map<String, Object> metadata) {
        try {
            vectorKnowledge.getClass()
                    .getMethod("addSource", String.class, String.class, Map.class)
                    .invoke(vectorKnowledge, sourceId, content, metadata);
        } catch (Exception e) {
            logger.warn("VectorKnowledge.addSource() failed: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Internal helpers -- conversation context
    // =========================================================================

    /**
     * Builds a conversation context string from the most recent messages.
     * Returns at most the last 10 messages formatted as "role: content".
     */
    private String buildConversationContext(List<ConversationMessage> history) {
        if (history.isEmpty()) {
            return "";
        }
        int start = Math.max(0, history.size() - 10);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < history.size(); i++) {
            ConversationMessage msg = history.get(i);
            sb.append(msg.role()).append(": ").append(msg.content()).append("\n");
        }
        return sb.toString();
    }

    // =========================================================================
    // Internal helpers -- result parsing
    // =========================================================================

    /**
     * Parse confidence score from pipeline output. Searches task outputs for a
     * numeric confidence value, falling back to 50.0 if not found.
     */
    private double parseConfidence(SwarmOutput result) {
        try {
            for (var taskOutput : result.getTaskOutputs()) {
                String raw = taskOutput.getRawOutput();
                if (raw != null && raw.contains("CONFIDENCE")) {
                    String value = extractField(raw, "CONFIDENCE", "50");
                    return parseDouble(value, 50.0);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse confidence: {}", e.getMessage());
        }
        return 50.0;
    }

    /**
     * Parse analysis notes from the fact checker stage output.
     */
    private String parseNotes(SwarmOutput result) {
        try {
            for (var taskOutput : result.getTaskOutputs()) {
                String raw = taskOutput.getRawOutput();
                if (raw != null && raw.contains("NOTES")) {
                    return extractField(raw, "NOTES", "");
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse notes: {}", e.getMessage());
        }
        return "";
    }

    /**
     * Extract citation references from the synthesized answer.
     * Looks for patterns like [source: passage_1], [source: some-doc-id], etc.
     */
    private List<Citation> extractCitations(String answer) {
        List<Citation> citations = new ArrayList<>();
        if (answer == null || answer.isBlank()) return citations;

        Pattern pattern = Pattern.compile("\\[source:\\s*([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(answer);
        Set<String> seen = new HashSet<>();

        while (matcher.find()) {
            String sourceId = matcher.group(1).trim();
            if (seen.add(sourceId)) {
                // Extract a snippet of surrounding text as the passage reference
                int start = Math.max(0, matcher.start() - 100);
                int end = Math.min(answer.length(), matcher.end() + 50);
                String passage = answer.substring(start, end).trim();

                citations.add(new Citation(sourceId, passage, 1.0));
            }
        }
        return citations;
    }

    // =========================================================================
    // Internal helpers -- text utilities
    // =========================================================================

    /**
     * Truncate a string to the given maximum length, appending "..." if truncated.
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * Extract a named field value from structured LLM output.
     * Looks for "FIELD_NAME: value" or "FIELD_NAME: value\n" patterns.
     */
    private static String extractField(String text, String fieldName, String defaultValue) {
        if (text == null || text.isBlank()) return defaultValue;

        // Try exact pattern: FIELD_NAME: value (until next uppercase label or end)
        Pattern p = Pattern.compile(
                fieldName + ":\\s*(.+?)(?=\\n[A-Z_]+:|$)",
                Pattern.DOTALL
        );
        Matcher m = p.matcher(text);
        if (m.find()) {
            String value = m.group(1).trim();
            if (!value.isEmpty()) return value;
        }

        // Fallback: simple line match
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith(fieldName + ":")) {
                String value = trimmed.substring(fieldName.length() + 1).trim();
                if (!value.isEmpty()) return value;
            }
        }

        return defaultValue;
    }

    /**
     * Count occurrences of a substring within a string.
     */
    private static int countOccurrences(String text, String sub) {
        if (text == null || sub == null || sub.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * Parse a double from a string that may contain non-numeric characters.
     * Extracts the first decimal number found.
     */
    private static double parseDouble(String text, double defaultValue) {
        if (text == null || text.isBlank()) return defaultValue;
        Matcher m = Pattern.compile("(\\d+\\.?\\d*)").matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Extract file extension from a filename.
     */
    private static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) return "";
        return filename.substring(lastDot + 1);
    }
}
