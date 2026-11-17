# EchoMind SaaS Support Agent Design

- Date: 2026-11-17
- Status: Approved design baseline for specification review
- Product baseline: `XPeng-Coding/EchoMind-SaaS-Platform` at `6379e2ad63b661404b61059c0c3c152627721b7a`
- Reference implementation: `kaiyue-ai/EchoMind` at `07190abe86f67db4184c027e7966dacaab6f9686`
- Target working repository: `E:\EchoMind\echomind-saas-agent`

## 1. Summary

EchoMind SaaS Support Agent is a multi-tenant customer support application for B2B SaaS subscription and billing scenarios. It combines a Java/Spring Boot business backend, a real RAG pipeline, a bounded multi-agent runtime, a human-support ticket workflow, and reproducible quality evaluation.

The product uses the XPeng EchoMind business shape as its starting point. It borrows narrowly selected runtime patterns from Kaiyue EchoMind: explicit agent execution stages, DAG coordination for complex investigations, asynchronous workers, distributed locking where justified, and test organization. It does not import the complete Kaiyue platform.

The first release is a modular monolith with one Spring Boot deployment unit. Module seams are enforced in code so later extraction remains possible without paying the operational cost of microservices in the initial version.

## 2. Goals

The release must demonstrate all of the following:

1. Java/Spring Boot business modeling for accounts, plans, subscriptions, invoices, support conversations, tickets, approvals, and tenant access.
2. Hybrid RAG with immutable document versions, ACL-aware retrieval, semantic embeddings, lexical retrieval, reranking, and verifiable citations.
3. Bounded multi-agent orchestration that routes simple requests cheaply and reserves DAG execution for complex investigations.
4. A working human-escalation path with idempotent ticket creation, queue assignment, SLA tracking, and approval boundaries.
5. Real tenant isolation across HTTP, asynchronous messages, relational data, vector indexes, caches, object storage, and traces.
6. Repeatable evaluation for intent quality, retrieval quality, citation support, handoff behavior, latency, and cost.
7. A minimal browser demonstration surface for chat, citations, business facts, tickets, knowledge ingestion, and evaluation results.

## 3. Non-Goals

The first release does not implement:

- Card storage, payment capture, refunds, or monetary settlement.
- Autonomous cancellation, subscription changes, or refunds.
- A complete CRM or help-desk product.
- Real-time voice support.
- Third-party billing webhooks as a required dependency.
- Microservice deployment, Kubernetes orchestration, or multi-region operation.
- Claims of production capacity before load testing is completed.

## 4. Users And Roles

| Actor | Responsibility |
| --- | --- |
| Tenant customer | Ask subscription, entitlement, invoice, and product questions; create tickets; request human support |
| Support agent | Review queued tickets, claim work, inspect evidence, approve or reject sensitive requests, resolve tickets |
| Tenant administrator | Manage users, customer mappings, knowledge documents, quotas, and policies |
| Platform operator | Monitor system health, inspect traces, run evaluation, and manage degraded dependencies |

An actor may belong to multiple tenants. The active tenant is selected explicitly and every operation verifies membership independently of request body fields.

## 5. Supported Business Scenarios

The initial business catalog contains:

1. Subscription status and renewal-date explanation.
2. Plan, seat, and entitlement explanation.
3. Invoice lookup and billing-failure explanation.
4. Product documentation questions with cited answers.
5. Technical troubleshooting grounded in versioned documentation.
6. Ticket creation when the request cannot be resolved automatically.
7. Human escalation for user request, low confidence, repeated failure, or high-risk action.
8. Approval requests for refund, cancellation, or plan change without automatic execution.

## 6. Architecture

### 6.1 Deployment Shape

The application is one Spring Boot process backed by external infrastructure:

- MySQL 8 as the relational source of truth.
- Redis 7 for short-lived conversation context, rate-limit state, leases, and cache.
- Milvus as the dense-vector index.
- RabbitMQ for durable asynchronous work and transactional outbox delivery.
- Object storage for uploaded files and generated artifacts.
- OpenTelemetry Collector and Prometheus for traces and metrics.

The Vue frontend is a thin demonstration client and must not hold authoritative business state.

### 6.2 Module Layout

```text
com.echomind
├── api
├── identity
├── commerce
├── support
├── ticket
├── knowledge
├── agent
├── evaluation
└── platform
```

Responsibilities:

| Module | Responsibility |
| --- | --- |
| `api` | HTTP, SSE, validation, authentication entry, response mapping |
| `identity` | Tenant membership, roles, access scopes, policy decisions |
| `commerce` | Read model for account, plan, entitlement, subscription, invoice, and payment attempt |
| `support` | Conversation, message, intent, support turn, and agent run lifecycle |
| `ticket` | Ticket state, queue, assignment, SLA, approval request, and audit history |
| `knowledge` | Document lifecycle, ACL, parsing, chunks, indexing, hybrid retrieval, reranking, citations |
| `agent` | Model adapter, specialists, tool authorization, execution pipeline, DAG coordinator |
| `evaluation` | Golden datasets, offline evaluation, metrics, baseline comparison, release gate |
| `platform` | Persistence, outbox/inbox, RabbitMQ, Redis, object storage, observability, clocks, IDs |

### 6.3 Dependency Rule

The allowed dependency direction is:

```text
api
 ├── support
 │    └── agent
 │         ├── commerce public interface
 │         ├── knowledge public interface
 │         └── ticket public interface
 ├── knowledge
 ├── ticket
 ├── commerce
 └── identity

all modules → platform infrastructure ports
```

Rules:

- No module reads another module's tables.
- Cross-module calls use public application interfaces and typed commands/results.
- Infrastructure adapters implement ports owned by the consuming module.
- `identity` decisions are consumed by all resource-owning modules; `identity` does not depend on those modules.
- `evaluation` invokes the same support interfaces used by online requests but cannot write production business facts.
- ArchUnit tests enforce dependency direction and table-access ownership.

## 7. Deep Module Interfaces

The public interfaces remain small and hide internal workflow complexity.

### 7.1 Commerce

```java
public interface CommerceQuery {
    CustomerCommercialSnapshot load(
        TenantId tenantId,
        CustomerId customerId,
        CommerceQueryOptions options
    );
}
```

The result contains the account, active plan, relevant entitlements, current subscription, recent invoices, and recent failed payment attempts. The first adapter reads the local MySQL commerce tables. A future external SaaS adapter may implement the same interface.

### 7.2 Knowledge

```java
public interface KnowledgeIngestion {
    DocumentVersion ingest(KnowledgeIngestCommand command, ActorScope actor);
}

public interface KnowledgeRetrieval {
    EvidenceSet retrieve(KnowledgeQuery query, ActorScope actor);
}
```

The ingestion implementation owns parsing, checksum deduplication, immutable version creation, chunking, embedding, indexing, publication, retirement, and repair.

The retrieval implementation owns ACL filtering, lexical and vector retrieval, result fusion, reranking, evidence thresholds, and citation construction.

### 7.3 Agent Runtime

```java
public interface SupportTurn {
    SupportTurnResult handle(SupportTurnCommand command, ActorScope actor);
}

public interface InvestigationCoordinator {
    RunId submit(InvestigationCommand command, ActorScope actor);
}
```

`SupportTurn` owns the online pipeline. `InvestigationCoordinator` owns asynchronous DAG execution. Callers do not choose tools, prompts, or intermediate stages.

### 7.4 Ticket

```java
public interface TicketCommands {
    TicketRef create(CreateTicketCommand command, ActorScope actor);
    void claim(TicketId ticketId, ActorScope actor, long expectedVersion);
    void resolve(TicketId ticketId, ResolutionCommand command, ActorScope actor);
}
```

### 7.5 Tool Policy

```java
public interface ToolPolicy {
    ToolDecision authorize(ToolCallRequest request, ActorScope actor, RunPolicy policy);
}
```

Models may propose tool calls. Only this interface can authorize them.

## 8. Domain Model

All tenant-owned tables include `tenant_id` as part of the primary access path and every repository query requires a tenant predicate.

### 8.1 Identity

- `tenant`
- `app_user`
- `tenant_membership`
- `role_binding`
- `customer_mapping`

### 8.2 Commerce

- `customer_account`
- `plan`
- `plan_entitlement`
- `subscription`
- `invoice`
- `payment_attempt`

Commerce records are business facts. The model cannot invent or patch them.

### 8.3 Support

- `support_conversation`
- `support_message`
- `support_intent`
- `agent_run`
- `agent_tool_call`
- `memory_fact`

`memory_fact` is advisory, revocable, scoped to one tenant/customer, and always stores its source message and expiry.

### 8.4 Ticket

- `ticket`
- `ticket_event`
- `ticket_assignment`
- `approval_request`

### 8.5 Knowledge

- `knowledge_space`
- `knowledge_document`
- `knowledge_document_version`
- `knowledge_chunk`
- `knowledge_acl`

### 8.6 Platform And Evaluation

- `outbox_event`
- `inbox_event`
- `idempotency_record`
- `evaluation_suite`
- `evaluation_case`
- `evaluation_run`
- `evaluation_result`

## 9. Core Support Flow

1. Authenticate the caller and resolve tenant membership.
2. Load the conversation with a tenant-scoped query.
3. Persist the user message and create one `agent_run` in a local transaction.
4. Classify deterministic safety and risk conditions before model-based intent classification.
5. Classify intent as plan/entitlement, subscription, billing, technical support, ticket request, human handoff, sensitive change, or unrelated.
6. Route to one specialist or a bounded DAG.
7. Load business facts and evidence in parallel where safe.
8. Generate a structured answer containing facts, caveats, citations, proposed actions, and handoff state.
9. Verify permissions, evidence, citations, and action risk.
10. Return the answer, ask for clarification, create a ticket, or create an approval request.
11. Persist the final run state, tool trace, usage, and audit event.

The browser receives progress through SSE. SSE events are idempotent and contain a stable `run_id`, monotonically increasing `sequence`, stage, and terminal status.

## 10. Agent Runtime

### 10.1 Specialists

| Specialist | Allowed capability |
| --- | --- |
| `SubscriptionAgent` | Read subscription, plan, entitlement, and renewal context |
| `BillingAgent` | Read invoice and payment-attempt context; explain known failure categories |
| `TechnicalAgent` | Retrieve product documentation and produce cited troubleshooting steps |
| `TicketAgent` | Prepare and create support tickets within policy |
| `Verifier` | Check evidence, citations, policy compliance, and requested write action |

### 10.2 Online Pipeline

The online pipeline has a hard budget:

- At most five main stages.
- At most three tool calls.
- At most one model retry.
- At most 40 seconds of model time.
- At most 15 seconds of retrieval time.
- At most one DAG submission after the online response.

The default stages are:

```text
context
→ route
→ evidence gather
→ generate
→ verify and decide
```

Requests exceeding budget return a bounded answer with missing-information guidance or a ticket.

### 10.3 Complex Investigation DAG

DAG execution is reserved for complex cases such as repeated renewal failure with conflicting entitlement state.

```text
collect_context
→ parallel[query_commercial_facts, retrieve_knowledge, load_recent_ticket_events]
→ aggregate_evidence
→ analyze_root_cause
→ verify
→ write_ticket_update_or_human_escalation
```

Each node declares:

- Maximum attempts.
- Timeout.
- Token and cost budget.
- Required input type.
- Produced artifact type.
- Whether it is cancellable.

The coordinator persists node state and artifacts. A canceled run stops further nodes, attempts to cancel in-flight model calls, waits for tool completion or marks it indeterminate, and retains facts already committed.

## 11. Tool Authorization

The first release exposes two business tools plus one escalation command:

| Tool | Operation type | Policy |
| --- | --- | --- |
| `query_subscription_order` | Read | Tenant and customer scope; field-level redaction |
| `create_ticket` | Write | Membership, tenant quota, idempotency key, rate limit |
| `request_human_review` | Workflow write | Creates approval request; never executes the underlying commercial action |

The model cannot provide trusted tenant, actor, role, or customer identity. Those values come from `ActorScope`.

Authorization checks:

1. Actor belongs to the selected tenant.
2. Actor is allowed to access the requested customer account.
3. Specialist is allowed to invoke this tool.
4. Run still has sufficient action and cost budget.
5. Idempotency key is unused or matches the same command payload.
6. Sensitive actions require human approval.

## 12. Ticket And Human Handoff

Ticket state:

```text
OPEN → QUEUED → CLAIMED → RESOLVED → CLOSED
  \       \         \
   +-------+---------+→ REOPENED
```

Rules:

- `OPEN` is created and committed with the initial event.
- Queue publication occurs through the outbox.
- Claim uses optimistic locking with `expected_version`.
- A ticket can be claimed by one actor only.
- SLA deadlines are stored as absolute timestamps.
- Every state transition writes an immutable `ticket_event`.
- A reopening transition records the actor and reason.

Human handoff occurs when:

- The user explicitly requests it.
- Intent or retrieval confidence is below the configured threshold.
- Business facts are unavailable and guessing would be unsafe.
- The user asks to execute a sensitive commercial action.
- Verification fails for an answer that would otherwise be sent automatically.

The Agent creates a structured ticket summary containing conversation references, relevant order/subscription references, evidence links, attempted tools, and recommended next action.

## 13. RAG Pipeline

### 13.1 Document Model

- A logical document has immutable versions.
- One version may be `DRAFT`, `INDEXING`, `PUBLISHED`, `FAILED`, or `RETIRED`.
- Only one version is active for retrieval at a time.
- Chunks belong to a document version and preserve page, section, heading path, character range, checksum, and ACL.
- Deleting or retiring a document removes vector visibility and marks metadata unavailable before physical cleanup.

### 13.2 Ingestion

```text
upload
→ checksum deduplication
→ object storage
→ parse
→ normalize
→ chunk
→ generate embeddings
→ write MySQL chunks and Milvus vectors
→ verify counts and metadata
→ atomically activate version
```

Failures keep the previous active version available. Index verification checks tenant, document, version, chunk count, and embedding model version.

### 13.3 Retrieval

```text
authorize query
→ apply tenant/ACL filter
→ query rewrite
→ parallel lexical and vector retrieval
→ RRF fusion
→ rerank
→ evidence threshold
→ citation assembly
```

Implementation choices:

- MySQL FULLTEXT with the ngram parser provides lexical retrieval.
- Local `bge-small-zh-v1.5` ONNX embeddings are the default semantic model.
- Milvus stores dense vectors with tenant, knowledge-space, document-version, and ACL metadata.
- A reranker port has a real model adapter and a deterministic fallback adapter.
- Duplicate or conflicting document versions are not merged.

### 13.4 Grounding

Every cited statement references a chunk ID whose version is active at answer time. Citations display document title, version, page or section, and excerpt.

The verifier rejects automatic answer when:

- No evidence passes the threshold.
- A numeric business fact does not match the commerce snapshot.
- A citation does not support the associated claim.
- Retrieval used a retired document.
- The answer requests an operation beyond the run policy.

## 14. Memory

Memory is separated into four layers:

1. Current conversation, read from Redis and backed by MySQL.
2. Recent support facts extracted from messages into `memory_fact`.
3. Business facts queried from `commerce`; these are never stored as model memory.
4. Ticket history queried from `ticket`.

`memory_fact` rules:

- Tenant and customer scoped.
- Source message reference required.
- Confidence and expiry recorded.
- User or administrator can revoke it.
- Memory retrieval cannot override commerce facts.
- Sensitive fields are redacted before model use.

## 15. Tenant Isolation And Security

Authentication produces an `ActorScope` containing user ID, tenant ID, roles, permitted customer IDs, and request ID.

Isolation rules:

- MySQL repositories always include `tenant_id`.
- Cache keys include tenant ID and resource ID.
- RabbitMQ messages carry tenant ID and are rejected if the worker context mismatches.
- Milvus filters include tenant ID and ACL groups.
- Object-storage keys are namespaced by tenant.
- Traces and logs use structured redaction.
- Cross-tenant resource lookup returns not found after an authorization audit event.

RBAC roles:

| Capability | Customer | Support Agent | Tenant Admin |
| --- | --- | --- | --- |
| Chat about own account | Yes | Yes with customer permission | Yes with customer permission |
| Create ticket | Yes | Yes | Yes |
| Read tenant tickets | No | Yes | Yes |
| Claim queue ticket | No | Yes | Yes |
| Approve sensitive action | No | Yes with approval role | Yes |
| Manage knowledge | No | No by default | Yes |
| Manage quotas and users | No | No | Yes |

## 16. Consistency And Asynchronous Processing

Cross-module distributed transactions are not used.

Every asynchronous integration uses transactional outbox:

1. Commit local business state and outbox record atomically.
2. A relay publishes the event to RabbitMQ.
3. Consumers write an inbox record or enforce a business idempotency key.
4. Failed messages retry with bounded backoff and then enter a dead-letter queue.

Concurrency controls:

- Per-tenant request rate and token-bucket budget.
- Per-tenant maximum online runs and asynchronous investigations.
- Separate bounded worker pools for online chat, retrieval, ingestion, evaluation, and ticket queues.
- Rejection occurs before unbounded queue growth.
- Fair ordering rotates tenants when multiple tenants are waiting.
- High-risk and short online interactions have priority over bulk ingestion and analysis.

Run budget:

- Wall-clock deadline.
- Model-call count.
- Tool-call count.
- DAG node count and depth.
- Token budget.
- Monetary budget when provider pricing is configured.

Cancellation state is durable. Workers check it before each stage and before each tool call.

## 17. Failure Handling

| Failure | Required behavior |
| --- | --- |
| Commerce unavailable | Do not guess; return temporary unavailability or create a ticket |
| Milvus unavailable | Fall back to lexical retrieval and mark degraded evidence |
| Reranker unavailable | Use deterministic fusion ordering and mark degraded |
| Authentication unavailable | Reject request |
| RabbitMQ unavailable | Commit local state and outbox; show asynchronous work as pending |
| Duplicate message delivery | Deduplicate by event ID or idempotency key |
| Worker crash | Reassign after lease expiry |
| Model timeout | Retry once within budget, then constrain or escalate |
| Verifier failure | Fail closed for sensitive or low-evidence answers |
| Cross-tenant access attempt | Reject and emit security audit |
| Index version mismatch | Retire inconsistent version and retain prior published version |

No failure path silently converts an unknown business fact into a model-generated answer.

## 18. APIs

The versioned HTTP surface is:

```text
POST   /api/v1/chat
GET    /api/v1/chat/{runId}/stream
GET    /api/v1/conversations/{conversationId}

GET    /api/v1/customers/{customerId}/commercial-summary

POST   /api/v1/tickets
GET    /api/v1/tickets/{ticketId}
GET    /api/v1/tickets
POST   /api/v1/tickets/{ticketId}/claim
POST   /api/v1/tickets/{ticketId}/resolve
POST   /api/v1/tickets/{ticketId}/reopen

POST   /api/v1/knowledge-documents
POST   /api/v1/knowledge-documents/{documentId}/versions
POST   /api/v1/knowledge-documents/{documentId}/publish

POST   /api/v1/evaluations
GET    /api/v1/evaluations/{runId}

GET    /api/v1/runs/{runId}/trace
```

Every mutating API accepts or derives an idempotency key. Error responses include a stable code, request ID, retryability, and safe user message.

## 19. Frontend Scope

The minimal Vue interface contains:

1. Chat page with streamed status, business facts, citations, and handoff action.
2. Ticket queue and ticket detail with claim/resolve controls.
3. Knowledge document upload and version publication.
4. Evaluation page showing dataset version, metrics, failures, latency, and cost.
5. Tenant selector restricted to the authenticated user's memberships.

The frontend receives server-derived permissions. Hiding a button is not treated as an authorization control.

## 20. Observability

Every request and asynchronous run has a Trace ID and correlation ID.

Required spans:

- HTTP request.
- Tenant authorization.
- Conversation load.
- Intent and risk classification.
- Agent routing.
- Model call.
- Tool call.
- Retrieval and rerank.
- Database transaction.
- Outbox publish and consumer processing.
- Ticket transition.
- DAG node and run.

Required metrics:

- Request and stage latency percentiles.
- Agent-run completion, cancellation, timeout, and failure.
- Tool success, timeout, circuit-breaker state, and policy denial.
- Retrieval degradation and index mismatch.
- Ticket queue depth, claim conflict, SLA warning, and resolution time.
- Model tokens, estimated cost, and unavailable-usage count.

## 21. Evaluation And Quality Gates

### 21.1 Datasets

Evaluation assets are versioned and include:

- Intent and handoff cases.
- Subscription, invoice, and entitlement QA pairs.
- Knowledge retrieval cases with labeled chunk IDs.
- Citation support cases.
- Cross-tenant and unauthorized-tool security cases.
- Duplicate request and retry cases.
- Dependency-failure cases.

The initial acceptance dataset contains at least 40 intent/handoff cases, 30 retrieval cases, 20 end-to-end cases, and 15 security cases. These are minimum coverage requirements, not claimed results.

### 21.2 Metrics

| Metric | Definition |
| --- | --- |
| Intent Macro-F1 | Macro average of per-class F1; per-class recall is also reported |
| Recall@K | Relevant chunk appears in the first K retrieved chunks |
| MRR | Reciprocal rank of the first relevant chunk |
| Citation Support Rate | Fraction of factual answer claims backed by cited active evidence |
| Handoff Recall | Correct handoff cases divided by all cases requiring handoff |
| False Escalation Rate | Incorrect handoffs divided by cases solvable automatically |
| Resolution Rate | Cases completed without human intervention inside supported scope |
| P50/P95 Latency | End-to-end and per-stage latency |
| Cost Per Resolved Case | Model, rerank, retrieval, and tool cost divided by resolved cases |

Missing provider usage is stored as `unavailable`; the report must not fabricate a complete cost ranking.

### 21.3 Hard Release Gates

The release is blocked if any of these occurs:

- Cross-tenant read or write succeeds.
- An unauthorized refund, cancellation, or plan change executes.
- A duplicate idempotency key creates multiple tickets or approvals.
- Commerce unavailability produces invented business facts.
- Sensitive action executes without an authorized human approval.
- Evaluation runs on a different code or dataset version than the one reported.

Quality metrics use a committed baseline. Absolute target values are recorded only after the first reproducible benchmark.

## 22. Testing Strategy

### 22.1 Unit Tests

- Intent and risk rules.
- Route selection.
- Tool-policy decisions.
- Ticket state transitions.
- DAG graph validation and budget enforcement.
- Citation matching.
- Tenant-key generation.

### 22.2 Architecture Tests

ArchUnit verifies:

- Module dependency direction.
- No cross-module repository access.
- No infrastructure dependency in domain policy.
- No controller calling persistence directly.

### 22.3 Integration Tests

Testcontainers covers:

- MySQL migrations and tenant-scoped repositories.
- Redis conversation and lock behavior.
- RabbitMQ outbox, inbox, retry, and dead-letter behavior.
- Ticket claim concurrency.
- SSE event ordering and idempotency.

Milvus integration runs through a dedicated Docker profile. Unit and normal CI runs use a deterministic in-memory vector adapter.

### 22.4 System Tests

A deterministic fake model exercises:

- Happy-path billing explanation.
- Technical answer with citations.
- Missing evidence and clarification.
- Commerce timeout and ticket creation.
- Sensitive-action approval.
- Explicit human handoff.
- Duplicate and concurrent requests.

### 22.5 Evaluation In CI

```text
static checks + ArchUnit
→ unit tests
→ Testcontainers integration tests
→ fake-model system tests
→ golden-set evaluation and baseline comparison
→ container build
```

Real-provider smoke tests run outside the required CI gate and never replace deterministic regression tests.

## 23. Deployment And Configuration

Docker Compose provides:

- Application.
- MySQL.
- Redis.
- RabbitMQ.
- Milvus and its dependencies.
- OpenTelemetry Collector.
- Prometheus.

Configuration is grouped by:

- Tenant quotas.
- Model providers.
- Embedding and rerank providers.
- Retrieval thresholds.
- Run budgets.
- Ticket SLA.
- Evaluation baselines.

Secrets are supplied through environment variables or a secret store and never committed.

## 24. Implementation Phases

### Phase 1: Baseline And Foundation

- Establish the new repository and module packages.
- Replace insecure request-body identity with `ActorScope`.
- Add MySQL migrations, tenant model, roles, and ArchUnit checks.
- Add outbox/inbox and idempotency infrastructure.

### Phase 2: Commerce And Tickets

- Implement account, plan, subscription, invoice, and payment-attempt read models.
- Implement ticket state machine, queue, claim, SLA, and approval request.
- Seed multiple tenants and representative SaaS customers.

### Phase 3: Support And Agent Runtime

- Implement the support-turn pipeline.
- Add specialist routing, run budgets, cancellation, and tool policy.
- Implement subscription/order query and ticket creation tools.

### Phase 4: RAG

- Implement document versions, ACLs, parsing, chunking, embeddings, and publication.
- Implement hybrid retrieval, reranking, citations, and degraded retrieval.
- Add knowledge management endpoints and UI.

### Phase 5: Async Investigation

- Implement bounded DAG execution.
- Add RabbitMQ workers, retries, dead-letter handling, leases, and cancellation.
- Add complex subscription/billing investigation flow.

### Phase 6: Evaluation And Evidence

- Add golden datasets and evaluation runner.
- Add baseline comparison, metrics, traces, and release gates.
- Run load and failure tests.
- Publish a reproducible report that separates implemented, locally verified, and externally integrated behavior.

## 25. MVP Acceptance

The MVP is accepted when:

1. At least three tenants and representative customer accounts are seeded.
2. A customer can ask about subscription, entitlement, renewal, and billing failure and receive cited, fact-grounded answers.
3. A request can be converted into a queued ticket, claimed by a support agent, resolved, reopened, and audited.
4. Sensitive commercial actions create approvals and cannot execute automatically.
5. Cross-tenant tests fail closed across HTTP, MySQL, Redis, RabbitMQ, Milvus, and traces.
6. Duplicate requests and message deliveries remain idempotent.
7. Commerce and Milvus failure scenarios produce documented degradation rather than invented facts.
8. The full unit, ArchUnit, integration, and fake-model system test suites pass.
9. Evaluation reports include dataset version, code SHA, metrics, latency, cost availability, and known limitations.
10. No performance, quality, or capacity claim is published without a reproducible run.

## 26. Risks And Mitigations

| Risk | Mitigation |
| --- | --- |
| XPeng baseline has no tests and weak tenant boundaries | Build security, migrations, and tests before extending business behavior |
| Kaiyue patterns are too broad to copy safely | Reimplement only selected seams and keep one deployment unit |
| Chinese lexical retrieval quality is weak | Evaluate MySQL ngram retrieval; replace the lexical adapter if benchmark evidence requires it |
| Milvus increases local operational cost | Keep vector adapter interface and deterministic in-memory test adapter |
| LLM output is non-deterministic | Store structured outputs and use fake-model regression plus real-model smoke tests |
| Multi-tenant leakage | Tenant predicates, policy tests, and security audit events at every adapter |
| Long DAG runs consume shared capacity | Per-tenant quotas, bounded queues, deadlines, cancellation, and separate worker pools |
| Evaluation becomes a marketing exercise | Version all inputs, preserve raw reports, and report unavailable metrics honestly |

