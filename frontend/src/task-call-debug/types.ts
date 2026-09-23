import type { JsonValue } from "@/runtime-viewer/types";

export interface WorkerQuery {
  executorName: string;
  input: Exclude<JsonValue, null>;
}

export interface TaskCallDebugDraft {
  taskId: string;
  workerGroupId: string;
  eventName: string;
  payloadText: string;
  workerSelectorText: string;
  waitTimeoutMillis: number;
}

export interface ValidatedTaskCallDebugDraft extends TaskCallDebugDraft {
  payload: Record<string, JsonValue>;
  workerSelector: WorkerQuery;
}

export interface TaskCallDebugClientRequest {
  taskId: string;
  messageId: string;
  eventName: string;
  payload: Record<string, JsonValue>;
  workerSelector: WorkerQuery;
  waitTimeoutMillis: number;
}

export type TaskCallDebugOutcome =
  | {
      status: "succeeded";
      opaqueResultPayload: string;
    }
  | {
      status: "failed";
    }
  | {
      status: "not_observed";
    };

export interface TaskCallDebugClient {
  callTask(request: TaskCallDebugClientRequest): Promise<TaskCallDebugOutcome>;
  loadResult(taskId: string, messageId: string): Promise<TaskCallDebugOutcome>;
}
