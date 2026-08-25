import type { JsonValue } from "@/runtime-viewer/types";

export interface TaskCallDebugDraft {
  taskId: string;
  workerGroupId: string;
  eventName: string;
  payloadText: string;
  allocationRuleText: string;
  waitTimeoutMillis: number;
}

export interface ValidatedTaskCallDebugDraft extends TaskCallDebugDraft {
  payload: Record<string, JsonValue>;
  allocationRule: Record<string, JsonValue>;
}

export interface TaskCallDebugClientRequest {
  taskId: string;
  messageId: string;
  eventName: string;
  payload: Record<string, JsonValue>;
  allocationRule: Record<string, JsonValue>;
  waitTimeoutMillis: number;
}

export type TaskCallDebugOutcome =
  | {
      status: "succeeded";
      opaqueResultPayload: string;
    }
  | {
      status: "not_observed";
    };

export interface TaskCallDebugClient {
  callTask(request: TaskCallDebugClientRequest): Promise<TaskCallDebugOutcome>;
  loadResult(taskId: string, messageId: string): Promise<string | undefined>;
}
