package com.echomind.saas.evaluation.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.echomind.saas.rag.application.MarkdownAwareChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class GoldenDatasetLoaderTest {

    private static final String VERSION = "rag-support-golden-v1";
    private static final String ROOT = "eval/golden/" + VERSION + "/";

    private ObjectMapper objectMapper;
    private GoldenDatasetLoader loader;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        loader = new GoldenDatasetLoader(objectMapper, new MarkdownAwareChunker());
    }

    @Test
    void v1HasRequiredCountsSplitsAndReferences() {
        GoldenDataset dataset = loader.load(VERSION);

        assertThat(dataset.documents()).hasSize(12);
        assertThat(dataset.documents())
                .allSatisfy(document -> assertThat(document.expectedChunks()).hasSize(1));
        assertThat(dataset.retrievalCases()).hasSize(30);
        assertThat(dataset.retrievalCases().stream()
                .filter(item -> item.split() == GoldenDataset.Split.DEV))
                .hasSize(20);
        assertThat(dataset.retrievalCases().stream()
                .filter(item -> item.split() == GoldenDataset.Split.HOLDOUT))
                .hasSize(10);
        assertThat(dataset.handoffCases()).hasSize(20);
        assertThat(dataset.handoffCases().stream()
                .filter(item -> item.split() == GoldenDataset.Split.DEV))
                .hasSize(12);
        assertThat(dataset.handoffCases().stream()
                .filter(item -> item.split() == GoldenDataset.Split.HOLDOUT))
                .hasSize(8);
        assertThat(dataset.provenance())
                .isEqualTo(new GoldenDataset.Provenance(
                        "synthetic", false, false, 0));
    }

    @Test
    void fixedChunkIdsMatchDeterministicFormula() {
        GoldenDataset dataset = loader.load(VERSION);

        assertThat(dataset.documents()).allSatisfy(document ->
                assertThat(document.expectedChunks()).allSatisfy(chunk ->
                        assertThat(chunk.chunkId()).isEqualTo(
                                GoldenDatasetLoader.deterministicChunkId(
                                        document.documentId(),
                                        document.version(),
                                        chunk.chunkIndex()))));
    }

    @Test
    void rejectsDigestMismatch() throws IOException {
        String datasetText = resourceText("dataset.json");
        GoldenManifest manifest = manifest();
        GoldenManifest changed = new GoldenManifest(
                manifest.datasetVersion(),
                manifest.schemaVersion(),
                manifest.createdOn(),
                manifest.embeddingModel(),
                "0".repeat(64),
                manifest.documentCount(),
                manifest.chunkCount(),
                manifest.retrievalCaseCount(),
                manifest.handoffCaseCount(),
                manifest.retrievalSplitCounts(),
                manifest.handoffSplitCounts(),
                manifest.provenance());

        assertThatThrownBy(() -> loader.validate(
                        VERSION, changed, dataset(datasetText), datasetText))
                .isInstanceOf(GoldenDatasetException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void rejectsUnsupportedVersionBeforeReadingResources() {
        assertThatThrownBy(() -> loader.load("../dataset"))
                .isInstanceOf(GoldenDatasetException.class)
                .hasMessageContaining("unsupported dataset version");
    }

    @Test
    void rejectsDuplicateRetrievalCaseIds() throws IOException {
        GoldenDataset original = dataset(resourceText("dataset.json"));
        List<GoldenDataset.RetrievalCase> cases =
                new ArrayList<>(original.retrievalCases());
        cases.add(cases.getFirst());
        GoldenDataset duplicate = new GoldenDataset(
                original.datasetVersion(),
                original.schemaVersion(),
                original.tenantId(),
                original.provenance(),
                original.documents(),
                cases,
                original.handoffCases());
        GoldenManifest manifest = manifest();
        GoldenManifest changed = new GoldenManifest(
                manifest.datasetVersion(),
                manifest.schemaVersion(),
                manifest.createdOn(),
                manifest.embeddingModel(),
                GoldenDatasetLoader.sha256(
                        GoldenDatasetLoader.normalizeLineEndings(
                                objectMapper.writeValueAsString(duplicate))),
                manifest.documentCount(),
                manifest.chunkCount(),
                31,
                manifest.handoffCaseCount(),
                Map.of("DEV", 21, "HOLDOUT", 10),
                manifest.handoffSplitCounts(),
                manifest.provenance());
        String duplicateText = objectMapper.writeValueAsString(duplicate);

        assertThatThrownBy(() -> loader.validate(
                        VERSION, changed, duplicate, duplicateText))
                .isInstanceOf(GoldenDatasetException.class)
                .hasMessageContaining("duplicate retrieval case id");
    }

    private GoldenManifest manifest() throws IOException {
        return objectMapper.readValue(resourceText("manifest.json"), GoldenManifest.class);
    }

    private GoldenDataset dataset(String text) throws IOException {
        return objectMapper.readValue(text, GoldenDataset.class);
    }

    private static String resourceText(String name) throws IOException {
        return new String(
                new ClassPathResource(ROOT + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
