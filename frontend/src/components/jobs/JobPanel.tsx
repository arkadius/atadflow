import type { JobDto } from '@/types/job';
import { JobStatusBadge } from './JobStatusBadge';

interface JobPanelProps {
  jobs: JobDto[];
  onSubmit: () => void;
  onCancel: (jobId: string) => void;
}

export function JobPanel({ jobs, onSubmit, onCancel }: JobPanelProps) {
  return (
    <div className="job-panel">
      <div className="job-panel-header">
        <h3>Jobs</h3>
        <button className="primary" onClick={onSubmit}>
          Run
        </button>
      </div>
      <div className="job-list">
        {jobs.length === 0 && (
          <div style={{ padding: '8px 0', color: 'var(--color-text-muted)', fontSize: 12 }}>
            No jobs yet
          </div>
        )}
        {jobs.map((job) => (
          <div key={job.id} className="job-item">
            <span style={{ fontFamily: 'monospace' }}>{job.id.slice(0, 8)}</span>
            <JobStatusBadge status={job.status} />
            {(job.status === 'SUBMITTED' || job.status === 'RUNNING') && (
              <button className="danger" onClick={() => onCancel(job.id)} style={{ padding: '2px 8px', fontSize: 11 }}>
                Cancel
              </button>
            )}
            {job.submittedAt && (
              <span style={{ color: 'var(--color-text-muted)', fontSize: 11 }}>
                {new Date(job.submittedAt).toLocaleTimeString()}
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
