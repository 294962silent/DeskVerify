package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.KnowledgeChunk;

public record LexicalSearchHit(
        KnowledgeChunk chunk,
        double score
) {
}
