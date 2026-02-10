import { useState, useEffect, useCallback } from 'react';
import { jobsApi } from '@/api/jobs';
import type { JobDto, JobStatus } from '@/types/job';

const ACTIVE_STATUSES: JobStatus[] = ['PENDING', 'SUBMITTED', 'RUNNING'];

export function useJobs(flowId: string | undefined) {
  const [jobs, setJobs] = useState<JobDto[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    if (!flowId) return;
    setLoading(true);
    const data = await jobsApi.list(flowId);
    setJobs(data);
    setLoading(false);
  }, [flowId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Poll every 5s when any job is active
  useEffect(() => {
    const hasActiveJobs = jobs.some(j => ACTIVE_STATUSES.includes(j.status));
    if (!hasActiveJobs) return;

    const intervalId = window.setInterval(refresh, 5000);
    return () => clearInterval(intervalId);
  }, [jobs, refresh]);

  const submit = useCallback(async () => {
    if (!flowId) return;
    await jobsApi.submit({ flowId });
    await refresh();
  }, [flowId, refresh]);

  const cancel = useCallback(
    async (jobId: string) => {
      await jobsApi.cancel(jobId);
      await refresh();
    },
    [refresh],
  );

  return { jobs, loading, submit, cancel, refresh };
}
