package com.echomind.saas.rag.application;

public interface EmbeddingProvider {
    String modelName();

    double[] embed(String text);

    default double[] embedQuery(String text) {
        return embed(text);
    }

    default boolean available() {
        return true;
    }
}
