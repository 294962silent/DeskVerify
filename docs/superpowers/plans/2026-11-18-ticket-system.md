# Ticket System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add tenant-scoped support tickets with idempotent creation, explicit state transitions, optimistic locking, immutable event history, SLA deadlines, and transactional outbox events.

**Architecture:** Ticket commands and queries are exposed through a deep application interface. Domain state transitions are pure; JDBC adapters own row locking and version checks; creation writes the ticket, first event, idempotency completion, and outbox event in one transaction.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring JDBC, MySQL 8, Flyway, Testcontainers, JUnit 5, MockMvc, Kafka-free Transactional Outbox.

## Global Constraints

- Work in `E:\EchoMind\echomind-saas-agent` on branch `codex/echomind-saas-mvp`.
- New product code stays under `com.echomind.saas`.
- Actor and tenant identity come from `ActorScope`.
- Every tenant-owned SQL query includes `tenant_id`.
- Foreign or unknown ticket access returns 404 `RESOURCE_NOT_FOUND`.
- Creating a ticket requires an idempotency key and authenticated customer mapping.
- Append to outbox only inside an active transaction.
- Explicit-path staging only; never `git add .`.
- Tests run with PowerShell 7, JDK 21, Docker, and live MySQL.

---

### Task 1: Ticket Schema And Domain State Machine

**Files:**

- Create: `EchoMindJava/src/main/resources/db/migration/V9__ticket_system.sql`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/domain/TicketId.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/domain/TicketStatus.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/domain/TicketPriority.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/domain/Ticket.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/domain/TicketEvent.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/domain/TicketStateMachine.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/ticket/domain/TicketStateMachineTest.java`

**Interfaces:**

- Produces: `TicketStatus` values `OPEN`, `QUEUED`, `CLAIMED`, `RESOLVED`, `CLOSED`, `REOPENED`.
- Produces: `TicketStateMachine.requireClaimable(TicketStatus)`.
- Produces: `TicketStateMachine.requireResolvable(TicketStatus)`.
- Produces: `TicketStateMachine.requireClosable(TicketStatus)`.
- Produces: `TicketStateMachine.requireReopenable(TicketStatus)`.
- Illegal transitions throw `InvalidTicketTransitionException`.

**Schema:**

`ticket`:

- `tenant_id`, `ticket_id` composite primary key
- `customer_id`, `created_by_user_id`
- `subject`, `description`, `status`, `priority`, `queue_name`
- `assignee_user_id`, `conversation_id`, `resolution`
- `commercial_refs_json`, `sla_due_at`
- `version`, `created_at`, `updated_at`, `resolved_at`, `closed_at`

`ticket_event`:

- `tenant_id`, `event_id` primary key
- `ticket_id`, `event_type`, `from_status`, `to_status`
- `actor_user_id`, `reason`, `payload_json`, `created_at`

- [ ] **Step 1: Write failing state-machine tests**

```java
@ParameterizedTest
@EnumSource(value = TicketStatus.class, names = {"OPEN", "QUEUED", "REOPENED"})
void queued_states_are_claimable(TicketStatus status) {
    assertThatNoException().isThrownBy(() -> stateMachine.requireClaimable(status));
}

@Test
void claimed_ticket_cannot_be_claimed_twice() {
    assertThatThrownBy(() -> stateMachine.requireClaimable(TicketStatus.CLAIMED))
            .isInstanceOf(InvalidTicketTransitionException.class);
}

@Test
void resolved_ticket_can_be_closed_or_reopened() {
    assertThatNoException().isThrownBy(() -> stateMachine.requireClosable(TicketStatus.RESOLVED));
    assertThatNoException().isThrownBy(() -> stateMachine.requireReopenable(TicketStatus.RESOLVED));
}
```

- [ ] **Step 2: Confirm RED**

```powershell
.\mvnw.cmd '-Dtest=TicketStateMachineTest' test
```

- [ ] **Step 3: Add schema**

Use foreign keys:

- ticket customer to `customer_account(tenant_id, customer_id)`;
- ticket event to `ticket(tenant_id, ticket_id)`.

Use `version bigint not null default 0` and indexes:

- `(tenant_id, status, created_at)`;
- `(tenant_id, assignee_user_id, status)`;
- `(tenant_id, customer_id, created_at)`;
- event `(tenant_id, ticket_id, created_at)`.

- [ ] **Step 4: Implement pure domain transitions**

Allowed:

- claim: `OPEN`, `QUEUED`, `REOPENED` -> `CLAIMED`;
- resolve: `CLAIMED` -> `RESOLVED`;
- close: `RESOLVED` -> `CLOSED`;
- reopen: `RESOLVED`, `CLOSED` -> `REOPENED`;
- queue: `OPEN`, `REOPENED` -> `QUEUED`.

No model or Spring dependency belongs in the domain package.

- [ ] **Step 5: Run tests and ArchUnit**

```powershell
.\mvnw.cmd '-Dtest=TicketStateMachineTest,ModuleBoundaryTest,SaasApiPersistenceBanTest' test
```

- [ ] **Step 6: Commit**

```powershell
git add -- EchoMindJava/src/main/resources/db/migration/V9__ticket_system.sql EchoMindJava/src/main/java/com/echomind/saas/ticket/domain EchoMindJava/src/test/java/com/echomind/saas/ticket/domain
git commit -m "feat: add ticket domain and schema"
```

---

### Task 2: Ticket Repository And Transactional Creation

**Files:**

- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/TicketRepository.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/TicketCommands.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/TicketQueries.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/TicketApplicationService.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/CreateTicketCommand.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/ClaimTicketCommand.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/ResolveTicketCommand.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/ReopenTicketCommand.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/infrastructure/JdbcTicketRepository.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/TicketConflictException.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/application/IdempotencyInProgressException.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/ticket/application/TicketApplicationServiceTest.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/ticket/infrastructure/JdbcTicketRepositoryTest.java`

**Interfaces:**

```java
public interface TicketCommands {
    Ticket create(CreateTicketCommand command, ActorScope actor);
    Ticket claim(String ticketId, long expectedVersion, ActorScope actor);
    Ticket resolve(String ticketId, long expectedVersion, String resolution, ActorScope actor);
    Ticket close(String ticketId, long expectedVersion, ActorScope actor);
    Ticket reopen(String ticketId, long expectedVersion, String reason, ActorScope actor);
}

public interface TicketQueries {
    Ticket get(String ticketId, ActorScope actor);
    List<Ticket> list(TicketFilter filter, ActorScope actor);
    List<TicketEvent> events(String ticketId, ActorScope actor);
}
```

Creation transaction:

1. Validate customer mapping and request.
2. Acquire idempotency key.
3. Insert ticket in `QUEUED` after recording `OPEN` and `QUEUED` event history.
4. Insert `ticket.created` outbox event.
5. Complete idempotency record with the response JSON.

Claim/resolve/close/reopen transaction:

1. Load tenant-scoped ticket.
2. Validate access and current state.
3. Execute optimistic update with `tenant_id`, `ticket_id`, `version`, and allowed source state.
4. Insert immutable event.
5. Insert outbox event.

- [ ] **Step 1: Write failing service tests**

Tests cover:

- create writes ticket, events, idempotency completion, and outbox together;
- duplicate idempotency key returns the completed response;
- an active duplicate returns `IdempotencyInProgressException`;
- a hash mismatch returns `IdempotencyConflictException`;
- foreign customer mapping is denied as 404;
- stale version returns `TicketConflictException`;
- claim updates assignee and version exactly once;
- resolve/close/reopen write one event each and preserve history.

- [ ] **Step 2: Confirm RED**

```powershell
.\mvnw.cmd '-Dtest=TicketApplicationServiceTest,JdbcTicketRepositoryTest' test
```

- [ ] **Step 3: Implement repository**

`JdbcTicketRepository`:

- inserts ticket and event;
- uses optimistic SQL:

```sql
update ticket
set status = ?,
    assignee_user_id = ?,
    resolution = ?,
    version = version + 1,
    updated_at = current_timestamp(6),
    resolved_at = ?,
    closed_at = ?
where tenant_id = ?
  and ticket_id = ?
  and version = ?
  and status = ?
```

- loads by tenant and ticket ID;
- lists by tenant with optional status, customer ID, assignee, and limit;
- loads events by tenant and ticket ID.

- [ ] **Step 4: Implement application service**

Use `@Transactional` on commands. Obtain tenant from `ActorScope`; obtain user roles from `ActorScope`.

Access matrix:

- Customer can create/view/reopen own customer mapping tickets.
- Support agent can view/claim/resolve/close/reopen tenant tickets.
- Tenant admin can perform all support actions.
- Customers cannot list tenant-wide tickets.
- Claim requires `SUPPORT_AGENT` or `TENANT_ADMIN`.

- [ ] **Step 5: Run focused MySQL tests**

```powershell
.\mvnw.cmd '-Dtest=TicketApplicationServiceTest,JdbcTicketRepositoryTest' test
```

Expected: all pass with live MySQL and zero skips.

- [ ] **Step 6: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/ticket/application EchoMindJava/src/main/java/com/echomind/saas/ticket/infrastructure EchoMindJava/src/test/java/com/echomind/saas/ticket
git commit -m "feat: implement transactional ticket workflow"
```

---

### Task 3: Ticket HTTP Contract And Security

**Files:**

- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/api/TicketController.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/api/TicketCreateRequest.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/api/TicketActionRequest.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/ticket/api/TicketResponse.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/saas/api/error/ErrorCode.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/saas/api/error/GlobalApiExceptionHandler.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/ticket/api/TicketControllerTest.java`

**Endpoints:**

- `POST /api/v1/tickets`
- `GET /api/v1/tickets/{ticketId}`
- `GET /api/v1/tickets`
- `POST /api/v1/tickets/{ticketId}/claim`
- `POST /api/v1/tickets/{ticketId}/resolve`
- `POST /api/v1/tickets/{ticketId}/close`
- `POST /api/v1/tickets/{ticketId}/reopen`
- `GET /api/v1/tickets/{ticketId}/events`

Create request header:

- `Idempotency-Key` required, 1-160 safe characters.

Error contract:

- `TICKET_STATE_CONFLICT` -> 409, public message `ticket state conflict`, retryable=false.
- `IDEMPOTENCY_IN_PROGRESS` -> 409, public message `request is still in progress`, retryable=true.
- Unknown/foreign tickets -> 404 `RESOURCE_NOT_FOUND`.

- [ ] **Step 1: Write failing HTTP tests**

Tests cover:

- customer creates own ticket -> 201;
- missing/blank idempotency key -> 400 stable envelope;
- replay returns the same ticket and 200;
- customer cannot view another customer's ticket;
- support agent claims queued ticket;
- stale claim -> 409 `TICKET_STATE_CONFLICT`;
- customer list -> 403;
- support/admin list -> filtered tenant results;
- state events are returned in chronological order.

- [ ] **Step 2: Confirm RED**

```powershell
.\mvnw.cmd '-Dtest=TicketControllerTest' test
```

- [ ] **Step 3: Implement controller and response mapping**

Use `@AuthenticationPrincipal ActorScopeAuthentication`. Never accept user or tenant IDs from the body.

Set:

- `Location: /api/v1/tickets/{ticketId}`;
- `201 Created` for new creation;
- `200 OK` for idempotent replay.

- [ ] **Step 4: Extend security route policy**

Support/admin:

- list, claim, resolve, close;
- all GET actions.

Customer:

- create;
- get/reopen own mapped customer ticket.

Prefer method security over broad URL matchers for ownership-sensitive actions.

- [ ] **Step 5: Run focused tests**

```powershell
.\mvnw.cmd '-Dtest=TicketControllerTest,TicketApplicationServiceTest,Task6bSecurityBoundaryTest' test
```

- [ ] **Step 6: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/ticket/api EchoMindJava/src/main/java/com/echomind/saas/api/error EchoMindJava/src/test/java/com/echomind/saas/ticket/api
git commit -m "feat: expose secure ticket api"
```

---

### Task 4: Ticket Frontend And End-To-End Flow

**Files:**

- Create: `EchoMindFrontend/src/components/TicketPanel.vue`
- Create: `EchoMindFrontend/src/components/TicketPanel.test.js`
- Modify: `EchoMindFrontend/src/App.vue`
- Modify: `EchoMindFrontend/src/lib/backends.js`
- Modify: `docs/LOCAL_DEVELOPMENT.md`

**UI:**

- Customer ticket form with subject/description.
- Ticket status badge and SLA deadline.
- Support controls: claim, resolve, close, reopen.
- Event history.
- Tenant/customer context comes from the authenticated session, not editable inputs.
- All Java calls go through `authorizedFetch`.

- [ ] **Step 1: Write failing component tests**

Tests cover:

- customer can create only when token and customer context exist;
- replay idempotency key does not submit a duplicate;
- support claim sends `expected_version`;
- 409 state conflict displays a refetch action;
- 404 foreign ticket does not reveal owner.

- [ ] **Step 2: Confirm RED**

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindFrontend
npm test
```

- [ ] **Step 3: Implement component and API mapping**

Use dependency injection for the API adapter so tests do not require a live server.

- [ ] **Step 4: Run frontend tests and build**

```powershell
npm test
npm run build
```

- [ ] **Step 5: Run end-to-end backend flow**

A Spring Boot/Testcontainers test must:

1. create a queued ticket;
2. claim it;
3. resolve it;
4. close it;
5. reopen it;
6. assert immutable events and outbox messages.

- [ ] **Step 6: Run complete backend suite**

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindJava
.\mvnw.cmd test
```

Expected: zero failures, errors, or skips.

- [ ] **Step 7: Commit**

```powershell
git add -- EchoMindFrontend/src/components EchoMindFrontend/src/App.vue EchoMindFrontend/src/lib/backends.js docs/LOCAL_DEVELOPMENT.md EchoMindJava/src/test
git commit -m "feat: add ticket support workflow ui"
```

---

## Ticket Acceptance

- Ticket state transitions are pure and exhaustively tested.
- Every transition uses optimistic version control and writes immutable history.
- Creation and all transitions use transactional outbox.
- Idempotent replay is tenant safe.
- Customer/support/admin access is enforced before or at the application seam.
- Foreign ticket access is indistinguishable from missing data.
- UI uses the authenticated bearer token and tenant-scoped transport.
- Backend and frontend suites pass with live MySQL and zero skips.
