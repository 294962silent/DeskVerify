package com.echomind.saas.rag.application;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;

public interface KnowledgeRetrieval {
    KnowledgeRetrievalResult search(String query, int topK, ActorScope actor);
}
