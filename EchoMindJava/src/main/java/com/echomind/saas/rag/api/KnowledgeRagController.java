package com.echomind.saas.rag.api;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.rag.application.AddKnowledgeVersionCommand;
import com.echomind.saas.rag.application.CreateKnowledgeDocumentCommand;
import com.echomind.saas.rag.application.KnowledgeDocumentView;
import com.echomind.saas.rag.application.KnowledgeEmbeddingMigrationCommand;
import com.echomind.saas.rag.application.KnowledgeEmbeddingMigrationResult;
import com.echomind.saas.rag.application.KnowledgeEmbeddingMigrationService;
import com.echomind.saas.rag.application.KnowledgeIngestionService;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.saas.rag.application.PublishKnowledgeVersionCommand;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class KnowledgeRagController {

    private final KnowledgeIngestionService ingestion;
    private final KnowledgeRetrieval retrieval;
    private final KnowledgeEmbeddingMigrationService embeddingMigration;

    public KnowledgeRagController(
            KnowledgeIngestionService ingestion,
            KnowledgeRetrieval retrieval,
            KnowledgeEmbeddingMigrationService embeddingMigration) {
        this.ingestion = ingestion;
        this.retrieval = retrieval;
        this.embeddingMigration = embeddingMigration;
    }

    @PostMapping("/knowledge-documents")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<KnowledgeDocumentResponse> create(
            @Valid @RequestBody KnowledgeDocumentRequest request,
            @AuthenticationPrincipal ActorScope actor) {
        KnowledgeDocumentView view = ingestion.create(
                new CreateKnowledgeDocumentCommand(
                        request.title(),
                        request.content(),
                        request.visibility(),
                        request.customerId()),
                actor);
        return ResponseEntity.created(URI.create(
                        "/api/v1/knowledge-documents/" + view.document().documentId()))
                .body(KnowledgeDocumentResponse.from(view));
    }

    @PostMapping("/knowledge-documents/{documentId}/versions")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<KnowledgeDocumentResponse.VersionResponse> addVersion(
            @PathVariable String documentId,
            @Valid @RequestBody KnowledgeVersionRequest request,
            @AuthenticationPrincipal ActorScope actor) {
        KnowledgeDocumentVersion version = ingestion.addVersion(
                documentId,
                new AddKnowledgeVersionCommand(
                        request.content(),
                        request.visibility(),
                        request.customerId()),
                actor);
        return ResponseEntity.status(201)
                .body(KnowledgeDocumentResponse.VersionResponse.from(version));
    }

    @PostMapping("/knowledge-documents/{documentId}/publish")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public KnowledgeDocumentResponse.VersionResponse publish(
            @PathVariable String documentId,
            @Valid @RequestBody KnowledgePublishRequest request,
            @AuthenticationPrincipal ActorScope actor) {
        return KnowledgeDocumentResponse.VersionResponse.from(
                ingestion.publish(
                        documentId,
                        new PublishKnowledgeVersionCommand(request.versionNumber()),
                        actor));
    }

    @GetMapping("/knowledge-documents/{documentId}/versions")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public List<KnowledgeDocumentResponse.VersionResponse> versions(
            @PathVariable String documentId,
            @AuthenticationPrincipal ActorScope actor) {
        return ingestion.versions(documentId, actor).stream()
                .map(KnowledgeDocumentResponse.VersionResponse::from)
                .toList();
    }

    @PostMapping("/knowledge/embedding-migrations")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public KnowledgeEmbeddingMigrationResponse migrateEmbeddings(
            @Valid @RequestBody KnowledgeEmbeddingMigrationRequest request,
            @AuthenticationPrincipal ActorScope actor) {
        KnowledgeEmbeddingMigrationResult result = embeddingMigration.migrate(
                new KnowledgeEmbeddingMigrationCommand(
                        request.dryRun(),
                        request.pageSize(),
                        request.afterDocumentId()),
                actor);
        return KnowledgeEmbeddingMigrationResponse.from(result);
    }

    @PostMapping("/knowledge/search")
    public KnowledgeSearchResponse search(
            @Valid @RequestBody KnowledgeSearchRequest request,
            @AuthenticationPrincipal ActorScope actor) {
        return KnowledgeSearchResponse.from(
                retrieval.search(request.query(), request.topK(), actor));
    }

    @GetMapping("/knowledge/stats")
    public Map<String, Object> stats(@AuthenticationPrincipal ActorScope actor) {
        return Map.of("published_chunks", ingestion.publishedChunks(actor));
    }
}
