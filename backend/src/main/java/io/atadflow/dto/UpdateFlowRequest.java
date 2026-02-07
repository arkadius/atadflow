package io.atadflow.dto;

import java.util.List;

public record UpdateFlowRequest(
        String name,
        String description,
        List<FlowNodeDto> nodes,
        List<FlowEdgeDto> edges
) {
}
