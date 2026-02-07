package io.atadflow.nodetype;

import java.util.List;
import java.util.Map;

public record NodeTypeDescriptor(
        String id,
        String label,
        List<PortDescriptor> inputs,
        List<PortDescriptor> outputs,
        Map<String, ConfigFieldDescriptor> configSchema,
        String defaultCodeTemplate
) {
}
