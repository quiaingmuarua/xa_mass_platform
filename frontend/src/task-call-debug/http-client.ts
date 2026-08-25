import axios, { AxiosError, type AxiosInstance } from "axios";

import { TaskCallDebugError } from "./errors";
import {
  taskCallDebugApiErrorSchema,
  taskCallDebugResponseSchema,
  taskCallDebugResultLoadResponseSchema
} from "./schemas";
import type {
  TaskCallDebugClient,
  TaskCallDebugClientRequest,
  TaskCallDebugOutcome
} from "./types";

const SAFE_ERROR_MESSAGES: Record<number, string> = {
  12001: "Task Call 请求无效。",
  12002: "Task 已不存在。",
  12003: "Task Owner 暂时不可用。",
  12005: "该 WorkerGroup 没有配置 Task Call。",
  12006: "Task Call 配置与当前状态冲突。",
  12007: "Task Call 配置暂时不可用。",
  12008: "该 Task 不支持 Task Call。",
  12009: "Task Call 与当前 Task 状态冲突。",
  12010: "Task Result 尚未就绪。",
  12011: "Task 对应的 WorkerGroup 已不存在。",
  19001: "Task Call 请求体无效。"
};

export class HttpTaskCallDebugClient implements TaskCallDebugClient {
  private readonly client: AxiosInstance;

  constructor(baseUrl: string, client?: AxiosInstance) {
    this.client = client ?? axios.create({ baseURL: baseUrl });
  }

  async callTask(request: TaskCallDebugClientRequest): Promise<TaskCallDebugOutcome> {
    const requestId = createRequestId("task-call");
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(request.taskId)}/items:call`,
        {
          items: [
            {
              messageId: request.messageId,
              eventCode: request.eventName,
              payload: request.payload,
              priority: 5,
              allocationRule: request.allocationRule
            }
          ],
          waitTimeoutMillis: request.waitTimeoutMillis
        },
        {
          headers: { "X-Request-Id": requestId },
          timeout: request.waitTimeoutMillis + 5_000
        }
      );
      if (response.status !== 200) throw schemaError(requestId);
      const parsed = taskCallDebugResponseSchema.safeParse(response.data);
      if (!parsed.success) throw schemaError(requestId);
      const resultIds = Object.keys(parsed.data.results);
      const outcome = parsed.data.results[request.messageId];
      if (
        resultIds.length !== 1 ||
        resultIds[0] !== request.messageId ||
        outcome === undefined
      ) {
        throw schemaError(requestId);
      }
      return outcome;
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async loadResult(taskId: string, messageId: string): Promise<string | undefined> {
    const requestId = createRequestId("task-result-load");
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/results:load`,
        { messageIds: [messageId] },
        {
          headers: { "X-Request-Id": requestId },
          timeout: 5_000
        }
      );
      if (response.status !== 200) throw schemaError(requestId);
      const parsed = taskCallDebugResultLoadResponseSchema.safeParse(response.data);
      if (!parsed.success) throw schemaError(requestId);
      const resultIds = Object.keys(parsed.data.results);
      if (resultIds.length === 0) return undefined;
      if (resultIds.length !== 1 || resultIds[0] !== messageId) {
        throw schemaError(requestId);
      }
      return parsed.data.results[messageId];
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }
}

function mapHttpError(error: unknown, fallbackRequestId: string): TaskCallDebugError {
  if (error instanceof TaskCallDebugError) return error;
  if (!(error instanceof AxiosError)) {
    return new TaskCallDebugError({
      kind: "network",
      message: "无法连接 Task API。",
      requestId: fallbackRequestId,
      cause: error
    });
  }
  const status = error.response?.status;
  const parsed = taskCallDebugApiErrorSchema.safeParse(error.response?.data);
  if (status !== undefined && parsed.success) {
    return new TaskCallDebugError({
      kind: "http",
      message: SAFE_ERROR_MESSAGES[parsed.data.code] ?? "Task API 返回未知错误。",
      requestId: parsed.data.requestId ?? fallbackRequestId,
      code: parsed.data.code,
      status,
      cause: error
    });
  }
  return new TaskCallDebugError({
    kind: status === undefined ? "network" : "http",
    message:
      status === undefined ? "无法连接 Task API。" : "Task API 返回无法识别的错误。",
    requestId: fallbackRequestId,
    status,
    cause: error
  });
}

function schemaError(requestId: string): TaskCallDebugError {
  return new TaskCallDebugError({
    kind: "schema",
    message: "Task API 返回无法识别的响应。",
    requestId
  });
}

function createRequestId(prefix: string): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
