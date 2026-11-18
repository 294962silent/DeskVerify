package com.echomind.saas.rag.domain;

public record RetrievalHit(
        KnowledgeChunk chunk,
        double lexicalScore,
        double vectorScore,
        int lexicalRank,
        int vectorRank,
        double rrfScore,
        double rerankScore
) {
}
