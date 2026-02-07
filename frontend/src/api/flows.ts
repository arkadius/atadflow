import { apiFetch, apiFetchText } from './client';
import type { FlowDto, FlowSummaryDto, CreateFlowRequest, UpdateFlowRequest } from '@/types/flow';

export const flowsApi = {
  list: () => apiFetch<FlowSummaryDto[]>('/flows'),

  get: (id: string) => apiFetch<FlowDto>(`/flows/${id}`),

  create: (req: CreateFlowRequest) =>
    apiFetch<FlowDto>('/flows', {
      method: 'POST',
      body: JSON.stringify(req),
    }),

  update: (id: string, req: UpdateFlowRequest) =>
    apiFetch<FlowDto>(`/flows/${id}`, {
      method: 'PUT',
      body: JSON.stringify(req),
    }),

  delete: (id: string) =>
    apiFetch<void>(`/flows/${id}`, { method: 'DELETE' }),

  generateCode: (id: string) => apiFetchText(`/flows/${id}/code`),
};
