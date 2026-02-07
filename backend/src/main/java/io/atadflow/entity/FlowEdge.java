package io.atadflow.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "flow_edge")
public class FlowEdge extends PanacheEntityBase {

    @Id
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    public Flow flow;

    @Column(name = "source_node", nullable = false)
    public String sourceNode;

    @Column(name = "target_node", nullable = false)
    public String targetNode;

    @Column(name = "source_handle")
    public String sourceHandle;

    @Column(name = "target_handle")
    public String targetHandle;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
    }
}
