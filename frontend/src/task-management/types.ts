import type { WorkerQuery } from "@/task-call-debug/types";
import type { JsonValue, RefillTarget } from "@/runtime-viewer/types";

export interface FiniteTaskConfig {
  priority: number;
  refill: RefillTarget[];
  maxRetryTimes: number;
}
export interface FiniteTaskSeedItem {
  lineNumber: number;
  payload: Record<string, JsonValue>;
}
export interface TaskCreateApiRequest extends FiniteTaskConfig {
  projectId: string;
  workerGroupId: string;
}
export interface TaskCreateApiResponse {
  taskId: string;
}
export interface TaskItemApiRequest {
  messageId: string;
  eventCode: string;
  workerSelector: WorkerQuery;
  payload: Record<string, JsonValue>;
  priority?: number;
  ttlMillis?: number;
}
export type ActionOutcome =
  | { status: "applied" }
  | { status: "unchanged" }
  | { status: "rejected"; code: number; message: string };
export type TaskItemsAppendApiResponse = Record<
  string,
  Exclude<ActionOutcome, { status: "unchanged" }>
>;
export type TaskExportResult =
  | { ready: true; fileName: string; blob: Blob }
  | { ready: false };
export interface FiniteTaskClient {
  createTask(request: TaskCreateApiRequest): Promise<TaskCreateApiResponse>;
  appendItems(
    taskId: string,
    items: TaskItemApiRequest[]
  ): Promise<TaskItemsAppendApiResponse>;
  approveTask(taskId: string): Promise<void>;
  closeTask(taskId: string): Promise<void>;
  exportResults(taskId: string): Promise<TaskExportResult>;
}
export interface ImportReceipt {
  taskId?: string;
  fileName: string;
  total: number;
  applied: number;
  rejected: number;
  unconfirmed: number;
  notSubmitted: number;
  error?: string;
  complete: boolean;
}
