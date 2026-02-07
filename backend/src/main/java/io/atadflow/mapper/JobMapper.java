package io.atadflow.mapper;

import io.atadflow.dto.JobDto;
import io.atadflow.entity.Job;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class JobMapper {

    public JobDto toDto(Job job) {
        return new JobDto(
                job.id,
                job.flow.id,
                job.status,
                job.sparkAppId,
                job.submittedAt,
                job.startedAt,
                job.finishedAt,
                job.errorMessage
        );
    }
}
