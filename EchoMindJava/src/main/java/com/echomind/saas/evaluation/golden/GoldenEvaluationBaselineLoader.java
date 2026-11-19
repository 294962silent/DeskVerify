package com.echomind.saas.evaluation.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class GoldenEvaluationBaselineLoader {

    private static final Pattern SAFE_VERSION =
            Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

    private final ObjectMapper objectMapper;

    public GoldenEvaluationBaselineLoader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public GoldenEvaluationBaseline load(String datasetVersion) {
        if (datasetVersion == null || !SAFE_VERSION.matcher(datasetVersion).matches()) {
            throw new GoldenDatasetException(
                    "GOLDEN_BASELINE_DATASET_VERSION_INVALID",
                    "invalid golden dataset version");
        }
        ClassPathResource resource = new ClassPathResource(
                "eval/golden/" + datasetVersion + "/baseline.json",
                getClass().getClassLoader());
        if (!resource.exists()) {
            throw new GoldenDatasetException(
                    "GOLDEN_BASELINE_NOT_FOUND",
                    "golden baseline not found for " + datasetVersion);
        }
        try (InputStream input = resource.getInputStream()) {
            GoldenEvaluationBaseline baseline = objectMapper.readValue(
                    input, GoldenEvaluationBaseline.class);
            if (!datasetVersion.equals(baseline.datasetVersion())) {
                throw new GoldenDatasetException(
                        "GOLDEN_BASELINE_VERSION_MISMATCH",
                        "golden baseline version does not match " + datasetVersion);
            }
            return baseline;
        } catch (GoldenDatasetException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new GoldenDatasetException(
                    "GOLDEN_BASELINE_INVALID",
                    "invalid golden baseline for " + datasetVersion,
                    exception);
        }
    }
}
