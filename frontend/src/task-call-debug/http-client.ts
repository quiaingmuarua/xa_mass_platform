import { type TaskCaller, callerHeaders } from "@/task-caller";
import axios, { AxiosError, type AxiosInstance } from "axios";

import { TaskCallDebugError } from "./errors";
import { taskCallDebugApiErrorSchema, taskCallDebugResultsSchema } from "./schemas";
import { taskCreateResponseSchema } from "@/task-management/schemas";
import type {
  RpcTaskPreparationClient,
  RpcTaskPrepareRequest
} from "@/task-workbench/rpc-task";
import type {
  TaskCallDebugClient,
  TaskCallDebugClientRequest,
  TaskCallDebugResults
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
  12012: "调用方声明无效，请刷新页面或重新登录。",
  12013: "无权访问该任务，请刷新页面或退出后重新登录。",
  19001: "Task Call 请求体无效。"
};

export class HttpTaskCallDebugClient
  implements TaskCallDebugClient, RpcTaskPreparationClient
{
  private readonly client: AxiosInstance;

  constructor(baseUrl: string, client?: AxiosInstance) {
    this.client = client ?? axios.create({ baseURL: baseUrl });
  }

  async prepareTask(
    caller: TaskCaller,
    taskId: string,
    request: RpcTaskPrepareRequest
  ): Promise<void> {
    const requestId = createRequestId("rpc-prepare");
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/rpc:prepare`,
        request,
        {
          headers: { "X-Request-Id": requestId, ...callerHeaders(caller) },
          timeout: 5_000
        }
      );
      if (response.status !== 200) throw schemaError(requestId);
      const parsed = taskCreateResponseSchema.safeParse(response.data);
      if (!parsed.success || parsed.data.taskId !== taskId)
        throw schemaError(requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async callTask(request: TaskCallDebugClientRequest): Promise<TaskCallDebugResults> {
    const requestId = createRequestId("task-call");
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(request.taskId)}/items:call`,
        request.request,
        {
          headers: { "X-Request-Id": requestId, ...callerHeaders(request.caller) },
          timeout: (request.request.waitTimeoutMillis ?? 60_000) + 5_000
        }
      );
      return readResults(
        response.status,
        response.data,
        request.request.items.map((item) => item.messageId),
        requestId
      );
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }
  async takeResults(
    caller: TaskCaller,
    taskId: string,
    messageIds: string[]
  ): Promise<TaskCallDebugResults> {
    const requestId = createRequestId("task-result-take");
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/results:take`,
        [...new Set(messageIds)],
        {
          headers: { "X-Request-Id": requestId, ...callerHeaders(caller) },
          timeout: 5_000
        }
      );
      return readResults(response.status, response.data, messageIds, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }
}

function readResults(
  status: number,
  data: unknown,
  ids: string[],
  requestId: string
): TaskCallDebugResults {
  if (status !== 200) throw schemaError(requestId);
  const parsed = taskCallDebugResultsSchema.safeParse(data);
  const expected = new Set(ids);
  if (
    !parsed.success ||
    Object.keys(parsed.data).length !== expected.size ||
    [...expected].some((id) => !Object.hasOwn(parsed.data, id))
  )
    throw schemaError(requestId);
  return parsed.data;
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
