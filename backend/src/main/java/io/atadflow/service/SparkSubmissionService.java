package io.atadflow.service;

import io.atadflow.entity.Job;
import io.atadflow.entity.JobStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

@ApplicationScoped
public class SparkSubmissionService {

    private static final Logger LOG = Logger.getLogger(SparkSubmissionService.class);

    @Inject
    ManagedExecutor executor;

    @ConfigProperty(name = "python.executable")
    String pythonExecutable;

    public void submit(Job job, String code) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("spark-job-", ".py");
            Files.writeString(tempFile, code);

            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, tempFile.toString());
            pb.inheritIO();

            Process process = pb.start();

            job.processPid = process.pid();
            job.status = JobStatus.RUNNING;
            job.startedAt = LocalDateTime.now();

            UUID jobId = job.id;
            Path tempFileRef = tempFile;
            process.onExit().thenAcceptAsync(
                    p -> handleProcessExit(jobId, p.exitValue(), tempFileRef),
                    executor
            );

            LOG.infof("Started process PID %d for job %s", process.pid(), job.id);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to start subprocess for job %s", job.id);
            job.status = JobStatus.FAILED;
            job.errorMessage = "Failed to start process: " + e.getMessage();
            job.finishedAt = LocalDateTime.now();

            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException cleanupEx) {
                    LOG.warnf(cleanupEx, "Failed to clean up temp file %s", tempFile);
                }
            }
        }
    }

    void handleProcessExit(UUID jobId, int exitCode, Path tempFile) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                Job job = Job.findById(jobId);
                if (job == null) {
                    LOG.warnf("Job %s not found during process exit handling", jobId);
                    return;
                }

                if (job.status == JobStatus.CANCELLED) {
                    LOG.infof("Job %s already cancelled, skipping status update", jobId);
                    return;
                }

                if (exitCode == 0) {
                    job.status = JobStatus.SUCCEEDED;
                    LOG.infof("Job %s succeeded", jobId);
                } else {
                    job.status = JobStatus.FAILED;
                    job.errorMessage = "Process exited with code " + exitCode;
                    LOG.infof("Job %s failed with exit code %d", jobId, exitCode);
                }

                job.finishedAt = LocalDateTime.now();
                job.persist();
            });
        } catch (Exception e) {
            LOG.errorf(e, "Error handling process exit for job %s", jobId);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOG.warnf(e, "Failed to clean up temp file %s", tempFile);
            }
        }
    }

    public void cancel(Job job) {
        job.status = JobStatus.CANCELLED;
        job.finishedAt = LocalDateTime.now();

        if (job.processPid == null) {
            LOG.infof("Job %s has no process PID, marking as cancelled", job.id);
            return;
        }

        try {
            ProcessHandle.of(job.processPid).ifPresentOrElse(
                    handle -> {
                        LOG.infof("Sending SIGTERM to PID %d for job %s", job.processPid, job.id);
                        handle.destroy();

                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        if (handle.isAlive()) {
                            LOG.infof("Process %d still alive, sending SIGKILL for job %s", job.processPid, job.id);
                            handle.destroyForcibly();
                        }
                    },
                    () -> LOG.warnf("Process %d not found for job %s", job.processPid, job.id)
            );
        } catch (Exception e) {
            LOG.warnf(e, "Error cancelling process %d for job %s", job.processPid, job.id);
        }
    }
}
