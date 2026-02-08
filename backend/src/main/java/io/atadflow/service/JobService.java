package io.atadflow.service;

import io.atadflow.dto.FlowDto;
import io.atadflow.dto.JobDto;
import io.atadflow.dto.SubmitJobRequest;
import io.atadflow.entity.Flow;
import io.atadflow.entity.Job;
import io.atadflow.entity.JobStatus;
import io.atadflow.exception.FlowNotFoundException;
import io.atadflow.exception.JobNotFoundException;
import io.atadflow.mapper.FlowMapper;
import io.atadflow.mapper.JobMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class JobService {

    @Inject
    JobMapper mapper;

    @Inject
    FlowMapper flowMapper;

    @Inject
    CodeGenerationService codeGenerationService;

    @Inject
    SparkSubmissionService sparkSubmissionService;

    public List<JobDto> listJobs(UUID flowId) {
        List<Job> jobs;
        if (flowId != null) {
            jobs = Job.list("flow.id", flowId);
        } else {
            jobs = Job.listAll();
        }
        return jobs.stream().map(mapper::toDto).toList();
    }

    public JobDto getJob(UUID id) {
        Job job = Job.findById(id);
        if (job == null) throw new JobNotFoundException(id);
        return mapper.toDto(job);
    }

    @Transactional
    public JobDto submitJob(SubmitJobRequest request) {
        Flow flow = Flow.findById(requesok,t.flowId());
        if (flow == null) throw new FlowNotFoundException(request.flowId());

        FlowDto flowDto = flowMapper.toDto(flow);
        String code = codeGenerationService.generateCode(flowDto);

        Job job = new Job();
        job.flow = flow;
        job.status = JobStatus.PENDING;
        job.submittedAt = LocalDateTime.now();
        job.persist();

        sparkSubmissionService.submit(job, code);
        job.persist();

        return mapper.toDto(job);
    }

    @Transactional
    public JobDto cancelJob(UUID id) {
        Job job = Job.findById(id);
        if (job == null) throw new JobNotFoundException(id);

        sparkSubmissionService.cancel(job);
        job.finishedAt = LocalDateTime.now();
        job.persist();

        return mapper.toDto(job);
    }
}
