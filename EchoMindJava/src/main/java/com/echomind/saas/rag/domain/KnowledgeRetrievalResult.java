package com.echomind.saas.rag.domain;

import java.util.List;

public record KnowledgeRetrievalResult(
        String query,
        List<RetrievalHit> hits,
        List<KnowledgeCitation> citations,
        List<String> degradedModes,
        long latencyMs
) {
    public KnowledgeRetrievalResult {
        hits = List.copyOf(hits);
        citations = List.copyOf(citations);
        degradedModes = List.copyOf(degradedModes);
    }
}
