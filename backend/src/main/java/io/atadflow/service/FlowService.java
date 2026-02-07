package io.atadflow.service;

import io.atadflow.dto.*;
import io.atadflow.entity.Flow;
import io.atadflow.exception.FlowNotFoundException;
import io.atadflow.mapper.FlowMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class FlowService {

    @Inject
    FlowMapper mapper;

    @Inject
    EntityManager em;

    public List<FlowSummaryDto> listFlows() {
        List<Flow> flows = Flow.listAll();
        return flows.stream().map(mapper::toSummaryDto).toList();
    }

    public FlowDto getFlow(UUID id) {
        Flow flow = Flow.findById(id);
        if (flow == null) throw new FlowNotFoundException(id);
        return mapper.toDto(flow);
    }

    @Transactional
    public FlowDto createFlow(CreateFlowRequest request) {
        Flow flow = new Flow();
        flow.name = request.name();
        flow.description = request.description();
        flow.persist();
        return mapper.toDto(flow);
    }

    @Transactional
    public FlowDto updateFlow(UUID id, UpdateFlowRequest request) {
        Flow flow = Flow.findById(id);
        if (flow == null) throw new FlowNotFoundException(id);

        flow.name = request.name();
        flow.description = request.description();

        // Full replace of nodes and edges
        flow.nodes.clear();
        flow.edges.clear();
        em.flush();

        if (request.nodes() != null) {
            request.nodes().forEach(dto -> {
                var node = mapper.toNodeEntity(dto, flow);
                flow.nodes.add(node);
            });
        }
        if (request.edges() != null) {
            request.edges().forEach(dto -> {
                var edge = mapper.toEdgeEntity(dto, flow);
                flow.edges.add(edge);
            });
        }

        flow.persist();
        return mapper.toDto(flow);
    }

    @Transactional
    public void deleteFlow(UUID id) {
        Flow flow = Flow.findById(id);
        if (flow == null) throw new FlowNotFoundException(id);
        flow.delete();
    }
}
