# Commerce Read Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tenant-safe read model for SaaS customer accounts, plans, entitlements, orders, subscriptions, invoices, and payment attempts.

**Architecture:** The commerce module is read-only and exposes one deep query interface. The implementation hides SQL aggregation and access filtering; callers receive a typed snapshot and cannot mutate commercial facts.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring JDBC, MySQL 8, Flyway, Testcontainers, JUnit 5, ArchUnit.

## Global Constraints

- Work in `E:\EchoMind\echomind-saas-agent` on branch `codex/echomind-saas-mvp`.
- New product code stays under `com.echomind.saas`.
- Commerce code never writes commercial tables.
- Every tenant-owned SQL query includes `tenant_id`.
- Actor and customer access comes from `ActorScope`, never request-body identity.
- Foreign or unknown customer access returns the same 404 `RESOURCE_NOT_FOUND` envelope.
- Maven commands use PowerShell 7, JDK 21, and Docker:

```powershell
$env:JAVA_HOME='C:\Users\MLL\AppData\Local\Temp\echomind-jdk21\jdk-21.0.13+9'
$env:Path="$env:JAVA_HOME\bin;D:\Docker\Docker\resources\bin;$env:Path"
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
```

- Explicit-path staging only; never `git add .`.
- All tests execute with zero failures, errors, or skips.

---

### Task 1: Commerce Schema And JDBC Read Adapter

**Files:**

- Create: `EchoMindJava/src/main/resources/db/migration/V7__commerce_read_model.sql`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/CustomerId.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/Money.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/CustomerAccount.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/CommerceOrder.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/PlanSummary.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/Entitlement.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/SubscriptionSummary.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/InvoiceSummary.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/PaymentAttemptSummary.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/domain/CommercialSnapshot.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/application/CommerceReadRepository.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/infrastructure/JdbcCommerceReadRepository.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/commerce/domain/MoneyTest.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/commerce/infrastructure/JdbcCommerceReadRepositoryTest.java`

**Interfaces:**

- Produces: `CustomerId(String value)`.
- Produces: `Money(long amountMinor, String currency)`.
- Produces: `CommerceReadRepository.load(TenantId tenantId, CustomerId customerId): Optional<CommercialSnapshot>`.
- `CommercialSnapshot` owns one account, orders, current plan, entitlements, optional current subscription, invoices, and payment attempts.

**Schema:**

- `customer_account`
- `commerce_order`
- `commerce_plan`
- `plan_entitlement`
- `subscription`
- `invoice`
- `payment_attempt`

All customer-owned tables use composite indexes beginning with `tenant_id, customer_id`.

- [ ] **Step 1: Write failing domain and repository tests**

```java
@Test
void moneyRejectsNegativeAmounts() {
    assertThatThrownBy(() -> new Money(-1, "CNY"))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void loadsOnlyTheSelectedTenantAndCustomer() {
    CommercialSnapshot snapshot = repository
            .load(new TenantId("tenant-a"), new CustomerId("customer-a"))
            .orElseThrow();

    assertThat(snapshot.account().customerId().value()).isEqualTo("customer-a");
    assertThat(snapshot.orders()).extracting(CommerceOrder::orderId)
            .containsExactly("order-a1", "order-a2");
    assertThat(snapshot.invoices()).extracting(InvoiceSummary::invoiceId)
            .containsExactly("invoice-a1");
}
```

The MySQL fixture must also insert:

- `tenant-b/customer-a` with the same customer slug and different rows;
- `tenant-a/customer-b`;
- two invoices in descending due-date order;
- a failed payment attempt;
- one active and one historical subscription.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\mvnw.cmd '-Dtest=MoneyTest,JdbcCommerceReadRepositoryTest' test
```

Expected: compilation failure because the commerce types and schema do not exist.

- [ ] **Step 3: Add migration**

`V7__commerce_read_model.sql` creates:

```sql
create table customer_account (
    tenant_id varchar(64) not null,
    customer_id varchar(128) not null,
    display_name varchar(160) not null,
    status varchar(32) not null,
    external_ref varchar(160) null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (tenant_id, customer_id)
);

create table commerce_order (
    tenant_id varchar(64) not null,
    order_id varchar(128) not null,
    customer_id varchar(128) not null,
    product_code varchar(80) not null,
    quantity int not null,
    amount_minor bigint not null,
    currency char(3) not null,
    status varchar(32) not null,
    ordered_at timestamp(6) not null,
    fulfilled_at timestamp(6) null,
    subscription_id varchar(128) null,
    primary key (tenant_id, order_id),
    constraint fk_order_account foreign key (tenant_id, customer_id)
        references customer_account(tenant_id, customer_id),
    index idx_order_customer (tenant_id, customer_id, ordered_at)
);
```

Add equivalent `commerce_plan`, `plan_entitlement`, `subscription`, `invoice`, and `payment_attempt` tables. Use `bigint` minor units and `char(3)` currency. Use foreign keys for customer, plan, subscription, and invoice references.

- [ ] **Step 4: Implement immutable domain records**

```java
public record Money(long amountMinor, String currency) {
    public Money {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must be non-negative");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code");
        }
    }
}
```

```java
public record CommercialSnapshot(
        CustomerAccount account,
        List<CommerceOrder> orders,
        PlanSummary currentPlan,
        List<Entitlement> entitlements,
        Optional<SubscriptionSummary> currentSubscription,
        List<InvoiceSummary> invoices,
        List<PaymentAttemptSummary> paymentAttempts
) {
    public CommercialSnapshot {
        orders = List.copyOf(orders);
        entitlements = List.copyOf(entitlements);
        invoices = List.copyOf(invoices);
        paymentAttempts = List.copyOf(paymentAttempts);
    }
}
```

- [ ] **Step 5: Implement JDBC adapter**

`JdbcCommerceReadRepository` loads in deterministic order:

1. Customer account.
2. Orders by `ordered_at desc, order_id desc`.
3. Current plan and entitlements.
4. Active subscription, otherwise most recent subscription.
5. Recent invoices by `due_at desc, invoice_id desc`.
6. Payment attempts belonging to those invoices.

Return `Optional.empty()` when the account is not visible. Each SQL query begins with tenant and customer predicates.

- [ ] **Step 6: Run focused tests**

```powershell
.\mvnw.cmd '-Dtest=MoneyTest,JdbcCommerceReadRepositoryTest' test
```

Expected: domain and MySQL tests pass with zero skips.

- [ ] **Step 7: Commit**

```powershell
git add -- EchoMindJava/src/main/resources/db/migration/V7__commerce_read_model.sql EchoMindJava/src/main/java/com/echomind/saas/commerce EchoMindJava/src/test/java/com/echomind/saas/commerce
git commit -m "feat: add commerce read model"
```

---

### Task 2: Commerce Access Policy And Application Interface

**Files:**

- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/application/CommerceAccessPolicy.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/application/CommerceQuery.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/application/CommerceQueryService.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/commerce/application/CommerceQueryServiceTest.java`

**Interfaces:**

- Produces: `CommerceQuery.load(CustomerId customerId, ActorScope actor): CommercialSnapshot`.
- `CommerceAccessPolicy.requireCustomer(ActorScope actor, CustomerId customerId): void`.
- Unauthorized customer access throws `ResourceNotFoundException`, not a resource-specific 403.

Policy:

- Actor tenant is always taken from `ActorScope`.
- Customer identity must exist in `ActorScope.customerIds`.
- No support/admin bypass for customer data unless the customer mapping is present.
- Missing or foreign data is indistinguishable.

- [ ] **Step 1: Write failing access tests**

```java
@Test
void deniesCustomerOutsideActorScopeAsNotFound() {
    ActorScope actor = actor("tenant-a", "user-1", Set.of("customer-a"));

    assertThatThrownBy(() -> service.load(new CustomerId("customer-b"), actor))
            .isInstanceOf(ResourceNotFoundException.class);
}

@Test
void usesTenantFromActorScope() {
    actor = actor("tenant-a", "user-1", Set.of("customer-a"));
    service.load(new CustomerId("customer-a"), actor);

    verify(repository).load(new TenantId("tenant-a"), new CustomerId("customer-a"));
}
```

- [ ] **Step 2: Confirm RED**

```powershell
.\mvnw.cmd '-Dtest=CommerceQueryServiceTest' test
```

- [ ] **Step 3: Implement policy and query service**

```java
public interface CommerceQuery {
    CommercialSnapshot load(CustomerId customerId, ActorScope actor);
}
```

```java
public final class CommerceAccessPolicy {
    public void requireCustomer(ActorScope actor, CustomerId customerId) {
        if (!actor.customerIds().contains(customerId.value())) {
            throw new ResourceNotFoundException("resource not found");
        }
    }
}
```

`CommerceQueryService` checks policy, calls the repository with `actor.tenantId()`, and throws the same not-found exception when the snapshot is empty.

- [ ] **Step 4: Run tests**

```powershell
.\mvnw.cmd '-Dtest=CommerceQueryServiceTest,JdbcCommerceReadRepositoryTest' test
```

- [ ] **Step 5: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/commerce/application EchoMindJava/src/test/java/com/echomind/saas/commerce/application
git commit -m "feat: enforce commerce customer scope"
```

---

### Task 3: Commerce HTTP Contract

**Files:**

- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/api/CommerceResponse.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/commerce/api/CommerceController.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/commerce/api/CommerceControllerTest.java`

**Interfaces:**

- Endpoint: `GET /api/v1/commerce/customers/{customerId}`.
- Identity comes from `@AuthenticationPrincipal ActorScopeAuthentication`.
- Response preserves minor-unit money values and snake_case JSON naming.

- [ ] **Step 1: Write failing HTTP tests**

Tests cover:

- authenticated customer sees its own complete snapshot;
- a customer without the mapping receives 404 `RESOURCE_NOT_FOUND`;
- two tenants with the same customer slug return only their tenant data;
- unauthenticated request is rejected by the existing security envelope.

- [ ] **Step 2: Confirm RED**

```powershell
.\mvnw.cmd '-Dtest=CommerceControllerTest' test
```

- [ ] **Step 3: Implement DTO and controller**

```java
@RestController
@RequestMapping("/api/v1/commerce")
public final class CommerceController {

    @GetMapping("/customers/{customerId}")
    public CommerceResponse get(
            @PathVariable String customerId,
            @AuthenticationPrincipal ActorScopeAuthentication authentication) {
        return CommerceResponse.from(
                commerceQuery.load(new CustomerId(customerId), authentication.getScope())
        );
    }
}
```

- [ ] **Step 4: Run focused HTTP and access tests**

```powershell
.\mvnw.cmd '-Dtest=CommerceControllerTest,CommerceQueryServiceTest' test
```

- [ ] **Step 5: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/commerce/api EchoMindJava/src/test/java/com/echomind/saas/commerce/api
git commit -m "feat: expose commerce customer summary"
```

---

### Task 4: Commerce Demo Data

**Files:**

- Create: `EchoMindJava/src/main/resources/db/demo/V100__commerce_demo.sql`
- Create: `EchoMindJava/src/main/resources/application-demo.yml`
- Modify: `docs/LOCAL_DEVELOPMENT.md`

**Behavior:**

- Demo profile runs normal migrations plus demo seed.
- Three tenants: `acme`, `globex`, `initech`.
- Each tenant has at least two customer accounts.
- Each customer has orders, plan, entitlements, a current subscription, invoices, and one failed payment attempt.
- Demo user mappings are inserted only in the demo profile.

- [ ] **Step 1: Add demo profile**

```yaml
spring:
  flyway:
    locations:
      - classpath:db/migration
      - classpath:db/demo
```

- [ ] **Step 2: Add deterministic demo rows**

Use stable IDs and UTC timestamps. Include at least one overdue invoice and one active subscription.

- [ ] **Step 3: Verify profile startup against MySQL**

Run with the demo profile and a Testcontainers-backed integration test. Assert all three tenants are present and customer reads remain tenant scoped.

- [ ] **Step 4: Document**

Document how to initialize demo data and how the commerce endpoint is queried with a JWT.

- [ ] **Step 5: Run full suite**

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindJava
.\mvnw.cmd test
```

Expected: zero failures, errors, or skips.

- [ ] **Step 6: Commit**

```powershell
git add -- EchoMindJava/src/main/resources/db/demo EchoMindJava/src/main/resources/application-demo.yml docs/LOCAL_DEVELOPMENT.md
git commit -m "feat: add commerce demo data"
```

---

## Commerce Acceptance

- Customer snapshot is immutable and read-only.
- Tenant and customer scoping is enforced in SQL, policy, and HTTP tests.
- Orders, plan, entitlements, subscription, invoice, and failed-payment data are queryable through one interface.
- Demo profile is deterministic and tenant safe.
- Full suite runs live MySQL with zero skips.
