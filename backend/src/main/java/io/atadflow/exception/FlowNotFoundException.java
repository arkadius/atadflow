package io.atadflow.exception;

import java.util.UUID;

public class FlowNotFoundException extends RuntimeException {
    public FlowNotFoundException(UUID id) {
        super("Flow not found: " + id);
    }
}
