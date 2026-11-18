package com.echomind.saas.rag.infrastructure;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.rag.application.KnowledgeChunkAclSummary;
import com.echomind.saas.rag.application.KnowledgeSearchScope;
import com.echomind.saas.rag.application.KnowledgeEmbeddingMigrationCandidate;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcKnowledgeDocumentRepository(jdbc, new ObjectMapper());
    }

    @Test
    void unpublishedVersionsStayInvisibleAndPublishingRetiresThePreviousVersion() {
        seedVersion("doc-1", 1, "旧版退款规则", "退款五天内到账", KnowledgeVisibility.TENANT, null);
        repository.publish(TENANT, "doc-1", 1, 1, "local-test");
        seedVersion("doc-1", 2, "新版退款规则", "退款三天内到账", KnowledgeVisibility.TENANT, null);

        assertThat(repository.activeChunks(scope(true), "local-test"))
                .extracting(chunk -> chunk.versionNumber())
                .containsOnly(1);

        repository.publish(TENANT, "doc-1", 2, 1, "local-test");

        assertThat(repository.activeChunks(scope(true), "local-test"))
                .extracting(KnowledgeChunk::versionNumber)
                .containsOnly(2);
        assertThat(repository.findVersion(TENANT, "doc-1", 1).orElseThrow().status())
                .isEqualTo(KnowledgeVersionStatus.RETIRED);
    }

    @Test
    void publishWaitsForTheDocumentLockBeforeMutatingVersions() throws Exception {
        seedVersion("doc-1", 1, "旧版退款规则", "退款五天内到账", KnowledgeVisibility.TENANT, null);
        repository.publish(TENANT, "doc-1", 1, 1, "local-test");
        seedVersion("doc-1", 2, "新版退款规则", "退款三天内到账", KnowledgeVisibility.TENANT, null);

        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseRowLock = new CountDownLatch(1);
        CountDownLatch publishStarted = new CountDownLatch(1);

        try {
            Future<?> lockHolder = executor.submit(() ->
                    transactions.executeWithoutResult(status -> {
                        repository.findDocumentForUpdate(TENANT, "doc-1")
                                .orElseThrow();
                        rowLocked.countDown();
                        await(releaseRowLock);
                    }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> publisher = executor.submit(() -> {
                publishStarted.countDown();
                transactions.executeWithoutResult(status -> repository.publish(
                        TENANT, "doc-1", 2, 1, "local-test"));
            });
            assertThat(publishStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> publisher.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseRowLock.countDown();
            publisher.get(5, TimeUnit.SECONDS);
            lockHolder.get(5, TimeUnit.SECONDS);

            assertThat(repository.findDocument(TENANT, "doc-1").orElseThrow().currentVersion())
                    .isEqualTo(2);
            assertThat(repository.findVersion(TENANT, "doc-1", 1).orElseThrow().status())
                    .isEqualTo(KnowledgeVersionStatus.RETIRED);
            assertThat(repository.findVersion(TENANT, "doc-1", 2).orElseThrow().status())
                    .isEqualTo(KnowledgeVersionStatus.PUBLISHED);
        } finally {
            releaseRowLock.countDown();
            executor.shutdownNow();
        }
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

        assertThat(repository.activeChunks(customerA, "local-test"))
                .extracting(chunk -> chunk.documentId())
                .containsExactlyInAnyOrder("doc-tenant", "doc-customer-a");
        assertThat(repository.activeChunks(support, "local-test"))
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

    @Test
    void sameChecksumCanBeReindexedForADifferentEmbeddingModel() {
        seedVersion("doc-1", 1, "政策", "退款三天内到账", KnowledgeVisibility.TENANT, null);
        String sourceChecksum = repository
                .findVersion(TENANT, "doc-1", 1)
                .orElseThrow()
                .sourceChecksum();

        repository.insertVersion(TENANT, new KnowledgeDocumentVersion(
                "doc-1",
                2,
                KnowledgeVersionStatus.INDEXING,
                sourceChecksum,
                "退款三天内到账",
                0,
                "bge-small-zh-v1.5-onnx-fp32",
                "admin-1",
                null));

        assertThat(repository.findVersionByChecksum(
                        TENANT,
                        "doc-1",
                        sourceChecksum,
                        "local-test"))
                .get()
                .extracting(KnowledgeDocumentVersion::versionNumber)
                .isEqualTo(1);
        assertThat(repository.findVersionByChecksum(
                        TENANT,
                        "doc-1",
                        sourceChecksum,
                        "bge-small-zh-v1.5-onnx-fp32"))
                .get()
                .extracting(KnowledgeDocumentVersion::versionNumber)
                .isEqualTo(2);
    }

    @Test
    void countsPublishedVectorsFromOtherEmbeddingModelsWithoutCrossingAcl() {
        seedVersion(
                "doc-tenant",
                1,
                "企业版席位说明",
                "企业版包含二十个席位",
                KnowledgeVisibility.TENANT,
                null,
                "local-hash-char-ngram-v1");
        seedVersion(
                "doc-customer",
                1,
                "Acme 专属折扣",
                "企业版续费享受专属折扣",
                KnowledgeVisibility.CUSTOMER,
                "customer-a",
                "local-hash-char-ngram-v1");
        repository.publish(
                TENANT, "doc-tenant", 1, 1, "local-hash-char-ngram-v1");
        repository.publish(
                TENANT, "doc-customer", 1, 1, "local-hash-char-ngram-v1");

        KnowledgeSearchScope customerB = new KnowledgeSearchScope(
                TENANT, false, Set.of("customer-b"));

        assertThat(repository.countActiveChunksWithOtherEmbeddingModel(
                        scope(true), "bge-small-zh-v1.5-onnx-fp32"))
                .isEqualTo(2);
        assertThat(repository.countActiveChunksWithOtherEmbeddingModel(
                        customerB, "bge-small-zh-v1.5-onnx-fp32"))
                .isEqualTo(1);
        assertThat(repository.countActiveChunksWithOtherEmbeddingModel(
                        scope(true), "local-hash-char-ngram-v1"))
                .isZero();
    }

    @Test
    void stalePublishedVersionsAreTenantScopedCursorPageableAndAclSummarized() {
        TenantId otherTenant = new TenantId("tenant-b");
        seedVersion(
                TENANT,
                "doc-a",
                1,
                "退款政策",
                "退款五到七个工作日到账",
                KnowledgeVisibility.TENANT,
                null,
                "local-hash-char-ngram-v1");
        seedVersion(
                TENANT,
                "doc-b",
                1,
                "Acme 退款政策",
                "Acme 退款三天内到账",
                KnowledgeVisibility.CUSTOMER,
                "customer-a",
                "local-hash-char-ngram-v1");
        seedVersion(
                TENANT,
                "doc-current",
                1,
                "当前模型文档",
                "这篇文章已经使用 BGE",
                KnowledgeVisibility.TENANT,
                null,
                "bge-small-zh-v1.5-onnx-fp32");
        seedVersion(
                otherTenant,
                "doc-other",
                1,
                "其他租户",
                "不应被当前租户扫描",
                KnowledgeVisibility.TENANT,
                null,
                "local-hash-char-ngram-v1");
        repository.publish(
                TENANT, "doc-a", 1, 1, "local-hash-char-ngram-v1");
        repository.publish(
                TENANT, "doc-b", 1, 1, "local-hash-char-ngram-v1");
        repository.publish(
                TENANT, "doc-current", 1, 1, "bge-small-zh-v1.5-onnx-fp32");
        repository.publish(
                otherTenant, "doc-other", 1, 1, "local-hash-char-ngram-v1");

        assertThat(repository.staleEmbeddingCandidates(
                        TENANT,
                        "bge-small-zh-v1.5-onnx-fp32",
                        null,
                        1))
                .extracting(KnowledgeEmbeddingMigrationCandidate::documentId)
                .containsExactly("doc-a");
        assertThat(repository.staleEmbeddingCandidates(
                        TENANT,
                        "bge-small-zh-v1.5-onnx-fp32",
                        "doc-a",
                        10))
                .extracting(KnowledgeEmbeddingMigrationCandidate::documentId)
                .containsExactly("doc-b");
        assertThat(repository.countStalePublishedVersions(
                        TENANT, "bge-small-zh-v1.5-onnx-fp32"))
                .isEqualTo(2);
        assertThat(repository.versionChunkAclSummaries(TENANT, "doc-b", 1))
                .containsExactly(new KnowledgeChunkAclSummary(
                        KnowledgeVisibility.CUSTOMER, "customer-a", 1));
    }

    private void seedVersion(
            String documentId,
            int version,
            String title,
            String content,
            KnowledgeVisibility visibility,
            String customerId) {
        seedVersion(
                documentId,
                version,
                title,
                content,
                visibility,
                customerId,
                "local-test");
    }

    private void seedVersion(
            String documentId,
            int version,
            String title,
            String content,
            KnowledgeVisibility visibility,
            String customerId,
            String embeddingModel) {
        seedVersion(
                TENANT,
                documentId,
                version,
                title,
                content,
                visibility,
                customerId,
                embeddingModel);
    }

    private void seedVersion(
            TenantId tenantId,
            String documentId,
            int version,
            String title,
            String content,
            KnowledgeVisibility visibility,
            String customerId,
            String embeddingModel) {
        if (repository.findDocument(tenantId, documentId).isEmpty()) {
            repository.insertDocument(tenantId, new KnowledgeDocument(
                    documentId, title, "ACTIVE", null, "admin-1"));
        }
        CleanChunk chunk = chunk(
                documentId, version, title, content, visibility, customerId);
        repository.insertVersion(tenantId, new KnowledgeDocumentVersion(
                documentId,
                version,
                KnowledgeVersionStatus.INDEXING,
                chunk.checksum(),
                content,
                0,
                embeddingModel,
                "admin-1",
                null));
        repository.markIndexingAndInsertChunks(
                tenantId,
                documentId,
                version,
                embeddingModel,
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for row-lock release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for row-lock release", exception);
        }
    }

    private record CleanChunk(String checksum, KnowledgeChunk knowledgeChunk) {
    }
}
