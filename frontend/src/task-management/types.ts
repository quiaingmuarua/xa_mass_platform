import type { JsonValue, RuntimeDataSourceMode } from "@/runtime-viewer/types";

export type FiniteTaskStage =
  | "CREATED"
  | "ITEMS_APPENDED"
  | "APPROVED"
  | "EXPORT_READY";

export interface FiniteTaskConfig {
  priority: number;
  maximumCandidateWorkers: number;
  maxRetryTimes: number;
}

export interface FiniteTaskSeedItem {
  lineNumber: number;
  payload: Record<string, string>;
}

export interface FiniteTaskSession {
  taskId: string;
  workerGroupId: string;
  eventCode: string;
  payloadKey: string;
  originalFileName: string;
  byteCount: number;
  lineCount: number;
  appendedCount: number;
  stage: FiniteTaskStage;
  config: FiniteTaskConfig;
  createdAt: string;
  updatedAt: string;
}

export interface CreateFiniteTaskExecutionRequest {
  workerGroupId: string;
  eventCode: string;
  payloadKey: string;
  file: File;
  config: FiniteTaskConfig;
}

export interface TaskCreateApiRequest extends FiniteTaskConfig {
  workerGroupId: string;
  allocationRule: Record<string, JsonValue>;
}

export interface TaskCreateApiResponse {
  taskId: string;
}

export interface TaskItemApiRequest {
  messageId: string;
  eventCode: string;
  payload: Record<string, string>;
}

export interface TaskItemsAppendApiResponse {
  results: Record<string, TaskItemAppendOutcome>;
}

export type TaskItemAppendOutcome =
  | { status: "succeeded" }
  | { status: "failed"; code: number; message: string };

export interface TaskExportDownload {
  ready: true;
  fileName: string;
  blob: Blob;
}

export interface TaskExportNotReady {
  ready: false;
}

export type TaskExportResult = TaskExportDownload | TaskExportNotReady;

export interface FiniteTaskClient {
  createTask(request: TaskCreateApiRequest): Promise<TaskCreateApiResponse>;
  appendItems(
    taskId: string,
    items: TaskItemApiRequest[]
  ): Promise<TaskItemsAppendApiResponse>;
  approveTask(taskId: string): Promise<void>;
  exportResults(taskId: string): Promise<TaskExportResult>;
}

export interface TaskManagementCatalog {
  readonly mode: RuntimeDataSourceMode;
  readonly workerGroups: Array<{
    workerGroupId: string;
    eventCodes: string[];
  }>;
}

export interface FiniteTaskDownload {
  fileName: string;
  blob: Blob;
}
