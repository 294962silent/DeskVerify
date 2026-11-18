package com.echomind.saas.rag.application;

public interface EmbeddingProvider {
    String modelName();

    double[] embed(String text);
}
