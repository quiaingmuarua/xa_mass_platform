import type {
  JsonValue,
  RuntimeDataSourceMode,
  TaskRuntimePreviewEntry
} from "@/runtime-viewer/types";

import { taskCallDebugConfigurationError } from "./errors";
import type {
  TaskCallDebugDraft,
  TaskItemWorkerSelector,
  ValidatedTaskCallDebugDraft
} from "./types";

export const DEFAULT_TASK_CALL_TIMEOUT_MILLIS = 3_000;
export const MAX_TASK_CALL_TIMEOUT_MILLIS = 60_000;

export interface TaskCallDebugAvailability {
  enabled: boolean;
  reason?: string;
}

export function taskCallDebugAvailability(
  mode: RuntimeDataSourceMode,
  entry: TaskRuntimePreviewEntry
): TaskCallDebugAvailability {
  if (mode !== "api") {
    return {
      enabled: false,
      reason: "Mock 模式不执行真实 Task Call。"
    };
  }
  if (entry.workerGroup === null) {
    return {
      enabled: false,
      reason: "WorkerGroup 描述符缺失，不能构造调试调用。"
    };
  }
  if (entry.task === null) {
    return {
      enabled: false,
      reason: "Task 描述符缺失，不能调用当前配置坐标。"
    };
  }
  if (entry.task.workerAllocationMechanism !== "ON_DEMAND_ITEM_RULE") {
    return {
      enabled: false,
      reason: "该 Task 不接受 Item Worker Selector。"
    };
  }
  if (entry.task.idleDisposition !== "PARK_WHEN_IDLE") {
    return {
      enabled: false,
      reason: "该 Task 不是可复用的 PARK_WHEN_IDLE Task Call。"
    };
  }
  return { enabled: true };
}

export function validateTaskCallDebugDraft(
  draft: TaskCallDebugDraft
): ValidatedTaskCallDebugDraft {
  const taskId = requireNonBlank(draft.taskId, "Task ID");
  const workerGroupId = requireNonBlank(draft.workerGroupId, "WorkerGroup");
  const eventName = requireNonBlank(draft.eventName, "Event Name");
  if (
    !Number.isInteger(draft.waitTimeoutMillis) ||
    draft.waitTimeoutMillis < 1 ||
    draft.waitTimeoutMillis > MAX_TASK_CALL_TIMEOUT_MILLIS
  ) {
    throw taskCallDebugConfigurationError(
      `Wait Timeout 必须是 1..${MAX_TASK_CALL_TIMEOUT_MILLIS} 的整数。`
    );
  }
  return {
    taskId,
    workerGroupId,
    eventName,
    payloadText: draft.payloadText,
    workerSelectorText: draft.workerSelectorText,
    waitTimeoutMillis: draft.waitTimeoutMillis,
    payload: parseJsonObject(draft.payloadText, "Payload"),
    workerSelector: parseWorkerSelector(draft.workerSelectorText)
  };
}

export function parseTaskCallResultJson(value?: string): JsonValue | undefined {
  if (value === undefined) return undefined;
  try {
    return JSON.parse(value) as JsonValue;
  } catch {
    return undefined;
  }
}

function parseJsonObject(text: string, label: string): Record<string, JsonValue> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw taskCallDebugConfigurationError(`${label} 必须是合法 JSON Object。`);
  }
  if (parsed === null || Array.isArray(parsed) || typeof parsed !== "object") {
    throw taskCallDebugConfigurationError(`${label} 必须是 JSON Object。`);
  }
  return parsed as Record<string, JsonValue>;
}

function parseWorkerSelector(text: string): TaskItemWorkerSelector {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw taskCallDebugConfigurationError(
      "Worker Selector 必须是合法 JSON Array。"
    );
  }
  if (!Array.isArray(parsed)) {
    throw invalidWorkerSelector();
  }
  if (parsed.length === 0) return [];
  if (parsed.length !== 3 || parsed[0] !== "workerId") {
    throw invalidWorkerSelector();
  }
  if (parsed[1] === "$eq" && isNonBlankString(parsed[2])) {
    return ["workerId", "$eq", parsed[2]];
  }
  if (parsed[1] === "$in" && Array.isArray(parsed[2])) {
    const workerIds = parsed[2];
    if (
      workerIds.length >= 1 &&
      workerIds.length <= 100 &&
      workerIds.every(isNonBlankString) &&
      new Set(workerIds).size === workerIds.length
    ) {
      return ["workerId", "$in", [...workerIds]];
    }
  }
  throw invalidWorkerSelector();
}

function invalidWorkerSelector(): Error {
  return taskCallDebugConfigurationError(
    'Worker Selector 必须是 []、["workerId","$eq","id"] 或 ' +
      '["workerId","$in",["id"]]。'
  );
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function requireNonBlank(value: string, label: string): string {
  const normalized = value.trim();
  if (normalized.length === 0) {
    throw taskCallDebugConfigurationError(`${label} 不能为空。`);
  }
  return normalized;
}
