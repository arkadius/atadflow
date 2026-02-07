package io.atadflow.dto;

import java.util.Map;
import java.util.UUID;

public record FlowNodeDto(
        UUID id,
        String nodeKey,
        String label,
        String nodeType,
        double positionX,
        double positionY,
        Map<String, Object> config,
        String pythonCode
) {
}
