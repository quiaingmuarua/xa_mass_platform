import { type TaskCaller, callerHeaders } from "@/task-caller";
import axios, { AxiosError, type AxiosInstance } from "axios";
import type { ZodType } from "zod";

import { FiniteTaskError } from "./errors";
import {
  taskApiErrorResponseSchema,
  taskActionOutcomeSchema,
  taskCreateResponseSchema,
  taskItemsAppendResponseSchema
} from "./schemas";
import type {
  FiniteTaskClient,
  TaskCreateApiRequest,
  TaskCreateApiResponse,
  TaskExportResult,
  TaskItemApiRequest,
  TaskItemsAppendApiResponse
} from "./types";

const SAFE_ERROR_MESSAGES: Record<number, string> = {
  12001: "The Task request is invalid.",
  12002: "The Task no longer exists.",
  12003: "Task data is temporarily unavailable.",
  12008: "This Task does not support the requested operation.",
  12009: "The Task lifecycle conflicts with its current state.",
  12010: "Task results are not ready.",
  12012: "开发调用方无效。",
  12013: "当前调用方不能访问此任务。",
  12011: "The selected WorkerGroup no longer exists.",
  19001: "The Task request body is invalid."
};

export class HttpFiniteTaskClient implements FiniteTaskClient {
  private readonly client: AxiosInstance;

  constructor(baseUrl: string, client?: AxiosInstance) {
    this.client = client ?? axios.create({ baseURL: baseUrl, timeout: 5_000 });
  }

  async createTask(
    caller: TaskCaller,
    request: TaskCreateApiRequest
  ): Promise<TaskCreateApiResponse> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post("/v1/tasks", request, {
        headers: requestHeaders(caller, requestId)
      });
      if (response.status !== 200) throw schemaError(requestId);
      return parseResponse(taskCreateResponseSchema, response.data, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async appendItems(
    caller: TaskCaller,
    taskId: string,
    items: TaskItemApiRequest[]
  ): Promise<TaskItemsAppendApiResponse> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/items`,
        items,
        { headers: requestHeaders(caller, requestId) }
      );
      if (response.status !== 200) throw schemaError(requestId);
      return parseResponse(taskItemsAppendResponseSchema, response.data, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async approveTask(
    caller: TaskCaller,
    taskId: string,
    expectedItemCount: number
  ): Promise<void> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/approve`,
        { expectedItemCount },
        { headers: requestHeaders(caller, requestId) }
      );
      if (response.status !== 200) throw schemaError(requestId);
      parseResponse(taskActionOutcomeSchema, response.data, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async exportResults(caller: TaskCaller, taskId: string): Promise<TaskExportResult> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/results:export`,
        undefined,
        {
          headers: requestHeaders(caller, requestId),
          responseType: "blob",
          validateStatus: () => true
        }
      );
      if (!(response.data instanceof Blob)) throw schemaError(requestId);
      if (response.status === 200)
        return {
          ready: true,
          fileName: `${taskId}-results.jsonl`,
          blob: response.data
        };

      const errorBody = await parseErrorBlob(response.data, requestId);
      if (response.status === 400 && errorBody.code === 12010) return { ready: false };
      throw apiError(errorBody, response.status, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async closeTask(caller: TaskCaller, taskId: string): Promise<void> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/close`,
        undefined,
        { headers: requestHeaders(caller, requestId) }
      );
      if (response.status !== 200) throw schemaError(requestId);
      parseResponse(taskActionOutcomeSchema, response.data, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async ensureDirectory(caller: TaskCaller, taskId: string): Promise<void> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/directory:ensure`,
        undefined,
        { headers: requestHeaders(caller, requestId) }
      );
      if (response.status !== 200) throw schemaError(requestId);
      parseResponse(taskActionOutcomeSchema, response.data, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }
}

function requestHeaders(caller: TaskCaller, requestId: string): Record<string, string> {
  return { "X-Request-Id": requestId, ...callerHeaders(caller) };
}

function parseResponse<T>(schema: ZodType<T>, value: unknown, requestId: string): T {
  const parsed = schema.safeParse(value);
  if (!parsed.success) throw schemaError(requestId);
  return parsed.data;
}

function schemaError(requestId: string): FiniteTaskError {
  return new FiniteTaskError({
    kind: "schema",
    message: "Task API returned an unrecognized response.",
    requestId
  });
}

function mapHttpError(error: unknown, fallbackRequestId: string): FiniteTaskError {
  if (error instanceof FiniteTaskError) return error;
  if (!(error instanceof AxiosError)) {
    return new FiniteTaskError({
      kind: "network",
      message: "Unable to connect to the Task API.",
      requestId: fallbackRequestId,
      cause: error
    });
  }

  const status = error.response?.status;
  const parsed = taskApiErrorResponseSchema.safeParse(error.response?.data);
  if (status !== undefined && parsed.success)
    return apiError(parsed.data, status, fallbackRequestId, error);
  return new FiniteTaskError({
    kind: status === undefined ? "network" : "http",
    message:
      status === undefined
        ? "Unable to connect to the Task API."
        : "Task API returned an unrecognized error.",
    requestId: fallbackRequestId,
    status,
    cause: error
  });
}

async function parseErrorBlob(blob: Blob, requestId: string) {
  try {
    const parsed = taskApiErrorResponseSchema.safeParse(JSON.parse(await blob.text()));
    if (parsed.success) return parsed.data;
  } catch {
    // Fall through to the stable schema error below.
  }
  throw schemaError(requestId);
}

function apiError(
  body: { code: number; message: string; requestId: string | null; taskId?: string },
  status: number,
  fallbackRequestId: string,
  cause?: unknown
): FiniteTaskError {
  return new FiniteTaskError({
    kind: "http",
    message:
      SAFE_ERROR_MESSAGES[body.code] ?? "Task API returned an unrecognized error.",
    requestId: body.requestId ?? fallbackRequestId,
    taskId: body.taskId,
    code: body.code,
    status,
    cause
  });
}

function createRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `finite-task-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
