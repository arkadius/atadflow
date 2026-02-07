package io.atadflow.service;

import io.atadflow.entity.Job;
import io.atadflow.entity.JobStatus;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SparkSubmissionService {

    private static final Logger LOG = Logger.getLogger(SparkSubmissionService.class);

    public void submit(Job job, String code) {
        LOG.infof("Stub: would submit job %s with code length %d", job.id, code.length());
        job.status = JobStatus.SUBMITTED;
        job.sparkAppId = "local-stub-" + job.id;
    }

    public void cancel(Job job) {
        LOG.infof("Stub: would cancel job %s (sparkAppId=%s)", job.id, job.sparkAppId);
        job.status = JobStatus.CANCELLED;
    }
}
