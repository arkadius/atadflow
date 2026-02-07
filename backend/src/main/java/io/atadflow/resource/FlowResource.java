package io.atadflow.resource;

import io.atadflow.dto.*;
import io.atadflow.service.CodeGenerationService;
import io.atadflow.service.FlowService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/flows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FlowResource {

    @Inject
    FlowService flowService;

    @Inject
    CodeGenerationService codeGenerationService;

    @GET
    public List<FlowSummaryDto> list() {
        return flowService.listFlows();
    }

    @GET
    @Path("/{id}")
    public FlowDto get(@PathParam("id") UUID id) {
        return flowService.getFlow(id);
    }

    @POST
    public Response create(CreateFlowRequest request) {
        FlowDto flow = flowService.createFlow(request);
        return Response.status(Response.Status.CREATED).entity(flow).build();
    }

    @PUT
    @Path("/{id}")
    public FlowDto update(@PathParam("id") UUID id, UpdateFlowRequest request) {
        return flowService.updateFlow(id, request);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") UUID id) {
        flowService.deleteFlow(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/code")
    @Produces(MediaType.TEXT_PLAIN)
    public String generateCode(@PathParam("id") UUID id) {
        FlowDto flow = flowService.getFlow(id);
        return codeGenerationService.generateCode(flow);
    }
}
