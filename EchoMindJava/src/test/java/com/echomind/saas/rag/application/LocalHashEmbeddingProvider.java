package com.echomind.saas.rag.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LocalHashEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSIONS = 384;

    @Override
    public String modelName() {
        return "local-hash-char-ngram-v1";
    }

    @Override
    public double[] embed(String text) {
        double[] vector = new double[DIMENSIONS];
        Set<String> features = new HashSet<>(features(text));
        for (String feature : features) {
            int hash = feature.hashCode();
            int index = Math.floorMod(hash, vector.length);
            vector[index] += (hash & 1) == 0 ? 1.0 : -1.0;
        }
        return vector;
    }

    private static List<String> features(String text) {
        String normalized = String.valueOf(text == null ? "" : text)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", " ")
                .strip();
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> features = new ArrayList<>();
        for (String word : normalized.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            features.add("w:" + word);
            for (int size = 2; size <= 4; size++) {
                for (int start = 0; start + size <= word.length(); start++) {
                    features.add("c:" + word.substring(start, start + size));
                }
            }
        }
        return features;
    }
}
