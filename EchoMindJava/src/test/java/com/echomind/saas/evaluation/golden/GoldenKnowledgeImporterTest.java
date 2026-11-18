package com.echomind.saas.evaluation.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.echomind.config.EchoMindProperties;
import com.echomind.saas.rag.application.KnowledgeIndexBuilder;
import com.echomind.saas.rag.application.MarkdownAwareChunker;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import com.echomind.saas.rag.infrastructure.JdbcKnowledgeDocumentRepository;
import com.echomind.saas.rag.infrastructure.OnnxBgeEmbeddingProvider;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GoldenKnowledgeImporterTest {

    private static final String VERSION = "rag-support-golden-v1";
    private static final EchoMindProperties PROPERTIES = new EchoMindProperties();

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    private JdbcTemplate jdbc;
    private JdbcKnowledgeDocumentRepository repository;
    private GoldenDataset dataset;
    private OnnxBgeEmbeddingProvider embeddings;
    private GoldenKnowledgeImporter importer;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                Files.isRegularFile(Path.of(
                        PROPERTIES.getRag().getEmbeddingModelPath())),
                "BGE model files are required");
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        MarkdownAwareChunker chunker = new MarkdownAwareChunker();
        repository = new JdbcKnowledgeDocumentRepository(jdbc, objectMapper);
        embeddings = new OnnxBgeEmbeddingProvider(PROPERTIES);
        KnowledgeIndexBuilder indexBuilder =
                new KnowledgeIndexBuilder(chunker, embeddings);
        dataset = new GoldenDatasetLoader(objectMapper, chunker).load(VERSION);
        importer = new GoldenKnowledgeImporter(repository, indexBuilder, embeddings);
    }

    @AfterEach
    void tearDown() {
        if (embeddings != null) {
            embeddings.close();
        }
    }

    @Test
    void importsFixedDocumentsAndChunksIdempotently() {
        importer.ensureImported(dataset);
        Integer firstDocuments = count("knowledge_document");
        Integer firstChunks = count("knowledge_chunk");
        Integer firstVersions = count("knowledge_document_version");

        importer.ensureImported(dataset);

        assertThat(firstDocuments).isEqualTo(12);
        assertThat(firstVersions).isEqualTo(12);
        assertThat(firstChunks).isEqualTo(12);
        assertThat(count("knowledge_document")).isEqualTo(firstDocuments);
        assertThat(count("knowledge_document_version")).isEqualTo(firstVersions);
        assertThat(count("knowledge_chunk")).isEqualTo(firstChunks);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from knowledge_document_version
                where tenant_id = ?
                  and status = 'PUBLISHED'
                  and version_number = 1
                  and chunk_count = 1
                """, Integer.class, dataset.tenantId())).isEqualTo(12);
        assertThat(repository.findVersion(
                        new com.echomind.saas.identity.domain.TenantId(dataset.tenantId()),
                        "golden-doc-customer-a-discount",
                        1)
                .orElseThrow()
                .status()).isEqualTo(KnowledgeVersionStatus.PUBLISHED);
    }

    private Integer count(String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = ?",
                Integer.class,
                dataset.tenantId());
    }
}
