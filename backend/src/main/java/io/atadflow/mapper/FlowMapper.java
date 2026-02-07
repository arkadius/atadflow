package io.atadflow.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.atadflow.dto.*;
import io.atadflow.entity.Flow;
import io.atadflow.entity.FlowEdge;
import io.atadflow.entity.FlowNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class FlowMapper {

    @Inject
    ObjectMapper objectMapper;

    public FlowDto toDto(Flow flow) {
        return new FlowDto(
                flow.id,
                flow.name,
                flow.description,
                flow.createdAt,
                flow.updatedAt,
                flow.nodes.stream().map(this::toNodeDto).toList(),
                flow.edges.stream().map(this::toEdgeDto).toList()
        );
    }

    public FlowSummaryDto toSummaryDto(Flow flow) {
        return new FlowSummaryDto(
                flow.id,
                flow.name,
                flow.description,
                flow.createdAt,
                flow.updatedAt
        );
    }

    public FlowNodeDto toNodeDto(FlowNode node) {
        return new FlowNodeDto(
                node.id,
                node.nodeKey,
                node.label,
                node.nodeType,
                node.positionX,
                node.positionY,
                parseConfig(node.config),
                node.pythonCode
        );
    }

    public FlowEdgeDto toEdgeDto(FlowEdge edge) {
        return new FlowEdgeDto(
                edge.id,
                edge.sourceNode,
                edge.targetNode,
                edge.sourceHandle,
                edge.targetHandle
        );
    }

    public FlowNode toNodeEntity(FlowNodeDto dto, Flow flow) {
        FlowNode node = new FlowNode();
        node.flow = flow;
        node.nodeKey = dto.nodeKey();
        node.label = dto.label();
        node.nodeType = dto.nodeType();
        node.positionX = dto.positionX();
        node.positionY = dto.positionY();
        node.config = serializeConfig(dto.config());
        node.pythonCode = dto.pythonCode();
        return node;
    }

    public FlowEdge toEdgeEntity(FlowEdgeDto dto, Flow flow) {
        FlowEdge edge = new FlowEdge();
        edge.flow = flow;
        edge.sourceNode = dto.sourceNode();
        edge.targetNode = dto.targetNode();
        edge.sourceHandle = dto.sourceHandle();
        edge.targetHandle = dto.targetHandle();
        return edge;
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String serializeConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
