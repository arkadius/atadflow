package io.atadflow.config;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class SpaRoutingConfig {

    public void init(@Observes Filters filters) {
        filters.register(routingContext -> {
            String path = routingContext.normalizedPath();

            // Skip API requests, Quarkus endpoints, and static assets
            if (path.startsWith("/api/") ||
                path.startsWith("/q/") ||
                path.startsWith("/assets/") ||
                path.contains(".") ||
                path.equals("/")) {
                routingContext.next();
                return;
            }

            // For client-side routes, serve index.html
            routingContext.reroute("/");
        }, 10); // Priority 10 runs before default routing
    }
}
