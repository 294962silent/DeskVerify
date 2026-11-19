package com.echomind.saas.evaluation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoldenRagEvaluationRequest(
        @NotBlank @Size(max = 96) String datasetVersion
) {
}
