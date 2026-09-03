import type { JsonValue } from "@/runtime-viewer/types";

export type TaskItemWorkerSelector =
  | []
  | ["workerId", "$eq", string]
  | ["workerId", "$in", string[]];

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
  workerSelector: TaskItemWorkerSelector;
}

export interface TaskCallDebugClientRequest {
  taskId: string;
  messageId: string;
  eventName: string;
  payload: Record<string, JsonValue>;
  workerSelector: TaskItemWorkerSelector;
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
