import { apiFetch } from './client';
import type { JobDto, SubmitJobRequest } from '@/types/job';

export const jobsApi = {
  list: (flowId?: string) =>
    apiFetch<JobDto[]>(flowId ? `/jobs?flowId=${flowId}` : '/jobs'),

  get: (id: string) => apiFetch<JobDto>(`/jobs/${id}`),

  submit: (req: SubmitJobRequest) =>
    apiFetch<JobDto>('/jobs', {
      method: 'POST',
      body: JSON.stringify(req),
    }),

  cancel: (id: string) =>
    apiFetch<JobDto>(`/jobs/${id}/cancel`, { method: 'POST' }),
};
