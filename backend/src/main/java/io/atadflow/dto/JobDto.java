package io.atadflow.dto;

import io.atadflow.entity.JobStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record JobDto(
        UUID id,
        UUID flowId,
        JobStatus status,
        String sparkAppId,
        LocalDateTime submittedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMessage
) {
}
