import type { TaskCaller } from "@/task-caller";
import type { JsonValue } from "@/runtime-viewer/types";

export interface WorkerQuery {
  executorName: string;
  input: Exclude<JsonValue, null>;
}
export interface RpcCallItem {
  messageId: string;
  eventCode: string;
  payload: Record<string, JsonValue>;
  workerSelector: WorkerQuery;
  priority?: number | null;
  ttlMillis?: number | null;
}
export interface RpcCallRequest {
  items: RpcCallItem[];
  waitTimeoutMillis?: number | null;
}
export interface TaskCallDebugClientRequest {
  caller: TaskCaller;
  taskId: string;
  request: RpcCallRequest;
}
export type TaskCallDebugOutcome =
  | { status: "succeeded"; opaqueResultPayload: string }
  | { status: "failed" }
  | { status: "not_observed" };
export type TaskCallDebugResults = Record<string, TaskCallDebugOutcome>;
export interface TaskCallDebugClient {
  callTask(request: TaskCallDebugClientRequest): Promise<TaskCallDebugResults>;
  loadResults(
    caller: TaskCaller,
    taskId: string,
    messageIds: string[]
  ): Promise<TaskCallDebugResults>;
}
