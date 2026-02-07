export type JobStatus = 'PENDING' | 'SUBMITTED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface JobDto {
  id: string;
  flowId: string;
  status: JobStatus;
  sparkAppId: string | null;
  submittedAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  errorMessage: string | null;
}

export interface SubmitJobRequest {
  flowId: string;
}
