package io.atadflow.dto;

import java.util.UUID;

public record FlowEdgeDto(
        UUID id,
        String sourceNode,
        String targetNode,
        String sourceHandle,
        String targetHandle
) {
}
