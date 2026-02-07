package io.atadflow.resource;

import io.atadflow.nodetype.NodeTypeDescriptor;
import io.atadflow.nodetype.NodeTypeRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Collection;

@Path("/api/node-types")
@Produces(MediaType.APPLICATION_JSON)
public class NodeTypeResource {

    @Inject
    NodeTypeRegistry registry;

    @GET
    public Collection<NodeTypeDescriptor> list() {
        return registry.getAll();
    }
}
