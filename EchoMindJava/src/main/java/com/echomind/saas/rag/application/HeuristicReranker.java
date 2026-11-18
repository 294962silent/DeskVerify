package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.RetrievalHit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class HeuristicReranker implements Reranker {

    @Override
    public List<RetrievalHit> rerank(
            String query, List<RetrievalHit> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = terms(query);
        double maxRrf = candidates.stream()
                .mapToDouble(RetrievalHit::rrfScore)
                .max()
                .orElse(1.0);
        return candidates.stream()
                .map(hit -> withScore(
                        hit,
                        score(query, queryTerms, hit, maxRrf)))
                .sorted(Comparator
                        .comparingDouble(RetrievalHit::rerankScore).reversed()
                        .thenComparing(hit -> hit.chunk().chunkId()))
                .limit(topK)
                .toList();
    }

    private static double score(
            String query,
            Set<String> queryTerms,
            RetrievalHit hit,
            double maxRrf) {
        String content = hit.chunk().content().toLowerCase(Locale.ROOT);
        String title = hit.chunk().documentTitle().toLowerCase(Locale.ROOT);
        String section = String.valueOf(hit.chunk().sectionTitle())
                .toLowerCase(Locale.ROOT);
        long matched = queryTerms.stream().filter(content::contains).count();
        double coverage = queryTerms.isEmpty() ? 0.0 : (double) matched / queryTerms.size();
        double exact = content.contains(query.toLowerCase(Locale.ROOT)) ? 1.0 : 0.0;
        double titleCoverage = queryTerms.isEmpty()
                ? 0.0
                : queryTerms.stream().filter(title::contains).count() / (double) queryTerms.size();
        double sectionCoverage = queryTerms.isEmpty()
                ? 0.0
                : queryTerms.stream().filter(section::contains).count() / (double) queryTerms.size();
        double normalizedRrf = maxRrf == 0.0 ? 0.0 : hit.rrfScore() / maxRrf;
        return round(
                0.22 * normalizedRrf
                        + 0.25 * clamp(hit.lexicalScore())
                        + 0.10 * clamp(hit.vectorScore())
                        + 0.25 * coverage
                        + 0.08 * titleCoverage
                        + 0.07 * exact
                        + 0.03 * sectionCoverage);
    }

    private static Set<String> terms(String query) {
        Set<String> values = new LinkedHashSet<>();
        String normalized = String.valueOf(query == null ? "" : query)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", " ")
                .strip();
        if (normalized.isBlank()) {
            return values;
        }
        for (String word : normalized.split("\\s+")) {
            if (!word.isBlank()) {
                values.add(word);
            }
        }
        String compact = normalized.replace(" ", "");
        for (int size = 2; size <= 3; size++) {
            for (int start = 0; start + size <= compact.length(); start++) {
                values.add(compact.substring(start, start + size));
            }
        }
        return values;
    }

    private static RetrievalHit withScore(RetrievalHit hit, double score) {
        return new RetrievalHit(
                hit.chunk(),
                hit.lexicalScore(),
                hit.vectorScore(),
                hit.lexicalRank(),
                hit.vectorRank(),
                hit.rrfScore(),
                score);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
