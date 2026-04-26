package ai.intelliswarm.swarmai.examples.ragapp;

import ai.intelliswarm.swarmai.knowledge.rag.RagAnswer;
import ai.intelliswarm.swarmai.knowledge.rag.RagConfig;
import ai.intelliswarm.swarmai.knowledge.rag.RagPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin Spring facade around {@link RagPipeline}.
 *
 * <p><b>Before:</b> ~950 lines hand-rolling a 5-stage RAG pipeline (analyze →
 * retrieve → score → synthesize → fact-check) with chunkers, hybrid retrieval,
 * RRF fusion, citation parsing — all duplicated in every example app.
 *
 * <p><b>After:</b> ~150 lines that delegate the AI parts to {@code RagPipeline}
 * (which baked in the lessons learned from the 2026-04-26 IntelliDoc eval) and
 * keep only the bits this example actually owns: document registry, per-session
 * conversation history, request statistics.
 *
 * <p>Loaded conditionally by {@code swarmai.rag-app.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "swarmai.rag-app", name = "enabled", havingValue = "true")
public class RAGKnowledgeBaseApp {

    private static final Logger logger = LoggerFactory.getLogger(RAGKnowledgeBaseApp.class);

    private final RagPipeline rag;

    // Per-document metadata so the controller can list / delete / show counts.
    private final Map<String, DocumentInfo> documents = new ConcurrentHashMap<>();
    // Per-session conversation history (rolling buffer).
    private final Map<String, List<ConversationMessage>> conversations = new ConcurrentHashMap<>();
    // Counters for /stats.
    private final AtomicLong queriesProcessed = new AtomicLong();
    private final AtomicLong totalQueryDurationMs = new AtomicLong();
    private final Instant startedAt = Instant.now();

    public RAGKnowledgeBaseApp(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.rag = RagPipeline.builder()
                .vectorStore(vectorStore)
                .chatClient(chatClientBuilder.build())
                .config(RagConfig.defaults())   // hybrid + simple prompt + chunk 800/100
                .build();
        logger.info("RAGKnowledgeBaseApp initialised (RagPipeline with eval-winning defaults)");
    }

    // =========================================================================
    // Ingestion
    // =========================================================================

    public DocumentInfo ingestText(String docId, String title, String content) {
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");

        rag.ingestText(title, content);   // sourceId = title so citations show the human-readable name
        // Approximate chunks; the exact count is internal to RagPipeline.
        int approxChunks = Math.max(1, content.length() / RagConfig.defaults().chunkSize());
        DocumentInfo info = new DocumentInfo(docId, title, approxChunks, content.length(), Instant.now());
        documents.put(docId, info);
        return info;
    }

    public DocumentInfo ingestFile(Path file) throws IOException {
        String docId = UUID.randomUUID().toString();
        String title = file.getFileName().toString();
        String content = Files.readString(file);
        return ingestText(docId, title, content);
    }

    public boolean deleteDocument(String docId) {
        DocumentInfo removed = documents.remove(docId);
        if (removed == null) return false;
        rag.removeSource(removed.filename());
        return true;
    }

    public List<DocumentInfo> getDocuments() {
        return new ArrayList<>(documents.values());
    }

    // =========================================================================
    // Query
    // =========================================================================

    public RAGResponse query(String sessionId, String question) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(question, "question");

        appendConversation(sessionId, "user", question);
        RagAnswer a = rag.query(question);
        appendConversation(sessionId, "assistant", a.answer());

        queriesProcessed.incrementAndGet();
        totalQueryDurationMs.addAndGet(a.durationMs());

        List<Citation> citations = a.citations().stream()
                .map(c -> new Citation(c.sourceId(), c.passage(), 1.0))   // RagPipeline doesn't expose per-citation relevance
                .toList();

        double confidence = a.refused() ? 0.0 : (citations.isEmpty() ? 0.5 : 1.0);
        String notes = a.refused() ? "Model refused — no relevant passages." : "";
        return new RAGResponse(a.answer(), citations, confidence, notes, a.durationMs());
    }

    // =========================================================================
    // Conversations + stats
    // =========================================================================

    public List<ConversationMessage> getConversation(String sessionId) {
        return conversations.getOrDefault(sessionId, List.of());
    }

    public Map<String, Object> getStats() {
        long n = queriesProcessed.get();
        long avg = n == 0 ? 0 : totalQueryDurationMs.get() / n;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("documentCount", documents.size());
        stats.put("totalChunks", documents.values().stream().mapToInt(DocumentInfo::chunks).sum());
        stats.put("queriesProcessed", n);
        stats.put("avgResponseMs", avg);
        stats.put("activeSessions", conversations.size());
        stats.put("upSince", startedAt.toString());
        return stats;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void appendConversation(String sessionId, String role, String content) {
        conversations.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new ConversationMessage(role, content, Instant.now()));
    }

    // =========================================================================
    // DTOs (kept on the App so the Controller's record shape doesn't change)
    // =========================================================================

    public record DocumentInfo(String id, String filename, int chunks, int totalChars, Instant ingestedAt) {}
    public record Citation(String sourceId, String passage, double relevance) {}
    public record RAGResponse(String answer, List<Citation> citations, double confidence,
                              String analysisNotes, long durationMs) {}
    public record ConversationMessage(String role, String content, Instant timestamp) {}
}
