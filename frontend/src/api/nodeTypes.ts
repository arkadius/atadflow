import { apiFetch } from './client';
import type { NodeTypeDescriptor } from '@/types/node';

export const nodeTypesApi = {
  list: () => apiFetch<NodeTypeDescriptor[]>('/node-types'),
};
