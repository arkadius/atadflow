package io.atadflow.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity
@Table(name = "flow_node")
public class FlowNode extends PanacheEntityBase {

    @Id
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    public Flow flow;

    @Column(name = "node_key", nullable = false)
    public String nodeKey;

    @Column(nullable = false)
    public String label;

    @Column(name = "node_type", nullable = false)
    public String nodeType;

    @Column(name = "position_x")
    public double positionX;

    @Column(name = "position_y")
    public double positionY;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String config;

    @Column(name = "python_code")
    public String pythonCode;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
    }
}
