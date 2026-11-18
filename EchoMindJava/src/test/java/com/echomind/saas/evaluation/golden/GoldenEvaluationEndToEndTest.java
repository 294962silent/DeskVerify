package com.echomind.saas.evaluation.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.echomind.agent.HandoffPolicy;
import com.echomind.config.EchoMindProperties;
import com.echomind.saas.rag.application.HeuristicReranker;
import com.echomind.saas.rag.application.KnowledgeIndexBuilder;
import com.echomind.saas.rag.application.KnowledgeRetrievalService;
import com.echomind.saas.rag.application.MarkdownAwareChunker;
import com.echomind.saas.rag.infrastructure.JdbcKnowledgeDocumentRepository;
import com.echomind.saas.rag.infrastructure.OnnxBgeEmbeddingProvider;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GoldenEvaluationEndToEndTest {

    private static final String VERSION = "rag-support-golden-v1";

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    private OnnxBgeEmbeddingProvider embeddings;
    private JdbcTemplate jdbc;
    private GoldenEvaluationService service;

    @BeforeEach
    void setUp() {
        EchoMindProperties properties = new EchoMindProperties();
        Assumptions.assumeTrue(
                Files.isRegularFile(Path.of(
                        properties.getRag().getEmbeddingModelPath())),
                "BGE model files are required");
        properties.getEval().setJobsPath(
                Path.of("target/golden-e2e/jobs.json").toAbsolutePath().toString());
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
        JdbcKnowledgeDocumentRepository repository =
                new JdbcKnowledgeDocumentRepository(jdbc, objectMapper);
        embeddings = new OnnxBgeEmbeddingProvider(properties);
        KnowledgeIndexBuilder indexBuilder =
                new KnowledgeIndexBuilder(chunker, embeddings);
        GoldenDatasetLoader loader = new GoldenDatasetLoader(objectMapper, chunker);
        GoldenKnowledgeImporter importer =
                new GoldenKnowledgeImporter(repository, indexBuilder, embeddings);
        GoldenRagEvaluator ragEvaluator = new GoldenRagEvaluator(
                new KnowledgeRetrievalService(
                        repository, embeddings, new HeuristicReranker()));
        HandoffEvaluator handoffEvaluator =
                new HandoffEvaluator(new HandoffPolicy());
        GoldenEvaluationReportWriter writer =
                new GoldenEvaluationReportWriter(objectMapper, properties);
        service = new GoldenEvaluationService(
                loader, importer, ragEvaluator, handoffEvaluator, writer);
    }

    @AfterEach
    void tearDown() {
        if (embeddings != null) {
            embeddings.close();
        }
    }

    @Test
    void executesVersionedGoldenEvaluationThroughRealMysqlAndBge() throws Exception {
        GoldenEvaluationReport report = service.run(VERSION, "golden-e2e");

        assertThat(report.totals().retrievalCases()).isEqualTo(30);
        assertThat(report.totals().handoffCases()).isEqualTo(20);
        assertThat(report.totals().externalLlmTokens()).isZero();
        assertThat(report.retrievalMetrics()).containsKeys("DEV", "HOLDOUT", "ALL");
        assertThat(report.handoffMetrics().get("ALL").accuracy()).isEqualTo(1.0);
        assertThat(report.retrievalMetrics().get("ALL").aclViolations()).isZero();
        assertThat(Files.readString(Path.of(report.markdownPath())))
                .contains("Dataset: `rag-support-golden-v1`")
                .contains("External LLM tokens: `0`");

        Integer documents = jdbc.queryForObject("""
                select count(*)
                from knowledge_document
                where tenant_id = 'tenant-golden-v1'
                """, Integer.class);
        Integer chunks = jdbc.queryForObject("""
                select count(*)
                from knowledge_chunk
                where tenant_id = 'tenant-golden-v1'
                """, Integer.class);
        assertThat(documents).isEqualTo(12);
        assertThat(chunks).isEqualTo(12);
    }
}
