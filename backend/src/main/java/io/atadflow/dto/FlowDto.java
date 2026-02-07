package io.atadflow.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record FlowDto(
        UUID id,
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<FlowNodeDto> nodes,
        List<FlowEdgeDto> edges
) {
}
