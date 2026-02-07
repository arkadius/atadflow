package io.atadflow.exception;

import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

public class ExceptionMappers {

    @ServerExceptionMapper
    public Response mapFlowNotFound(FlowNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response mapJobNotFound(JobNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", e.getMessage()))
                .build();
    }
}
