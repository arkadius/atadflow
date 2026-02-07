package io.atadflow.resource;

import io.atadflow.dto.JobDto;
import io.atadflow.dto.SubmitJobRequest;
import io.atadflow.service.JobService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JobResource {

    @Inject
    JobService jobService;

    @GET
    public List<JobDto> list(@QueryParam("flowId") UUID flowId) {
        return jobService.listJobs(flowId);
    }

    @GET
    @Path("/{id}")
    public JobDto get(@PathParam("id") UUID id) {
        return jobService.getJob(id);
    }

    @POST
    public Response submit(SubmitJobRequest request) {
        JobDto job = jobService.submitJob(request);
        return Response.status(Response.Status.CREATED).entity(job).build();
    }

    @POST
    @Path("/{id}/cancel")
    public JobDto cancel(@PathParam("id") UUID id) {
        return jobService.cancelJob(id);
    }
}
