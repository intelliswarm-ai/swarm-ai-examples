package ai.intelliswarm.swarmai.examples.ragapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for the Deep RAG Knowledge Base application.
 *
 * Exposes endpoints for document ingestion (text and file upload), document
 * management, conversational RAG queries with citations and fact-checking,
 * conversation history retrieval, and system statistics.
 *
 * All AI-related business logic is delegated to {@link RAGKnowledgeBaseApp};
 * this class handles HTTP plumbing, request validation, and logging.
 *
 * Conditionally loaded: starts only when {@code swarmai.rag-app.enabled=true}.
 */
@RestController
@RequestMapping("/api/rag")
@ConditionalOnProperty(prefix = "swarmai.rag-app", name = "enabled", havingValue = "true")
public class RAGKnowledgeBaseController {

    private static final Logger logger = LoggerFactory.getLogger(RAGKnowledgeBaseController.class);

    private final RAGKnowledgeBaseApp app;

    public RAGKnowledgeBaseController(RAGKnowledgeBaseApp app) {
        this.app = app;
        logger.info("RAGKnowledgeBaseController initialized -- endpoints available at /api/rag");
    }

    // =========================================================================
    // DTOs (Java records)
    // =========================================================================

    public record TextIngestionRequest(String title, String content) {}

    public record DocumentInfo(String docId, String title, int chunks, int characters, String ingestedAt) {}

    public record DocumentListResponse(List<DocumentInfo> documents, int totalChunks) {}

    public record QueryRequest(String sessionId, String question) {}

    public record Citation(String chunkId, String source, String passage, double relevance) {}

    public record QueryResponse(String sessionId, String answer, List<Citation> citations,
                                double confidence, String notes, long durationMs) {}

    public record ConversationEntry(String role, String content, String timestamp) {}

    public record ConversationResponse(String sessionId, List<ConversationEntry> messages) {}

    public record StatsResponse(int documentCount, int totalChunks, long queriesProcessed,
                                long avgResponseMs, String upSince) {}

    public record ErrorResponse(int status, String error, String message, String timestamp) {}

    // =========================================================================
    // 1. POST /api/rag/documents/text  --  Ingest raw text
    // =========================================================================

    @PostMapping("/documents/text")
    public ResponseEntity<DocumentInfo> ingestText(@RequestBody TextIngestionRequest request) {
        logger.info("POST /api/rag/documents/text  title={} contentLength={}",
                request.title(),
                request.content() == null ? 0 : request.content().length());

        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("Title must not be empty");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("Content must not be empty");
        }

        try {
            String docId = UUID.randomUUID().toString();
            RAGKnowledgeBaseApp.DocumentInfo result = app.ingestText(docId, request.title(), request.content());
            DocumentInfo info = new DocumentInfo(
                    result.id(), result.filename(), result.chunks(),
                    result.totalChars(), result.ingestedAt().toString());
            return ResponseEntity.status(HttpStatus.CREATED).body(info);
        } catch (Exception ex) {
            logger.error("Text ingestion failed: {}", ex.getMessage(), ex);
            throw new RAGProcessingException("Failed to ingest text: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 2. POST /api/rag/documents/upload  --  Upload a file (multipart)
    // =========================================================================

    @PostMapping("/documents/upload")
    public ResponseEntity<DocumentInfo> uploadFile(@RequestParam("file") MultipartFile file) {
        logger.info("POST /api/rag/documents/upload  filename={} size={}",
                file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("rag-upload-", "-" + file.getOriginalFilename());
            file.transferTo(tempFile.toFile());

            RAGKnowledgeBaseApp.DocumentInfo result = app.ingestFile(tempFile);
            DocumentInfo info = new DocumentInfo(
                    result.id(), result.filename(), result.chunks(),
                    result.totalChars(), result.ingestedAt().toString());
            return ResponseEntity.status(HttpStatus.CREATED).body(info);
        } catch (IOException ex) {
            logger.error("File upload I/O error: {}", ex.getMessage(), ex);
            throw new RAGProcessingException("Failed to process uploaded file: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            logger.error("File ingestion failed: {}", ex.getMessage(), ex);
            throw new RAGProcessingException("Failed to ingest file: " + ex.getMessage(), ex);
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    // =========================================================================
    // 3. GET /api/rag/documents  --  List all documents
    // =========================================================================

    @GetMapping("/documents")
    public ResponseEntity<DocumentListResponse> listDocuments() {
        logger.info("GET /api/rag/documents");

        try {
            List<RAGKnowledgeBaseApp.DocumentInfo> docs = app.getDocuments();
            List<DocumentInfo> infos = docs.stream()
                    .map(d -> new DocumentInfo(d.id(), d.filename(), d.chunks(),
                            d.totalChars(), d.ingestedAt().toString()))
                    .toList();
            int totalChunks = infos.stream().mapToInt(DocumentInfo::chunks).sum();
            return ResponseEntity.ok(new DocumentListResponse(infos, totalChunks));
        } catch (Exception ex) {
            logger.error("Failed to list documents: {}", ex.getMessage(), ex);
            throw new RAGProcessingException("Failed to list documents: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 4. DELETE /api/rag/documents/{docId}  --  Delete a document
    // =========================================================================

    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String docId) {
        logger.info("DELETE /api/rag/documents/{}", docId);

        try {
            boolean deleted = app.deleteDocument(docId);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.noContent().build();
        } catch (Exception ex) {
            logger.error("Failed to delete document {}: {}", docId, ex.getMessage(), ex);
            throw new RAGProcessingException("Failed to delete document: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 5. POST /api/rag/query  --  Ask a question (main RAG endpoint)
    // =========================================================================

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? UUID.randomUUID().toString()
                : request.sessionId();

        logger.info("POST /api/rag/query  sessionId={} questionLength={}",
                sessionId, request.question() == null ? 0 : request.question().length());

        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("Question must not be empty");
        }

        try {
            RAGKnowledgeBaseApp.RAGResponse result = app.query(sessionId, request.question());

            List<Citation> citations = result.citations().stream()
                    .map(c -> new Citation(c.sourceId(), c.sourceId(), c.passage(), c.relevance()))
                    .toList();

            QueryResponse response = new QueryResponse(
                    sessionId, result.answer(), citations,
                    result.confidence(), result.analysisNotes(), result.durationMs());
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            logger.error("Query processing failed for session {}: {}", sessionId, ex.getMessage(), ex);
            throw new RAGProcessingException("Failed to process query: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 6. GET /api/rag/conversations/{sessionId}  --  Conversation history
    // =========================================================================

    @GetMapping("/conversations/{sessionId}")
    public ResponseEntity<ConversationResponse> getConversation(@PathVariable String sessionId) {
        logger.info("GET /api/rag/conversations/{}", sessionId);

        try {
            List<RAGKnowledgeBaseApp.ConversationMessage> messages = app.getConversation(sessionId);
            if (messages == null || messages.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            List<ConversationEntry> entries = messages.stream()
                    .map(m -> new ConversationEntry(m.role(), m.content(), m.timestamp().toString()))
                    .toList();
            return ResponseEntity.ok(new ConversationResponse(sessionId, entries));
        } catch (Exception ex) {
            logger.error("Failed to retrieve conversation {}: {}", sessionId, ex.getMessage(), ex);
            throw new RAGProcessingException(
                    "Failed to retrieve conversation: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 7. GET /api/rag/stats  --  System statistics
    // =========================================================================

    @GetMapping("/stats")
    public ResponseEntity<StatsResponse> getStats() {
        logger.info("GET /api/rag/stats");

        try {
            Map<String, Object> stats = app.getStats();
            StatsResponse response = new StatsResponse(
                    (int) stats.getOrDefault("documentCount", 0),
                    (int) stats.getOrDefault("totalChunks", 0),
                    (long) stats.getOrDefault("queriesProcessed", 0L),
                    0L,
                    String.valueOf(stats.getOrDefault("activeSessions", 0)));
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            logger.error("Failed to retrieve stats: {}", ex.getMessage(), ex);
            throw new RAGProcessingException("Failed to retrieve stats: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // Exception handling
    // =========================================================================

    public static class RAGProcessingException extends RuntimeException {
        public RAGProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        logger.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                400, "Bad Request", ex.getMessage(), Instant.now().toString()));
    }

    @ExceptionHandler(RAGProcessingException.class)
    public ResponseEntity<ErrorResponse> handleProcessingError(RAGProcessingException ex) {
        logger.error("Processing error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                500, "Internal Server Error", ex.getMessage(), Instant.now().toString()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericError(Exception ex) {
        logger.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                500, "Internal Server Error",
                "An unexpected error occurred. Please try again later.",
                Instant.now().toString()));
    }
}
