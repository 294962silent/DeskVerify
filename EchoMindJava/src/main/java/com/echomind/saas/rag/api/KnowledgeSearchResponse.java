package com.echomind.saas.rag.api;

import com.echomind.saas.rag.domain.KnowledgeCitation;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;
import com.echomind.saas.rag.domain.RetrievalHit;
import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        List<HitResponse> hits,
        List<CitationResponse> citations,
        List<String> degradedModes,
        long latencyMs
) {
    public static KnowledgeSearchResponse from(KnowledgeRetrievalResult result) {
        return new KnowledgeSearchResponse(
                result.query(),
                result.hits().stream().map(HitResponse::from).toList(),
                result.citations().stream().map(CitationResponse::from).toList(),
                result.degradedModes(),
                result.latencyMs());
    }

    public record HitResponse(
            String chunkId,
            String documentId,
            String documentTitle,
            int versionNumber,
            int chunkIndex,
            Integer pageNumber,
            String sectionTitle,
            String headingPath,
            String content,
            int charStart,
            int charEnd,
            double lexicalScore,
            double vectorScore,
            Integer lexicalRank,
            Integer vectorRank,
            double rrfScore,
            double rerankScore
    ) {
        public static HitResponse from(RetrievalHit hit) {
            return new HitResponse(
                    hit.chunk().chunkId(),
                    hit.chunk().documentId(),
                    hit.chunk().documentTitle(),
                    hit.chunk().versionNumber(),
                    hit.chunk().chunkIndex(),
                    hit.chunk().pageNumber(),
                    hit.chunk().sectionTitle(),
                    hit.chunk().headingPath(),
                    hit.chunk().content(),
                    hit.chunk().charStart(),
                    hit.chunk().charEnd(),
                    hit.lexicalScore(),
                    hit.vectorScore(),
                    hit.lexicalRank() == Integer.MAX_VALUE ? null : hit.lexicalRank(),
                    hit.vectorRank() == Integer.MAX_VALUE ? null : hit.vectorRank(),
                    hit.rrfScore(),
                    hit.rerankScore());
        }
    }

    public record CitationResponse(
            String citationId,
            String chunkId,
            String documentId,
            String documentTitle,
            int versionNumber,
            Integer pageNumber,
            String sectionTitle,
            String headingPath,
            String excerpt,
            int charStart,
            int charEnd,
            double score
    ) {
        public static CitationResponse from(KnowledgeCitation citation) {
            return new CitationResponse(
                    citation.citationId(),
                    citation.chunkId(),
                    citation.documentId(),
                    citation.documentTitle(),
                    citation.versionNumber(),
                    citation.pageNumber(),
                    citation.sectionTitle(),
                    citation.headingPath(),
                    citation.excerpt(),
                    citation.charStart(),
                    citation.charEnd(),
                    citation.score());
        }
    }
}
