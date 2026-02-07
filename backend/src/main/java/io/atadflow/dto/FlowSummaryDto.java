package io.atadflow.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record FlowSummaryDto(
        UUID id,
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
