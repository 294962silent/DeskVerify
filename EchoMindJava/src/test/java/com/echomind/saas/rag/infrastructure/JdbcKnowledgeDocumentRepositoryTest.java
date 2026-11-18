package com.echomind.saas.rag.infrastructure;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.rag.application.KnowledgeSearchScope;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class JdbcKnowledgeDocumentRepositoryTest {

    private static final TenantId TENANT = new TenantId("tenant-a");

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    private JdbcTemplate jdbc;
    private JdbcKnowledgeDocumentRepository repository;

    @BeforeEach
    void setUp() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        repository = new JdbcKnowledgeDocumentRepository(jdbc, new ObjectMapper());
    }

    @Test
    void unpublishedVersionsStayInvisibleAndPublishingRetiresThePreviousVersion() {
        seedVersion("doc-1", 1, "旧版退款规则", "退款五天内到账", KnowledgeVisibility.TENANT, null);
        repository.publish(TENANT, "doc-1", 1, 1, "local-test");
        seedVersion("doc-1", 2, "新版退款规则", "退款三天内到账", KnowledgeVisibility.TENANT, null);

        assertThat(repository.activeChunks(scope(true)))
                .extracting(chunk -> chunk.versionNumber())
                .containsOnly(1);

        repository.publish(TENANT, "doc-1", 2, 1, "local-test");

        assertThat(repository.activeChunks(scope(true)))
                .extracting(KnowledgeChunk::versionNumber)
                .containsOnly(2);
        assertThat(repository.findVersion(TENANT, "doc-1", 1).orElseThrow().status())
                .isEqualTo(KnowledgeVersionStatus.RETIRED);
    }

    @Test
    void lexicalAndVectorLegsApplyTenantAndCustomerAcl() {
        seedVersion("doc-tenant", 1, "企业版席位说明", "企业版包含二十个席位", KnowledgeVisibility.TENANT, null);
        seedVersion("doc-customer-a", 1, "Acme 专属折扣", "企业版续费享受专属折扣", KnowledgeVisibility.CUSTOMER, "customer-a");
        seedVersion("doc-customer-b", 1, "Beta 专属折扣", "企业版续费享受内部折扣", KnowledgeVisibility.CUSTOMER, "customer-b");
        repository.publish(TENANT, "doc-tenant", 1, 1, "local-test");
        repository.publish(TENANT, "doc-customer-a", 1, 1, "local-test");
        repository.publish(TENANT, "doc-customer-b", 1, 1, "local-test");

        KnowledgeSearchScope customerA = new KnowledgeSearchScope(
                TENANT, false, Set.of("customer-a"));
        KnowledgeSearchScope support = scope(true);

        assertThat(repository.activeChunks(customerA))
                .extracting(chunk -> chunk.documentId())
                .containsExactlyInAnyOrder("doc-tenant", "doc-customer-a");
        assertThat(repository.activeChunks(support))
                .extracting(chunk -> chunk.documentId())
                .containsExactlyInAnyOrder("doc-tenant", "doc-customer-a", "doc-customer-b");
        assertThat(repository.lexicalSearch(customerA, "企业版续费折扣", 10))
                .extracting(hit -> hit.chunk().documentId())
                .doesNotContain("doc-customer-b");
    }

    @Test
    void sameChecksumCannotCreateDuplicateVersionRowsForOneDocument() {
        seedVersion("doc-1", 1, "政策", "退款三天内到账", KnowledgeVisibility.TENANT, null);
        KnowledgeDocumentVersion duplicate = new KnowledgeDocumentVersion(
                "doc-1",
                2,
                KnowledgeVersionStatus.INDEXING,
                checksum("政策", "退款三天内到账", 1, 0),
                "退款三天内到账",
                0,
                "local-test",
                "admin-1",
                null);

        assertThatThrownBy(() -> repository.insertVersion(TENANT, duplicate))
                .hasMessageContaining("Duplicate entry");
    }

    private void seedVersion(
            String documentId,
            int version,
            String title,
            String content,
            KnowledgeVisibility visibility,
            String customerId) {
        if (repository.findDocument(TENANT, documentId).isEmpty()) {
            repository.insertDocument(TENANT, new KnowledgeDocument(
                    documentId, title, "ACTIVE", null, "admin-1"));
        }
        CleanChunk chunk = chunk(
                documentId, version, title, content, visibility, customerId);
        repository.insertVersion(TENANT, new KnowledgeDocumentVersion(
                documentId,
                version,
                KnowledgeVersionStatus.INDEXING,
                chunk.checksum(),
                content,
                0,
                "local-test",
                "admin-1",
                null));
        repository.markIndexingAndInsertChunks(
                TENANT,
                documentId,
                version,
                "local-test",
                List.of(chunk.knowledgeChunk()));
    }

    private CleanChunk chunk(
            String documentId,
            int version,
            String title,
            String content,
            KnowledgeVisibility visibility,
            String customerId) {
        String checksum = checksum(title, content, version, 0);
        return new CleanChunk(
                checksum,
                new KnowledgeChunk(
                        "chunk-" + documentId + "-" + version,
                        documentId,
                        version,
                        0,
                        title,
                        content,
                        null,
                        title,
                        title,
                        0,
                        content.length(),
                        checksum,
                        visibility,
                        customerId,
                        new double[]{1.0, 0.0, 0.0}));
    }

    private static String checksum(String title, String content, int version, int index) {
        String value = title + ":" + content + ":" + version + ":" + index;
        return String.format("%064x", value.hashCode()).replace(' ', '0');
    }

    private static KnowledgeSearchScope scope(boolean tenantWide) {
        return new KnowledgeSearchScope(TENANT, tenantWide, Set.of());
    }

    private record CleanChunk(String checksum, KnowledgeChunk knowledgeChunk) {
    }
}
