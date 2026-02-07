import type { JobStatus } from '@/types/job';

export function JobStatusBadge({ status }: { status: JobStatus }) {
  return <span className={`status-badge ${status}`}>{status}</span>;
}
