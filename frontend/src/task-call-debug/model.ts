import { z } from "zod";
import { callerHeaders, type TaskCaller } from "@/task-caller";
import { jsonValueSchema } from "@/runtime-viewer/schemas";
import type { JsonValue } from "@/runtime-viewer/types";
import { taskCallDebugConfigurationError } from "./errors";
import type { WorkerQuery, RpcCallRequest } from "./types";

export const DEFAULT_TASK_CALL_TIMEOUT_MILLIS = 3_000;
export const MAX_TASK_CALL_TIMEOUT_MILLIS = 60_000;
export const EMPTY_RPC_REQUEST = JSON.stringify(
  { items: [], waitTimeoutMillis: DEFAULT_TASK_CALL_TIMEOUT_MILLIS },
  null,
  2
);
const nonblank = z.string().refine((value) => value.trim().length > 0, "不能为空");
const requestSchema = z
  .object({
    items: z
      .array(
        z
          .object({
            messageId: nonblank,
            eventCode: nonblank,
            payload: z.record(z.string(), jsonValueSchema),
            workerSelector: z.unknown(),
            priority: z.number().int().min(0).max(10).nullable().optional(),
            ttlMillis: z.number().int().positive().nullable().optional()
          })
          .strict()
      )
      .min(1)
      .max(100),
    waitTimeoutMillis: z
      .number()
      .int()
      .min(1)
      .max(MAX_TASK_CALL_TIMEOUT_MILLIS)
      .nullable()
      .optional()
  })
  .strict();
export function parseRpcRequest(text: string): RpcCallRequest {
  try {
    const value = requestSchema.parse(JSON.parse(text));
    return {
      ...value,
      items: value.items.map((item, index) => {
        try {
          return {
            ...item,
            workerSelector: parseWorkerSelector(JSON.stringify(item.workerSelector))
          };
        } catch (cause) {
          throw new Error(
            `items.${index}.workerSelector: ${cause instanceof Error ? cause.message : "无效"}`
          );
        }
      })
    };
  } catch (cause) {
    const message =
      cause instanceof z.ZodError && cause.issues[0]?.path.join(".") === "items"
        ? "items：每次调用需要 1～100 个 Item，可展开示例或粘贴完整请求。"
        : cause instanceof z.ZodError
          ? `${cause.issues[0]?.path.join(".") || "请求"}: ${cause.issues[0]?.message}`
          : cause instanceof Error
            ? cause.message
            : "请求 JSON 无效";
    throw taskCallDebugConfigurationError(message);
  }
}
export function rpcMessageIds(request: RpcCallRequest): string[] {
  return [...new Set(request.items.map((item) => item.messageId))];
}
export function parseResultIds(text: string): string[] {
  const ids = text
    .split(/\r?\n/)
    .map((id) => id.trim())
    .filter(Boolean);
  if (!ids.length || ids.length > 100)
    throw taskCallDebugConfigurationError("每次读取 1～100 个 messageId，每行一个。");
  return [...new Set(ids)];
}
export function rpcCurl(
  baseUrl: string,
  taskId: string,
  caller: TaskCaller,
  body: unknown,
  operation = "items:call"
): string {
  const quote = (value: string) => "'" + value.replaceAll("'", "'\\''") + "'";
  const url =
    baseUrl.replace(/\/$/, "") +
    "/v1/tasks/" +
    encodeURIComponent(taskId) +
    "/" +
    operation;
  const headers = { "Content-Type": "application/json", ...callerHeaders(caller) };
  return (
    "curl -X POST " +
    quote(url) +
    Object.entries(headers)
      .map(([name, value]) => " -H " + quote(name + ": " + value))
      .join("") +
    " --data-raw " +
    quote(JSON.stringify(body))
  );
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

export function parseWorkerSelector(text: string): WorkerQuery {
  const value = parseJsonObject(text, "Worker Selector");
  if (
    Object.keys(value).length !== 2 ||
    !isNonBlankString(value.executorName) ||
    !("input" in value) ||
    value.input === null
  )
    throw invalidWorkerSelector();
  const inspect = (input: JsonValue, depth: number): void => {
    if (typeof input === "number" && !Number.isFinite(input))
      throw invalidWorkerSelector();
    if (input === null || typeof input !== "object") return;
    const children = Array.isArray(input) ? input : Object.values(input);
    if (depth >= 8 || children.length > 100) throw invalidWorkerSelector();
    children.forEach((child) => inspect(child, depth + 1));
  };
  inspect(value.input, 0);
  if (new TextEncoder().encode(JSON.stringify(value.input)).length > 64 * 1024)
    throw invalidWorkerSelector();
  return { executorName: value.executorName, input: value.input };
}

function invalidWorkerSelector(): Error {
  return taskCallDebugConfigurationError(
    'Worker Selector 需要 executorName 和非 null 的 JSON input，例如 {"executorName":"worker.any","input":{}}。' +
      "input 每容器最多 100 项、容器深度最多 8、最多 64 KiB；局部参数由 Matching 函数校验。"
  );
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}
