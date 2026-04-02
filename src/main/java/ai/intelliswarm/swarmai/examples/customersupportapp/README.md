# Customer Support Application

A complete, production-style customer support system built with SwarmAI. Unlike the other examples which are single-run CLI workflows, this is a persistent REST API service with conversation history, product catalog, order management, and intelligent agent routing.

## Architecture

```
  +---------------------------+
  |  CustomerSupportController|     REST API (5 endpoints)
  |  POST /chat               |     POST /orders
  |  GET  /conversations/{id} |     GET  /tickets
  |  GET  /products           |
  +------------+--------------+
               |
  +------------v--------------+
  |    CustomerSupportApp     |     Business logic + AI orchestration
  +--+----------+----------+--+
     |          |          |
  +--v---+  +--v------+  +v---------+
  |Swarm |  |Product  |  |Order &   |
  |Graph |  |Store    |  |Ticket    |
  |      |  |(memory) |  |Stores    |
  +--+---+  +---------+  +----------+
     |
  [START] -> [classify] --(conditional)--> [billing|technical|account|general]
                                                     |
                                               [satisfaction]
                                                /          \
                                             [END]      [escalate] -> [END]
```

Custom tools: `ProductLookup` (catalog search, stock check), `OrderManagement` (place orders, refunds), `TicketCreation` (create/update/escalate tickets), `RefundProcessor`, `AccountVerifier`.

## What You Will Learn

- Building a persistent REST API on top of SwarmAI (not just CLI one-shot workflows)
- Session-based conversation management with multi-turn context
- SwarmGraph conditional routing for intelligent agent handoff
- Custom tool creation for domain-specific operations (products, orders, tickets)
- In-memory data stores for a self-contained demo
- Conditional bean loading with `@ConditionalOnProperty` to isolate the app
- Error handling patterns for production REST services
- How SwarmAI compares to LangChain4j's customer-support-agent example

## Prerequisites

- Java 21+
- Maven 3.9+
- Ollama running locally with `mistral:latest` pulled
- SwarmAI framework libraries installed to local Maven repository

## Quick Start

```bash
# Start the service (enables the REST API via the customer-support-app profile)
./scripts/run.sh customer-support-app

# In another terminal, try these curl commands:

# Chat with the AI (new session -- sessionId is generated automatically)
curl -X POST http://localhost:8080/api/support/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "I need help with my billing"}'

# Continue the conversation (use the sessionId from the previous response)
curl -X POST http://localhost:8080/api/support/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "abc-123", "message": "Can you show me my orders?"}'

# Browse the product catalog
curl http://localhost:8080/api/support/products

# View conversation history
curl http://localhost:8080/api/support/conversations/abc-123

# Place an order
curl -X POST http://localhost:8080/api/support/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "cust-001", "productId": "prod-001", "quantity": 2}'

# View support tickets for a customer
curl http://localhost:8080/api/support/tickets?customerId=cust-001
```

## API Reference

| Method | Path                              | Request Body                                          | Response                                                       |
|--------|-----------------------------------|-------------------------------------------------------|----------------------------------------------------------------|
| POST   | `/api/support/chat`               | `{ "sessionId": "optional", "message": "..." }`      | `{ "sessionId", "response", "category", "timestamp" }`        |
| GET    | `/api/support/conversations/{id}` | --                                                    | `{ "sessionId", "messages": [{ "role", "content", "timestamp" }] }` |
| GET    | `/api/support/products`           | --                                                    | `{ "products": [{ "id", "name", "category", "price", ... }] }`|
| POST   | `/api/support/orders`             | `{ "customerId", "productId", "quantity" }`           | `{ "orderId", "customerId", "total", "status", ... }`         |
| GET    | `/api/support/tickets`            | Query param: `?customerId=xxx` (optional)             | `{ "tickets": [{ "ticketId", "category", "subject", ... }] }` |

All error responses use a consistent envelope: `{ "status": 400, "error": "Bad Request", "message": "...", "timestamp": "..." }`.

## How It Works

When a user sends a chat message, the controller assigns or reuses a session ID and delegates to `CustomerSupportApp`. The app feeds the message into a SwarmGraph pipeline where a Triage Agent classifies the query into one of four categories: BILLING, TECHNICAL, ACCOUNT, or GENERAL. A conditional edge routes the conversation to the appropriate specialist agent, each of which has access to domain-specific tools (product lookup, order management, ticket creation). The specialist generates a response, which then passes through a satisfaction quality gate. If the quality gate determines the response is insufficient, the conversation is escalated to a Senior Support Director agent who has full authority to override policies and offer compensation. The final response, along with the detected category, is returned to the caller. Every message is persisted in an in-memory conversation store so that subsequent requests with the same session ID carry full context. The product catalog, order history, and support tickets are likewise maintained in memory, giving the AI agents real data to work with during conversations.

## Custom Tools

| Tool             | Used By                 | Description                                              |
|------------------|-------------------------|----------------------------------------------------------|
| ProductLookup    | All specialists         | Search the product catalog, check stock, get details     |
| OrderManagement  | Billing, Account        | Place orders, check status, process refunds              |
| TicketCreation   | All specialists         | Create support tickets, update status, escalate          |
| RefundProcessor  | Billing, Senior         | Calculate refund amounts, apply credits                  |
| AccountVerifier  | Account, Senior         | Verify customer identity, check permissions              |

## Key Code

The controller uses `@ConditionalOnProperty` so it only activates when the customer support app is explicitly enabled, preventing it from starting during other example runs:

```java
@RestController
@RequestMapping("/api/support")
@ConditionalOnProperty(prefix = "swarmai.customer-support",
                       name = "enabled", havingValue = "true")
public class CustomerSupportController {

    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(@RequestBody ChatRequest request) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? UUID.randomUUID().toString()
                : request.sessionId();
        CustomerSupportApp.ChatResult result = app.chat(sessionId, request.message());
        return ResponseEntity.ok(new ChatResponseDto(
                sessionId, result.response(), result.category(), Instant.now().toString()));
    }
}
```

## Comparison to LangChain4j customer-support-agent

The LangChain4j customer support example is an excellent single-agent chatbot. SwarmAI builds on that pattern with multi-agent orchestration:

| Aspect                    | LangChain4j                     | SwarmAI Customer Support App        |
|---------------------------|----------------------------------|--------------------------------------|
| Agent count               | 1 (monolithic)                   | 6+ (triage, 4 specialists, senior)  |
| Routing                   | Manual if/else or prompt-based   | SwarmGraph conditional edges         |
| Escalation                | Not built-in                     | Quality gate with automatic escalation|
| Tool permissions           | All tools available to all agents| Per-agent tool restrictions          |
| Budget tracking            | Not available                    | Token and cost tracking per session  |
| Observability              | Basic logging                    | Decision tracing, event replay       |
| Conversation management   | Memory-based                     | Session-based with full history API  |
| Data stores               | External DB required             | Self-contained in-memory stores      |

## Customization

- **Add products:** Edit `CustomerSupportApp` and add entries to the in-memory product store (ID, name, category, price, description, stock).
- **Change routing:** Modify the `addConditionalEdge` after the "classify" node to add categories or reroute specialists.
- **Add a specialist:** Create a new SwarmGraph node (e.g., "shipping"), wire it from the conditional edge, connect to the satisfaction gate.
- **Connect a real database:** Replace `ConcurrentHashMap`-based stores with Spring Data repositories. The controller interface stays the same.
- **Adjust the quality gate:** Tune the satisfaction node's prompt or scoring criteria to change escalation sensitivity.
- **Enable for production:** Set `swarmai.customer-support.enabled=true` in `application.yml` or as an environment variable.
