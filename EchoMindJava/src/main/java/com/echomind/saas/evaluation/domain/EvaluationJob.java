package com.echomind.saas.evaluation.domain;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;

public record EvaluationJob(
        String jobId,
        @JsonIgnore TenantId tenantId,
        @JsonIgnore UserId ownerUserId,
        String status,
        String phase,
        int progress,
        String createdAt,
        String startedAt,
        String finishedAt,
        String error,
        Map<String, Object> report
) {
}
