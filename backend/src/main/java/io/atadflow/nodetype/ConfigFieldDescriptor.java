package io.atadflow.nodetype;

import java.util.List;

public record ConfigFieldDescriptor(
        String label,
        String type,
        Object defaultValue,
        List<String> options
) {
}
