import { useState, useEffect, useCallback } from 'react';
import { jobsApi } from '@/api/jobs';
import type { JobDto } from '@/types/job';

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
