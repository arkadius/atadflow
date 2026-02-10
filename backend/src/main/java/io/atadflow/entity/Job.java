package io.atadflow.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "job")
public class Job extends PanacheEntityBase {

    @Id
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    public Flow flow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public JobStatus status;

    @Column(name = "spark_app_id")
    public String sparkAppId;

    @Column(name = "process_pid")
    public Long processPid;

    @Column(name = "submitted_at")
    public LocalDateTime submittedAt;

    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "finished_at")
    public LocalDateTime finishedAt;

    @Column(name = "error_message")
    public String errorMessage;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (status == null) status = JobStatus.PENDING;
    }
}
