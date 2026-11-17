# EchoMind SaaS Support Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a secure, testable modular-monolith foundation for tenant identity, MySQL persistence, idempotency, outbox/inbox messaging, and module boundaries before business features are added.

**Architecture:** Keep the XPeng application as the deployment baseline while creating all new product code under `com.echomind.saas`. New code is organized by responsibility and guarded by ArchUnit. This plan does not yet implement the chat, commerce, ticket, or RAG business modules.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Security OAuth2 Resource Server, Spring JDBC, Flyway, MySQL 8, Testcontainers, ArchUnit, JUnit 5.

## Global Constraints

- Work only in `E:\EchoMind\echomind-saas-agent` on branch `codex/echomind-saas-mvp`.
- Do not modify `E:\EchoMind\kaiyue-agent` or `E:\EchoMind\xpeng-saas`.
- New product code must use root package `com.echomind.saas`.
- Legacy XPeng packages under `com.echomind` remain temporarily available and must not import new `com.echomind.saas` internals.
- Tenant and actor identity must never be accepted from a request body for authorization decisions.
- Every tenant-owned SQL query must include `tenant_id`.
- Do not add placeholder secrets, commit real API keys, or require an external LLM for tests.
- Stage explicit paths only; never run `git add .`.
- Run Maven commands from `E:\EchoMind\echomind-saas-agent\EchoMindJava`; run Git commands from `E:\EchoMind\echomind-saas-agent`.
- Every task must finish with its specified tests passing and a local commit.

---

### Task 1: Foundation Dependencies And Actor Domain

**Files:**

- Modify: `EchoMindJava/pom.xml`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/domain/TenantId.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/domain/UserId.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/domain/ActorRole.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/domain/ActorScope.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/application/ActorScopeResolver.java`
- Create: `EchoMindJava/src/test/java/com/echomind/saas/identity/domain/ActorScopeTest.java`
- Create: `EchoMindJava/src/test/java/com/echomind/saas/arch/ModuleBoundaryTest.java`

**Interfaces:**

- Produces: `TenantId(String value)`
- Produces: `UserId(String value)`
- Produces: `ActorRole.CUSTOMER`, `ActorRole.SUPPORT_AGENT`, `ActorRole.TENANT_ADMIN`
- Produces: `ActorScope(UserId userId, TenantId tenantId, Set<ActorRole> roles, Set<String> customerIds, String requestId)`
- Produces: `ActorScope.hasRole(ActorRole role): boolean`
- Produces: `ActorScopeResolver.resolve(UserId userId, TenantId tenantId, String requestId): ActorScope`

- [ ] **Step 1: Write the failing actor and architecture tests**

```java
package com.echomind.saas.identity.domain;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorScopeTest {

    @Test
    void createsAnImmutableTenantScopedActor() {
        ActorScope scope = new ActorScope(
                new UserId("user-1"),
                new TenantId("tenant-a"),
                Set.of(ActorRole.SUPPORT_AGENT),
                Set.of("customer-1"),
                "request-1"
        );

        assertThat(scope.hasRole(ActorRole.SUPPORT_AGENT)).isTrue();
        assertThatThrownBy(() -> scope.roles().add(ActorRole.TENANT_ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankActorIdentity() {
        assertThatThrownBy(() -> new UserId(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantId("tenant_a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

```java
package com.echomind.saas.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.echomind.saas")
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule identity_does_not_depend_on_business_modules = noClasses()
            .that().resideInAPackage("com.echomind.saas.identity..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.echomind.saas.api..",
                    "com.echomind.saas.commerce..",
                    "com.echomind.saas.support..",
                    "com.echomind.saas.ticket..",
                    "com.echomind.saas.knowledge..",
                    "com.echomind.saas.agent..",
                    "com.echomind.saas.evaluation.."
            );
}
```

- [ ] **Step 2: Run tests and verify the expected failure**

Run:

```powershell
.\mvnw.cmd -Dtest=ActorScopeTest,ModuleBoundaryTest test
```

Expected: compilation fails because the actor classes and ArchUnit dependency do not exist.

- [ ] **Step 3: Add Maven dependencies**

Add these entries to `EchoMindJava/pom.xml`:

```xml
<properties>
    <archunit.version>1.3.0</archunit.version>
</properties>
```

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>${archunit.version}</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Implement the actor value types**

```java
package com.echomind.saas.identity.domain;

public record TenantId(String value) {
    public TenantId {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw new IllegalArgumentException("tenant id must be 2-63 lowercase slug characters");
        }
    }
}
```

```java
package com.echomind.saas.identity.domain;

public record UserId(String value) {
    public UserId {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("user id must be 1-128 safe characters");
        }
    }
}
```

```java
package com.echomind.saas.identity.domain;

public enum ActorRole {
    CUSTOMER,
    SUPPORT_AGENT,
    TENANT_ADMIN
}
```

```java
package com.echomind.saas.identity.domain;

import java.util.Objects;
import java.util.Set;

public record ActorScope(
        UserId userId,
        TenantId tenantId,
        Set<ActorRole> roles,
        Set<String> customerIds,
        String requestId
) {
    public ActorScope {
        userId = Objects.requireNonNull(userId, "userId");
        tenantId = Objects.requireNonNull(tenantId, "tenantId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        customerIds = Set.copyOf(Objects.requireNonNull(customerIds, "customerIds"));
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
    }

    public boolean hasRole(ActorRole role) {
        return roles.contains(role);
    }
}
```

- [ ] **Step 5: Add the actor resolution seam**

```java
package com.echomind.saas.identity.application;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;

public interface ActorScopeResolver {
    ActorScope resolve(UserId userId, TenantId tenantId, String requestId);
}
```

- [ ] **Step 6: Run the focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=ActorScopeTest,ModuleBoundaryTest test
```

Expected: `BUILD SUCCESS`; both test classes pass.

- [ ] **Step 7: Commit Task 1**

```powershell
git add -- EchoMindJava/pom.xml EchoMindJava/src/main/java/com/echomind/saas/identity/domain EchoMindJava/src/main/java/com/echomind/saas/identity/application/ActorScopeResolver.java EchoMindJava/src/test/java/com/echomind/saas/identity/domain EchoMindJava/src/test/java/com/echomind/saas/arch
git commit -m "feat: add secure actor foundation"
```

---

### Task 2: JWT To Actor Scope Conversion

**Files:**

- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/security/ActorScopeAuthentication.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/security/JwtActorScopeConverter.java`
- Create: `EchoMindJava/src/test/java/com/echomind/saas/identity/security/JwtActorScopeConverterTest.java`

**Interfaces:**

- Consumes: `ActorScope`, `TenantId`, `UserId`, `ActorScopeResolver`
- Produces: `ActorScopeAuthentication(ActorScope scope)`
- Produces: `JwtActorScopeConverter.convert(Jwt jwt): AbstractAuthenticationToken`
- JWT contract: `sub` is user ID, `tenant_id` is tenant slug, and `jti` is used as request ID when present.
- The converter does not trust role claims from the token. Roles and customer permissions are resolved through `ActorScopeResolver`.

- [ ] **Step 1: Write the failing converter test**

```java
package com.echomind.saas.identity.security;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class JwtActorScopeConverterTest {

    private final JwtActorScopeConverter converter = new JwtActorScopeConverter(
            (userId, tenantId, requestId) -> new com.echomind.saas.identity.domain.ActorScope(
                    userId,
                    tenantId,
                    Set.of(com.echomind.saas.identity.domain.ActorRole.SUPPORT_AGENT),
                    Set.of("customer-1"),
                    requestId
            ));

    @Test
    void convertsTrustedClaimsIntoActorScope() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .id("jwt-1")
                .claim("tenant_id", "tenant-a")
                .issuedAt(Instant.parse("2026-11-17T00:00:00Z"))
                .expiresAt(Instant.parse("2026-11-17T01:00:00Z"))
                .build();

        ActorScopeAuthentication authentication =
                (ActorScopeAuthentication) converter.convert(jwt);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getScope().tenantId().value()).isEqualTo("tenant-a");
        assertThat(authentication.getScope().hasRole(
                com.echomind.saas.identity.domain.ActorRole.SUPPORT_AGENT)).isTrue();
        assertThat(authentication.getScope().requestId()).isEqualTo("jwt-1");
    }
}
```

- [ ] **Step 2: Run the test and verify the expected failure**

Run:

```powershell
.\mvnw.cmd -Dtest=JwtActorScopeConverterTest test
```

Expected: compilation fails because the converter and authentication classes do not exist.

- [ ] **Step 3: Implement authentication and conversion**

```java
package com.echomind.saas.identity.security;

import com.echomind.saas.identity.domain.ActorScope;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class ActorScopeAuthentication extends AbstractAuthenticationToken {

    private final ActorScope scope;

    public ActorScopeAuthentication(ActorScope scope) {
        super(List.copyOf(scope.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList()));
        this.scope = scope;
        setAuthenticated(true);
    }

    public ActorScope getScope() {
        return scope;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return scope;
    }

    @Override
    public String getName() {
        return scope.userId().value();
    }
}
```

```java
package com.echomind.saas.identity.security;

import com.echomind.saas.identity.application.ActorScopeResolver;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtActorScopeConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ActorScopeResolver actorScopeResolver;

    public JwtActorScopeConverter(ActorScopeResolver actorScopeResolver) {
        this.actorScopeResolver = actorScopeResolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UserId userId = new UserId(jwt.getSubject());
        TenantId tenantId = new TenantId(jwt.getClaimAsString("tenant_id"));
        String requestId = jwt.getId() == null || jwt.getId().isBlank()
                ? java.util.UUID.randomUUID().toString()
                : jwt.getId();

        return new ActorScopeAuthentication(
                actorScopeResolver.resolve(userId, tenantId, requestId)
        );
    }
}
```

- [ ] **Step 4: Run the focused test**

Run:

```powershell
.\mvnw.cmd -Dtest=JwtActorScopeConverterTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit Task 2**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/identity/security EchoMindJava/src/test/java/com/echomind/saas/identity/security
git commit -m "feat: derive actor scope from jwt"
```

---

### Task 3: MySQL Identity And Messaging Schema

**Files:**

- Create: `EchoMindJava/src/main/resources/db/migration/V1__identity.sql`
- Create: `EchoMindJava/src/main/resources/db/migration/V2__platform_events.sql`
- Create: `EchoMindJava/src/test/java/com/echomind/saas/platform/persistence/FlywayMigrationTest.java`

**Interfaces:**

- Produces tables: `tenant`, `app_user`, `tenant_membership`, `role_binding`, `customer_membership`
- Produces tables: `idempotency_record`, `outbox_event`, `inbox_event`
- Every tenant-owned table has `tenant_id`.

- [ ] **Step 1: Write the failing migration test**

```java
package com.echomind.saas.platform.persistence;

import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withDatabaseName("echomind");

    @Test
    void appliesIdentityAndMessagingSchema() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            var tables = List.of("tenant", "tenant_membership", "outbox_event", "inbox_event");
            for (String table : tables) {
                try (var statement = connection.prepareStatement(
                        "select count(*) from information_schema.tables " +
                        "where table_schema = database() and table_name = ?")) {
                    statement.setString(1, table);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        assertThat(result.getInt(1)).isEqualTo(1);
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the failing migration test**

Run:

```powershell
.\mvnw.cmd -Dtest=FlywayMigrationTest test
```

Expected: no migrations are found or required tables are absent, so the test fails.

- [ ] **Step 3: Add the identity migration**

`V1__identity.sql`:

```sql
create table tenant (
    id varchar(64) primary key,
    display_name varchar(160) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null default current_timestamp(6)
);

create table app_user (
    id varchar(128) primary key,
    display_name varchar(160) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null default current_timestamp(6)
);

create table tenant_membership (
    tenant_id varchar(64) not null,
    user_id varchar(128) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (tenant_id, user_id),
    constraint fk_membership_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_membership_user foreign key (user_id) references app_user(id)
);

create table role_binding (
    tenant_id varchar(64) not null,
    user_id varchar(128) not null,
    role_name varchar(32) not null,
    primary key (tenant_id, user_id, role_name),
    constraint fk_role_membership foreign key (tenant_id, user_id)
        references tenant_membership(tenant_id, user_id)
);

create table customer_membership (
    tenant_id varchar(64) not null,
    user_id varchar(128) not null,
    customer_id varchar(128) not null,
    permission varchar(32) not null,
    primary key (tenant_id, user_id, customer_id),
    constraint fk_customer_membership foreign key (tenant_id, user_id)
        references tenant_membership(tenant_id, user_id)
);
```

- [ ] **Step 4: Add the platform event migration**

`V2__platform_events.sql`:

```sql
create table idempotency_record (
    tenant_id varchar(64) not null,
    idempotency_key varchar(160) not null,
    request_hash char(64) not null,
    state varchar(32) not null,
    response_json json null,
    created_at timestamp(6) not null default current_timestamp(6),
    expires_at timestamp(6) not null,
    primary key (tenant_id, idempotency_key)
);

create table outbox_event (
    id char(36) primary key,
    tenant_id varchar(64) not null,
    aggregate_type varchar(80) not null,
    aggregate_id varchar(128) not null,
    event_type varchar(128) not null,
    payload_json json not null,
    state varchar(32) not null,
    attempts int not null default 0,
    available_at timestamp(6) not null default current_timestamp(6),
    created_at timestamp(6) not null default current_timestamp(6),
    published_at timestamp(6) null,
    index idx_outbox_pending (state, available_at)
);

create table inbox_event (
    consumer_name varchar(128) not null,
    event_id varchar(128) not null,
    tenant_id varchar(64) not null,
    received_at timestamp(6) not null default current_timestamp(6),
    primary key (consumer_name, event_id)
);
```

- [ ] **Step 5: Run the migration test**

Run:

```powershell
.\mvnw.cmd -Dtest=FlywayMigrationTest test
```

Expected: `BUILD SUCCESS`; both migrations apply.

- [ ] **Step 6: Commit Task 3**

```powershell
git add -- EchoMindJava/src/main/resources/db/migration EchoMindJava/src/test/java/com/echomind/saas/platform/persistence
git commit -m "feat: add identity and messaging schema"
```

---

### Task 4: Tenant Membership Resolution

**Files:**

- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/application/TenantAccessDeniedException.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/application/TenantMembershipRepository.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/application/TenantAccessService.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/identity/infrastructure/JdbcTenantMembershipRepository.java`
- Create: `EchoMindJava/src/test/java/com/echomind/saas/identity/infrastructure/JdbcTenantMembershipRepositoryTest.java`

**Interfaces:**

- Produces: `TenantMembershipRepository.findRoles(TenantId, UserId): Set<ActorRole>`
- Produces: `TenantMembershipRepository.findCustomerIds(TenantId, UserId): Set<String>`
- Produces: `TenantAccessService.resolve(UserId, TenantId, String requestId): ActorScope`
- Throws: `TenantAccessDeniedException` when no active membership exists.

- [ ] **Step 1: Write the failing membership repository test**

```java
package com.echomind.saas.identity.infrastructure;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTenantMembershipRepositoryTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withDatabaseName("echomind");

    private JdbcTenantMembershipRepository repository;

    @BeforeEach
    void setUp() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("insert into tenant(id, display_name, status) values('tenant-a','A','ACTIVE')");
        jdbc.update("insert into app_user(id, display_name, status) values('user-1','U','ACTIVE')");
        jdbc.update("""
                insert into tenant_membership(tenant_id, user_id, status)
                values('tenant-a','user-1','ACTIVE')
                """);
        jdbc.update("""
                insert into role_binding(tenant_id, user_id, role_name)
                values('tenant-a','user-1','SUPPORT_AGENT')
                """);
        jdbc.update("""
                insert into customer_membership(tenant_id, user_id, customer_id, permission)
                values('tenant-a','user-1','customer-1','READ')
                """);
        repository = new JdbcTenantMembershipRepository(jdbc);
    }

    @Test
    void returnsOnlyActiveTenantRolesAndCustomers() {
        TenantId tenantId = new TenantId("tenant-a");
        UserId userId = new UserId("user-1");

        assertThat(repository.findRoles(tenantId, userId))
                .containsExactly(com.echomind.saas.identity.domain.ActorRole.SUPPORT_AGENT);
        assertThat(repository.findCustomerIds(tenantId, userId))
                .containsExactly("customer-1");
    }
}
```

- [ ] **Step 2: Run the test and verify the expected failure**

Run:

```powershell
.\mvnw.cmd -Dtest=JdbcTenantMembershipRepositoryTest test
```

Expected: compilation fails because the repository does not exist.

- [ ] **Step 3: Implement the repository port**

```java
package com.echomind.saas.identity.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Set;

public interface TenantMembershipRepository {
    Set<ActorRole> findRoles(TenantId tenantId, UserId userId);
    Set<String> findCustomerIds(TenantId tenantId, UserId userId);
}
```

```java
package com.echomind.saas.identity.application;

public final class TenantAccessDeniedException extends RuntimeException {
    public TenantAccessDeniedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Implement the JDBC adapter and resolver**

```java
package com.echomind.saas.identity.infrastructure;

import com.echomind.saas.identity.application.TenantMembershipRepository;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcTenantMembershipRepository
        implements TenantMembershipRepository {

    private final JdbcTemplate jdbc;

    public JdbcTenantMembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<ActorRole> findRoles(TenantId tenantId, UserId userId) {
        return Set.copyOf(jdbc.queryForList("""
                select rb.role_name
                from role_binding rb
                join tenant_membership tm
                  on tm.tenant_id = rb.tenant_id and tm.user_id = rb.user_id
                where rb.tenant_id = ? and rb.user_id = ? and tm.status = 'ACTIVE'
                """, String.class, tenantId.value(), userId.value())
                .stream()
                .map(ActorRole::valueOf)
                .toList());
    }

    @Override
    public Set<String> findCustomerIds(TenantId tenantId, UserId userId) {
        return Set.copyOf(jdbc.queryForList("""
                select cm.customer_id
                from customer_membership cm
                join tenant_membership tm
                  on tm.tenant_id = cm.tenant_id and tm.user_id = cm.user_id
                where cm.tenant_id = ? and cm.user_id = ? and tm.status = 'ACTIVE'
                """, String.class, tenantId.value(), userId.value()));
    }
}
```

```java
package com.echomind.saas.identity.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class TenantAccessService implements ActorScopeResolver {

    private final TenantMembershipRepository repository;

    public TenantAccessService(TenantMembershipRepository repository) {
        this.repository = repository;
    }

    @Override
    public ActorScope resolve(UserId userId, TenantId tenantId, String requestId) {
        Set<ActorRole> roles = repository.findRoles(tenantId, userId);
        if (roles.isEmpty()) {
            throw new TenantAccessDeniedException("active tenant membership is required");
        }
        return new ActorScope(
                userId,
                tenantId,
                roles,
                repository.findCustomerIds(tenantId, userId),
                requestId
        );
    }
}
```

- [ ] **Step 5: Run identity persistence and boundary tests**

Run:

```powershell
.\mvnw.cmd -Dtest=JdbcTenantMembershipRepositoryTest,ModuleBoundaryTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit Task 4**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/identity EchoMindJava/src/test/java/com/echomind/saas/identity
git commit -m "feat: enforce tenant membership resolution"
```

---

### Task 5: Idempotency And Outbox Persistence

**Files:**

- Create: `EchoMindJava/src/main/java/com/echomind/saas/platform/idempotency/IdempotencyConflictException.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/platform/idempotency/IdempotencyRepository.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/platform/idempotency/JdbcIdempotencyRepository.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/platform/outbox/OutboxMessage.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/platform/outbox/OutboxRepository.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/platform/outbox/JdbcOutboxRepository.java`
- Create: `EchoMindJava/src/test/java/com/echomind/saas/platform/idempotency/JdbcIdempotencyRepositoryTest.java`
- Create: `EchoMindJava/src/test/java/com/echomind/saas/platform/outbox/JdbcOutboxRepositoryTest.java`

**Interfaces:**

- Produces: `IdempotencyRepository.tryAcquire(TenantId, String key, String requestHash, Instant expiresAt): boolean`
- Produces: `IdempotencyRepository.complete(TenantId, String key, String responseJson): void`
- Produces: `IdempotencyConflictException`
- Produces: `OutboxRepository.append(OutboxMessage message): void`
- Produces: `OutboxRepository.findPending(int limit): List<OutboxMessage>`

- [ ] **Step 1: Write the failing idempotency test**

```java
package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcIdempotencyRepositoryTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withDatabaseName("echomind");

    private JdbcIdempotencyRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("delete from idempotency_record");
        repository = new JdbcIdempotencyRepository(jdbc);
    }

    @Test
    void acceptsOnceAndRejectsAReusedKeyWithDifferentPayload() {
        TenantId tenantId = new TenantId("tenant-a");
        Instant expiresAt = Instant.now().plusSeconds(3600);

        assertThat(repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt)).isTrue();
        assertThat(repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt)).isFalse();
        assertThatThrownBy(
                () -> repository.tryAcquire(tenantId, "key-1", "hash-b", expiresAt))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
```

- [ ] **Step 2: Write the failing outbox test**

```java
package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcOutboxRepositoryTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withDatabaseName("echomind");

    private JdbcOutboxRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("delete from outbox_event");
        repository = new JdbcOutboxRepository(jdbc);
    }

    @Test
    void persistsAPendingMessage() {
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID().toString(),
                new TenantId("tenant-a"),
                "ticket",
                "ticket-1",
                "ticket.created",
                "{\"ticketId\":\"ticket-1\"}",
                Instant.now()
        );

        repository.append(message);

        assertThat(repository.findPending(10))
                .extracting(OutboxMessage::eventType)
                .containsExactly("ticket.created");
    }
}
```

- [ ] **Step 3: Run tests and verify the expected failure**

Run:

```powershell
.\mvnw.cmd -Dtest=JdbcIdempotencyRepositoryTest,JdbcOutboxRepositoryTest test
```

Expected: compilation fails because the repositories do not exist.

- [ ] **Step 4: Implement idempotency persistence**

```java
package com.echomind.saas.platform.idempotency;

public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
```

```java
package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;

public interface IdempotencyRepository {
    boolean tryAcquire(TenantId tenantId, String key, String requestHash, Instant expiresAt);
    void complete(TenantId tenantId, String key, String responseJson);
}
```

```java
package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(
            TenantId tenantId, String key, String requestHash, Instant expiresAt) {
        try {
            jdbc.update("""
                    insert into idempotency_record(
                        tenant_id, idempotency_key, request_hash, state, expires_at
                    ) values (?, ?, ?, 'IN_PROGRESS', ?)
                    """, tenantId.value(), key, requestHash, expiresAt);
            return true;
        } catch (DuplicateKeyException duplicate) {
            List<String> hashes = jdbc.queryForList("""
                    select request_hash from idempotency_record
                    where tenant_id = ? and idempotency_key = ?
                    """, String.class, tenantId.value(), key);
            if (hashes.isEmpty()) {
                throw duplicate;
            }
            if (!hashes.getFirst().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "idempotency key was reused with a different request");
            }
            return false;
        }
    }

    @Override
    public void complete(TenantId tenantId, String key, String responseJson) {
        jdbc.update("""
                update idempotency_record
                set state = 'COMPLETED', response_json = ?
                where tenant_id = ? and idempotency_key = ?
                """, responseJson, tenantId.value(), key);
    }
}
```

- [ ] **Step 5: Implement outbox persistence**

```java
package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;

public record OutboxMessage(
        String id,
        TenantId tenantId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        Instant availableAt
) {
}
```

```java
package com.echomind.saas.platform.outbox;

import java.util.List;

public interface OutboxRepository {
    void append(OutboxMessage message);
    List<OutboxMessage> findPending(int limit);
}
```

```java
package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcTemplate jdbc;

    public JdbcOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OutboxMessage message) {
        jdbc.update("""
                insert into outbox_event(
                    id, tenant_id, aggregate_type, aggregate_id,
                    event_type, payload_json, state, available_at
                ) values (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """,
                message.id(),
                message.tenantId().value(),
                message.aggregateType(),
                message.aggregateId(),
                message.eventType(),
                message.payloadJson(),
                Timestamp.from(message.availableAt()));
    }

    @Override
    public List<OutboxMessage> findPending(int limit) {
        return jdbc.query("""
                select id, tenant_id, aggregate_type, aggregate_id,
                       event_type, payload_json, available_at
                from outbox_event
                where state = 'PENDING' and available_at <= current_timestamp(6)
                order by created_at
                limit ?
                """, (result, row) -> new OutboxMessage(
                result.getString("id"),
                new TenantId(result.getString("tenant_id")),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                result.getString("event_type"),
                result.getString("payload_json"),
                result.getTimestamp("available_at").toInstant()
        ), limit);
    }
}
```

- [ ] **Step 6: Run persistence tests**

Run:

```powershell
.\mvnw.cmd -Dtest=JdbcIdempotencyRepositoryTest,JdbcOutboxRepositoryTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit Task 5**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/platform EchoMindJava/src/test/java/com/echomind/saas/platform
git commit -m "feat: add idempotency and transactional outbox"
```

---

### Task 6: Stable API Error Contract

**Files:**

- Create: `EchoMindJava/src/main/java/com/echomind/saas/api/error/ErrorCode.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/api/error/ApiErrorResponse.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/api/error/GlobalApiExceptionHandler.java`
- Create: `EchoMindJava/src/test/java/com/echomind/saas/api/error/GlobalApiExceptionHandlerTest.java`

**Interfaces:**

- Produces: `ErrorCode.TENANT_ACCESS_DENIED`, `ErrorCode.IDEMPOTENCY_CONFLICT`, `ErrorCode.VALIDATION_FAILED`
- Produces: `ApiErrorResponse(String code, String message, String requestId, boolean retryable)`
- Maps `TenantAccessDeniedException` to HTTP 403.
- Maps `IdempotencyConflictException` to HTTP 409.
- Maps validation failures to HTTP 400.

- [ ] **Step 1: Write the failing handler test**

```java
package com.echomind.saas.api.error;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalApiExceptionHandlerTest {

    @Test
    void mapsTenantDenialToForbidden() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalApiExceptionHandler())
                .build();

        mvc.perform(get("/fail"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @RestController
    static class FailingController {
        @GetMapping("/fail")
        String fail() {
            throw new TenantAccessDeniedException("denied");
        }
    }
}
```

- [ ] **Step 2: Run the test and verify the expected failure**

Run:

```powershell
.\mvnw.cmd -Dtest=GlobalApiExceptionHandlerTest test
```

Expected: compilation fails because the error classes do not exist.

- [ ] **Step 3: Implement the error contract**

```java
package com.echomind.saas.api.error;

public enum ErrorCode {
    TENANT_ACCESS_DENIED,
    IDEMPOTENCY_CONFLICT,
    VALIDATION_FAILED
}
```

```java
package com.echomind.saas.api.error;

public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        boolean retryable
) {
}
```

```java
package com.echomind.saas.api.error;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.platform.idempotency.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class GlobalApiExceptionHandler {

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> tenantDenied(
            TenantAccessDeniedException exception,
            HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, ErrorCode.TENANT_ACCESS_DENIED,
                exception.getMessage(), request, false);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> idempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_CONFLICT,
                exception.getMessage(), request, false);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "request validation failed", request, false);
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            ErrorCode code,
            String message,
            HttpServletRequest request,
            boolean retryable) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = "unavailable";
        }
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code.name(), message, requestId, retryable));
    }
}
```

- [ ] **Step 4: Run the handler and foundation tests**

Run:

```powershell
.\mvnw.cmd -Dtest=GlobalApiExceptionHandlerTest,ActorScopeTest,ModuleBoundaryTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit Task 6**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/api EchoMindJava/src/test/java/com/echomind/saas/api
git commit -m "feat: add stable api error contract"
```

---

## Foundation Verification

- [ ] Run the full available test suite:

```powershell
.\mvnw.cmd test
```

Expected: all tests that do not require unavailable external services pass; Testcontainers tests run when Docker is available.

- [ ] Verify module rules:

```powershell
.\mvnw.cmd -Dtest=ModuleBoundaryTest test
```

Expected: `BUILD SUCCESS`.

- [ ] Verify a clean committed tree:

```powershell
git status --short --branch
```

Expected: branch name only, with no modified or untracked files.

## Follow-On Plans

This foundation produces working, testable infrastructure. The remaining specification is implemented through separate plans:

1. Commerce read model and seeded SaaS tenants.
2. Ticket lifecycle, queue, claim, SLA, and approval.
3. Support turn pipeline, specialists, and tool policy.
4. Knowledge ingestion, hybrid retrieval, reranking, citations, and memory.
5. Asynchronous investigation DAG and RabbitMQ workers.
6. Evaluation datasets, metrics, CI gates, observability, and load evidence.

