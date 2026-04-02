package ai.intelliswarm.swarmai.examples.customersupportapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for the Customer Support application.
 *
 * Exposes five endpoints for chat, conversation history, product catalog,
 * order management, and support ticket retrieval.  All AI-related business
 * logic is delegated to {@link CustomerSupportApp}; this class is
 * responsible only for HTTP plumbing, request validation, and logging.
 *
 * The controller is conditionally loaded: it starts only when
 * {@code swarmai.customer-support.enabled=true} is set, so it will not
 * interfere with other example workflows.
 */
@RestController
@RequestMapping("/api/support")
@ConditionalOnProperty(prefix = "swarmai.customer-support", name = "enabled", havingValue = "true")
public class CustomerSupportController {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSupportController.class);

    private final CustomerSupportApp app;

    public CustomerSupportController(CustomerSupportApp app) {
        this.app = app;
        logger.info("CustomerSupportController initialized -- endpoints available at /api/support");
    }

    // =========================================================================
    // DTOs (Java records)
    // =========================================================================

    /** Inbound chat message. {@code sessionId} may be null for a new conversation. */
    public record ChatRequest(String sessionId, String message) {}

    /** Outbound chat response including the resolved category and session identifier. */
    public record ChatResponseDto(String sessionId, String response, String category, String timestamp) {}

    /** Full conversation history for a given session. */
    public record ConversationDto(String sessionId, List<MessageDto> messages) {}

    /** A single message inside a conversation. */
    public record MessageDto(String role, String content, String timestamp) {}

    /** Inbound order placement request. */
    public record OrderRequest(String customerId, String productId, int quantity) {}

    /** Outbound order confirmation. */
    public record OrderDto(String orderId, String customerId, String productId,
                           int quantity, double total, String status, String createdAt) {}

    /** Outbound support ticket summary. */
    public record TicketDto(String ticketId, String customerId, String category,
                            String subject, String description, String status, String createdAt) {}

    /** Outbound product details. */
    public record ProductDto(String id, String name, String category,
                             double price, String description, int stock) {}

    /** Generic error envelope returned by the exception handler. */
    public record ErrorResponse(int status, String error, String message, String timestamp) {}

    // =========================================================================
    // 1. POST /api/support/chat
    // =========================================================================

    /**
     * Send a message to the AI customer-support agent.
     *
     * If {@code sessionId} is null or blank a new conversation session is
     * created automatically.  The response includes the AI's reply together
     * with the classified support category.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(@RequestBody ChatRequest request) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? UUID.randomUUID().toString()
                : request.sessionId();

        logger.info("POST /api/support/chat  sessionId={} messageLength={}",
                sessionId, request.message() == null ? 0 : request.message().length());

        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }

        try {
            CustomerSupportApp.ChatResult result = app.chat(sessionId, request.message());

            ChatResponseDto dto = new ChatResponseDto(
                    sessionId,
                    result.response(),
                    result.category(),
                    Instant.now().toString()
            );
            return ResponseEntity.ok(dto);

        } catch (Exception ex) {
            logger.error("Chat processing failed for session {}: {}", sessionId, ex.getMessage(), ex);
            throw new SupportProcessingException("Failed to process chat message: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 2. GET /api/support/conversations/{sessionId}
    // =========================================================================

    /**
     * Retrieve the full conversation history for the given session.
     */
    @GetMapping("/conversations/{sessionId}")
    public ResponseEntity<ConversationDto> getConversation(@PathVariable String sessionId) {
        logger.info("GET /api/support/conversations/{}", sessionId);

        try {
            List<CustomerSupportApp.ConversationMessage> messages = app.getConversation(sessionId);

            if (messages == null || messages.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            List<MessageDto> messageDtos = messages.stream()
                    .map(m -> new MessageDto(m.role(), m.content(), m.timestamp()))
                    .toList();

            return ResponseEntity.ok(new ConversationDto(sessionId, messageDtos));

        } catch (Exception ex) {
            logger.error("Failed to retrieve conversation {}: {}", sessionId, ex.getMessage(), ex);
            throw new SupportProcessingException(
                    "Failed to retrieve conversation: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 3. GET /api/support/products
    // =========================================================================

    /**
     * Return the full product catalog.
     */
    @GetMapping("/products")
    public ResponseEntity<Map<String, List<ProductDto>>> getProducts() {
        logger.info("GET /api/support/products");

        try {
            List<CustomerSupportApp.Product> products = app.getProducts();

            List<ProductDto> productDtos = products.stream()
                    .map(p -> new ProductDto(p.id(), p.name(), p.category(),
                            p.price(), p.description(), p.stock()))
                    .toList();

            return ResponseEntity.ok(Map.of("products", productDtos));

        } catch (Exception ex) {
            logger.error("Failed to retrieve products: {}", ex.getMessage(), ex);
            throw new SupportProcessingException(
                    "Failed to retrieve products: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 4. POST /api/support/orders
    // =========================================================================

    /**
     * Place a new order.  This is a direct operation (no AI involved) that
     * delegates to the application's order-creation logic.
     */
    @PostMapping("/orders")
    public ResponseEntity<OrderDto> createOrder(@RequestBody OrderRequest request) {
        logger.info("POST /api/support/orders  customerId={} productId={} qty={}",
                request.customerId(), request.productId(), request.quantity());

        if (request.customerId() == null || request.customerId().isBlank()) {
            throw new IllegalArgumentException("customerId must not be empty");
        }
        if (request.productId() == null || request.productId().isBlank()) {
            throw new IllegalArgumentException("productId must not be empty");
        }
        if (request.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }

        try {
            CustomerSupportApp.OrderResult order = app.createOrder(
                    request.customerId(), request.productId(), request.quantity());

            OrderDto dto = new OrderDto(
                    order.orderId(),
                    order.customerId(),
                    order.productId(),
                    order.quantity(),
                    order.total(),
                    order.status(),
                    order.createdAt()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);

        } catch (IllegalArgumentException ex) {
            throw ex; // re-throw so @ExceptionHandler picks it up as 400
        } catch (Exception ex) {
            logger.error("Failed to create order: {}", ex.getMessage(), ex);
            throw new SupportProcessingException(
                    "Failed to create order: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // 5. GET /api/support/tickets?customerId=xxx
    // =========================================================================

    /**
     * Retrieve support tickets, optionally filtered by customer ID.
     */
    @GetMapping("/tickets")
    public ResponseEntity<Map<String, List<TicketDto>>> getTickets(
            @RequestParam(required = false) String customerId) {

        logger.info("GET /api/support/tickets  customerId={}", customerId);

        try {
            List<CustomerSupportApp.Ticket> tickets = app.getTickets(customerId);

            List<TicketDto> ticketDtos = tickets.stream()
                    .map(t -> new TicketDto(t.ticketId(), t.customerId(), t.category(),
                            t.subject(), t.description(), t.status(), t.createdAt()))
                    .toList();

            return ResponseEntity.ok(Map.of("tickets", ticketDtos));

        } catch (Exception ex) {
            logger.error("Failed to retrieve tickets: {}", ex.getMessage(), ex);
            throw new SupportProcessingException(
                    "Failed to retrieve tickets: " + ex.getMessage(), ex);
        }
    }

    // =========================================================================
    // Exception handling
    // =========================================================================

    /** Thrown when an internal processing error occurs in the support pipeline. */
    public static class SupportProcessingException extends RuntimeException {
        public SupportProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        logger.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                400, "Bad Request", ex.getMessage(), Instant.now().toString()));
    }

    @ExceptionHandler(SupportProcessingException.class)
    public ResponseEntity<ErrorResponse> handleProcessingError(SupportProcessingException ex) {
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
