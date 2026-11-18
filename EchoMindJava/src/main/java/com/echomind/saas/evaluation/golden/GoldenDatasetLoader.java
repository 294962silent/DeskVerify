package com.echomind.saas.evaluation.golden;

import com.echomind.saas.rag.application.ChunkDraft;
import com.echomind.saas.rag.application.DocumentChunker;
import com.echomind.saas.rag.application.KnowledgeIndexBuilder;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class GoldenDatasetLoader {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9-]{2,80}");
    private static final String RESOURCE_ROOT = "eval/golden/";
    private static final Set<String> HANDOFF_REASONS = Set.of(
            "EXPLICIT_HUMAN_REQUEST",
            "CRITICAL_URGENCY",
            "SECURITY_OR_PRIVACY",
            "MONETARY_DISPUTE",
            "IRREVERSIBLE_ACTION",
            "KNOWLEDGE_SUFFICIENT",
            "SELF_SERVICE_AVAILABLE");

    private final ObjectMapper objectMapper;
    private final DocumentChunker chunker;

    public GoldenDatasetLoader(ObjectMapper objectMapper, DocumentChunker chunker) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
    }

    public GoldenDataset load(String datasetVersion) {
        return loadWithManifest(datasetVersion).dataset();
    }

    public LoadedDataset loadWithManifest(String datasetVersion) {
        String version = requireVersion(datasetVersion);
        String root = RESOURCE_ROOT + version + "/";
        GoldenManifest manifest = readJson(root + "manifest.json", GoldenManifest.class);
        String datasetText = readText(root + "dataset.json");
        GoldenDataset dataset = parse(datasetText, GoldenDataset.class);
        validate(datasetVersion, manifest, dataset, datasetText);
        return new LoadedDataset(dataset, manifest);
    }

    void validate(
            String expectedVersion,
            GoldenManifest manifest,
            GoldenDataset dataset,
            String datasetText) {
        if (!expectedVersion.equals(manifest.datasetVersion())
                || !expectedVersion.equals(dataset.datasetVersion())) {
            throw GoldenDatasetException.integrity("dataset version mismatch");
        }
        if (manifest.schemaVersion() != dataset.schemaVersion()) {
            throw GoldenDatasetException.integrity("dataset schema version mismatch");
        }
        if (!sha256(normalizeLineEndings(datasetText)).equals(manifest.datasetFileSha256())) {
            throw GoldenDatasetException.integrity("dataset digest mismatch");
        }
        if (!manifest.provenance().equals(dataset.provenance())) {
            throw GoldenDatasetException.integrity("dataset provenance mismatch");
        }
        validateProvenance(dataset.provenance());
        validateCounts(dataset, manifest);
        validateDocuments(dataset.documents());
        validateRetrievalCases(dataset);
        validateHandoffCases(dataset);
    }

    private void validateCounts(GoldenDataset dataset, GoldenManifest manifest) {
        int chunks = dataset.documents().stream()
                .mapToInt(document -> document.expectedChunks().size())
                .sum();
        if (manifest.documentCount() != dataset.documents().size()
                || manifest.chunkCount() != chunks
                || manifest.retrievalCaseCount() != dataset.retrievalCases().size()
                || manifest.handoffCaseCount() != dataset.handoffCases().size()) {
            throw GoldenDatasetException.integrity("dataset counts mismatch");
        }
        assertSplitCounts(
                "retrieval",
                dataset.retrievalCases().stream()
                        .map(GoldenDataset.RetrievalCase::split)
                        .toList(),
                manifest.retrievalSplitCounts());
        assertSplitCounts(
                "handoff",
                dataset.handoffCases().stream()
                        .map(GoldenDataset.HandoffCase::split)
                        .toList(),
                manifest.handoffSplitCounts());
    }

    private void validateProvenance(GoldenDataset.Provenance provenance) {
        if (!"synthetic".equals(provenance.kind())
                || provenance.humanReviewed()
                || provenance.statisticallyRepresentative()
                || provenance.externalLlmTokens() != 0) {
            throw GoldenDatasetException.integrity("v1 provenance constraints violated");
        }
    }

    private void validateDocuments(List<GoldenDataset.Document> documents) {
        Set<String> documentIds = new HashSet<>();
        Set<String> chunkIds = new HashSet<>();
        for (GoldenDataset.Document document : documents) {
            if (!documentIds.add(document.documentId())) {
                throw GoldenDatasetException.integrity(
                        "duplicate document id: " + document.documentId());
            }
            validateDocumentAcl(document);
            String normalized = normalizeText(document.content());
            if (!sha256(normalized).equals(document.normalizedContentSha256())) {
                throw GoldenDatasetException.integrity(
                        "document checksum mismatch: " + document.documentId());
            }
            List<ChunkDraft> actualChunks = chunker.chunk(normalized);
            if (actualChunks.size() != document.expectedChunks().size()) {
                throw GoldenDatasetException.integrity(
                        "document chunk count mismatch: " + document.documentId());
            }
            for (int index = 0; index < actualChunks.size(); index++) {
                ChunkDraft actual = actualChunks.get(index);
                GoldenDataset.ChunkExpectation expected =
                        document.expectedChunks().get(index);
                String expectedChunkId = deterministicChunkId(
                        document.documentId(), document.version(), index);
                if (expected.chunkIndex() != actual.chunkIndex()
                        || !expected.chunkId().equals(expectedChunkId)
                        || !expected.checksum().equals(actual.checksum())
                        || !expected.sectionTitle().equals(actual.sectionTitle())
                        || !expected.headingPath().equals(actual.headingPath())) {
                    throw GoldenDatasetException.integrity(
                            "document chunk expectation mismatch: "
                                    + document.documentId()
                                    + "#"
                                    + index);
                }
                if (!chunkIds.add(expected.chunkId())) {
                    throw GoldenDatasetException.integrity(
                            "duplicate chunk id: " + expected.chunkId());
                }
            }
        }
    }

    private void validateDocumentAcl(GoldenDataset.Document document) {
        if (document.visibility() == KnowledgeVisibility.TENANT
                && document.customerId() != null) {
            throw GoldenDatasetException.integrity(
                    "tenant document must not have customerId: " + document.documentId());
        }
        if (document.visibility() == KnowledgeVisibility.CUSTOMER
                && (document.customerId() == null || document.customerId().isBlank())) {
            throw GoldenDatasetException.integrity(
                    "customer document requires customerId: " + document.documentId());
        }
    }

    private void validateRetrievalCases(GoldenDataset dataset) {
        Map<String, GoldenDataset.ChunkExpectation> chunks = expectedChunks(dataset.documents());
        Set<String> caseIds = new HashSet<>();
        for (GoldenDataset.RetrievalCase evaluationCase : dataset.retrievalCases()) {
            if (!caseIds.add(evaluationCase.caseId())) {
                throw GoldenDatasetException.integrity(
                        "duplicate retrieval case id: " + evaluationCase.caseId());
            }
            GoldenDataset.ChunkExpectation chunk =
                    chunks.get(evaluationCase.expectedChunkId());
            if (chunk == null
                    || !chunk.headingPath().equals(evaluationCase.expectedHeadingPath())) {
                throw GoldenDatasetException.integrity(
                        "retrieval case has unknown chunk: " + evaluationCase.caseId());
            }
            GoldenDataset.Document document = dataset.documents().stream()
                    .filter(item -> item.documentId().equals(
                            evaluationCase.expectedDocumentId()))
                    .findFirst()
                    .orElseThrow(() -> GoldenDatasetException.integrity(
                            "retrieval case has unknown document: "
                                    + evaluationCase.caseId()));
            if (document.version() != evaluationCase.expectedVersionNumber()
                    || !document.expectedChunks().contains(chunk)) {
                throw GoldenDatasetException.integrity(
                        "retrieval case version mismatch: " + evaluationCase.caseId());
            }
        }
    }

    private void validateHandoffCases(GoldenDataset dataset) {
        Set<String> caseIds = new HashSet<>();
        Map<GoldenDataset.Split, int[]> counts = new HashMap<>();
        for (GoldenDataset.HandoffCase evaluationCase : dataset.handoffCases()) {
            if (!caseIds.add(evaluationCase.caseId())) {
                throw GoldenDatasetException.integrity(
                        "duplicate handoff case id: " + evaluationCase.caseId());
            }
            if (!HANDOFF_REASONS.contains(evaluationCase.expectedReason())) {
                throw GoldenDatasetException.integrity(
                        "unknown handoff reason: " + evaluationCase.caseId());
            }
            int[] splitCounts = counts.computeIfAbsent(
                    evaluationCase.split(), ignored -> new int[2]);
            splitCounts[evaluationCase.expectedHandoff() ? 1 : 0]++;
        }
        if (counts.getOrDefault(GoldenDataset.Split.DEV, new int[2])[0] != 6
                || counts.getOrDefault(GoldenDataset.Split.DEV, new int[2])[1] != 6
                || counts.getOrDefault(GoldenDataset.Split.HOLDOUT, new int[2])[0] != 4
                || counts.getOrDefault(GoldenDataset.Split.HOLDOUT, new int[2])[1] != 4) {
            throw GoldenDatasetException.integrity("handoff split balance mismatch");
        }
    }

    private static Map<String, GoldenDataset.ChunkExpectation> expectedChunks(
            List<GoldenDataset.Document> documents) {
        Map<String, GoldenDataset.ChunkExpectation> chunks = new HashMap<>();
        for (GoldenDataset.Document document : documents) {
            for (GoldenDataset.ChunkExpectation chunk : document.expectedChunks()) {
                chunks.put(chunk.chunkId(), chunk);
            }
        }
        return chunks;
    }

    private static void assertSplitCounts(
            String label,
            List<GoldenDataset.Split> splits,
            Map<String, Integer> expected) {
        EnumSet<GoldenDataset.Split> values = EnumSet.allOf(GoldenDataset.Split.class);
        for (GoldenDataset.Split split : values) {
            long actual = splits.stream().filter(split::equals).count();
            if (actual != expected.getOrDefault(split.name(), -1)) {
                throw GoldenDatasetException.integrity(
                        label + " split count mismatch for " + split);
            }
        }
    }

    private <T> T readJson(String path, Class<T> type) {
        return parse(readText(path), type);
    }

    private <T> T parse(String text, Class<T> type) {
        try {
            return objectMapper.readValue(text, type);
        } catch (IOException exception) {
            throw new GoldenDatasetException(
                    "GOLDEN_DATASET_INTEGRITY_FAILED",
                    "invalid dataset json: " + type.getSimpleName(),
                    exception);
        }
    }

    private static String readText(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw GoldenDatasetException.integrity("dataset resource not found: " + path);
        }
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GoldenDatasetException(
                    "GOLDEN_DATASET_INTEGRITY_FAILED",
                    "failed to read dataset resource: " + path,
                    exception);
        }
    }

    private static String requireVersion(String datasetVersion) {
        if (datasetVersion == null || !VERSION_PATTERN.matcher(datasetVersion).matches()) {
            throw new GoldenDatasetException(
                    "GOLDEN_DATASET_NOT_FOUND", "unsupported dataset version");
        }
        return datasetVersion;
    }

    static String normalizeText(String raw) {
        return KnowledgeIndexBuilder.normalizeText(raw);
    }

    static String deterministicChunkId(String documentId, int version, int index) {
        return KnowledgeIndexBuilder.deterministicChunkId(documentId, version, index);
    }

    static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String sha256(String value) {
        return KnowledgeIndexBuilder.sha256(value);
    }

    public record LoadedDataset(GoldenDataset dataset, GoldenManifest manifest) {
    }
}
