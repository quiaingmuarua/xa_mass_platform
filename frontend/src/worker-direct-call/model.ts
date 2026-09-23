import { workerDirectCallConfigurationError } from "./errors";
import type { JsonValue, RuntimeDataSourceMode } from "@/runtime-viewer/types";
import type { WorkerDirectCallRequest } from "./types";

export const DEFAULT_DIRECT_CALL_TIMEOUT_MILLIS = 3_000;
export const MAX_DIRECT_CALL_TIMEOUT_MILLIS = 10_000;

export function isWorkerDirectCallEnabled(mode: RuntimeDataSourceMode): boolean {
  return mode === "api";
}

export function validateWorkerDirectCallRequest(
  request: WorkerDirectCallRequest
): WorkerDirectCallRequest {
  const workerGroupId = requireNonBlank(request.workerGroupId, "WorkerGroup");
  const workerId = requireNonBlank(request.workerId, "Worker ID");
  const endpointManagerId = requireNonBlank(
    request.endpointManagerId,
    "Endpoint Manager"
  );
  const eventName = requireNonBlank(request.eventName, "Event Name");
  if (
    !Number.isInteger(request.waitTimeoutMillis) ||
    request.waitTimeoutMillis < 1 ||
    request.waitTimeoutMillis > MAX_DIRECT_CALL_TIMEOUT_MILLIS
  ) {
    throw workerDirectCallConfigurationError(
      `Wait Timeout 必须是 1..${MAX_DIRECT_CALL_TIMEOUT_MILLIS} 的整数。`
    );
  }
  try {
    JSON.parse(request.payloadText);
  } catch {
    throw workerDirectCallConfigurationError("Payload 必须是合法 JSON。");
  }
  return {
    workerGroupId,
    workerId,
    endpointManagerId,
    eventName,
    payloadText: request.payloadText,
    waitTimeoutMillis: request.waitTimeoutMillis
  };
}

export function parseOpaqueJson(value?: string): JsonValue | undefined {
  if (value === undefined) return undefined;
  try {
    return JSON.parse(value) as JsonValue;
  } catch {
    return undefined;
  }
}

function requireNonBlank(value: string, label: string): string {
  const normalized = value.trim();
  if (normalized.length === 0) {
    throw workerDirectCallConfigurationError(`${label} 不能为空。`);
  }
  return normalized;
}
