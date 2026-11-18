package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.RetrievalHit;
import java.util.List;

public interface Reranker {
    List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates, int topK);
}
