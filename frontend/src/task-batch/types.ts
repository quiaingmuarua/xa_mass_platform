export type TaskBatchRunStatus = "succeeded" | "partial";

export interface TaskBatchInputUploadResponse {
  fileName: string;
  byteCount: number;
  lineCount: number;
}

export interface TaskBatchRunRequest {
  workerGroupId: string;
  eventCode: string;
  payloadKey: string;
  inputFile: string;
  maximumWaitMillis: number;
}

export interface TaskBatchRunResponse {
  runId: string;
  workerGroupId: string;
  eventCode: string;
  payloadKey: string;
  inputFile: string;
  status: TaskBatchRunStatus;
  inputCount: number;
  resultCount: number;
  remainingCount: number;
  loadRounds: number;
  durationMillis: number;
  outputFile: string;
}

export interface TaskBatchClient {
  uploadInput(
    fileName: string,
    content: ArrayBuffer
  ): Promise<TaskBatchInputUploadResponse>;
  run(request: TaskBatchRunRequest): Promise<TaskBatchRunResponse>;
  downloadOutput(fileName: string): Promise<Blob>;
}

export interface TaskBatchExecutionRequest {
  workerGroupId: string;
  eventCode: string;
  payloadKey: string;
  file: File;
  maximumWaitMillis: number;
}

export interface TaskBatchRunRecord extends TaskBatchRunResponse {
  originalFileName: string;
}
