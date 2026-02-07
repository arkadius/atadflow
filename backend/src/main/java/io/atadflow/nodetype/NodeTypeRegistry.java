package io.atadflow.nodetype;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class NodeTypeRegistry {

    private final Map<String, NodeTypeDescriptor> types = new LinkedHashMap<>();

    @Inject
    void init(Instance<NodeTypeProvider> providers) {
        for (NodeTypeProvider provider : providers) {
            for (NodeTypeDescriptor descriptor : provider.getNodeTypes()) {
                types.put(descriptor.id(), descriptor);
            }
        }
    }

    public Collection<NodeTypeDescriptor> getAll() {
        return types.values();
    }

    public Optional<NodeTypeDescriptor> get(String id) {
        return Optional.ofNullable(types.get(id));
    }
}
