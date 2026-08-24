import axios, { AxiosError, type AxiosInstance } from "axios";
import type { ZodType } from "zod";

import { FiniteTaskError } from "./errors";
import {
  taskApiErrorResponseSchema,
  taskApprovalResponseSchema,
  taskCreateResponseSchema,
  taskExportNotReadySchema,
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
  11005: "The Task lifecycle conflicts with its current state.",
  12001: "The Task request is invalid.",
  12002: "The Task no longer exists.",
  12003: "Task data is temporarily unavailable.",
  12008: "This Task does not support the requested operation.",
  15001: "The selected WorkerGroup no longer exists.",
  19001: "The Task request body is invalid."
};

export class HttpFiniteTaskClient implements FiniteTaskClient {
  private readonly client: AxiosInstance;

  constructor(baseUrl: string, client?: AxiosInstance) {
    this.client = client ?? axios.create({ baseURL: baseUrl, timeout: 5_000 });
  }

  async createTask(request: TaskCreateApiRequest): Promise<TaskCreateApiResponse> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post("/v1/tasks", request, {
        headers: requestHeaders(requestId)
      });
      return parseResponse(taskCreateResponseSchema, response.data, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async appendItems(
    taskId: string,
    items: TaskItemApiRequest[]
  ): Promise<TaskItemsAppendApiResponse> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/items`,
        { items },
        { headers: requestHeaders(requestId) }
      );
      return parseResponse(taskItemsAppendResponseSchema, response.data, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async approveTask(taskId: string): Promise<void> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/approve`,
        undefined,
        { headers: requestHeaders(requestId) }
      );
      parseResponse(taskApprovalResponseSchema, response.data, requestId);
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async exportResults(
    taskId: string,
    waitTimeoutMillis: number
  ): Promise<TaskExportResult> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/tasks/${encodeURIComponent(taskId)}/results:export`,
        { waitTimeoutMillis },
        {
          headers: requestHeaders(requestId),
          responseType: "blob",
          timeout: waitTimeoutMillis + 5_000,
          validateStatus: (status) => status === 200 || status === 202
        }
      );
      if (!(response.data instanceof Blob)) throw schemaError(requestId);
      if (response.status === 202) {
        const parsed = taskExportNotReadySchema.safeParse(
          JSON.parse(await response.data.text())
        );
        if (!parsed.success) throw schemaError(requestId);
        return { ready: false };
      }
      return {
        ready: true,
        fileName: `${taskId}-results.jsonl`,
        blob: response.data
      };
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }
}

function requestHeaders(requestId: string): Record<string, string> {
  return { "X-Request-Id": requestId };
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

  const parsed = taskApiErrorResponseSchema.safeParse(error.response?.data);
  const code = parsed.success ? parsed.data.code : undefined;
  const status = error.response?.status;
  return new FiniteTaskError({
    kind: status === undefined ? "network" : "http",
    message:
      code === undefined
        ? "Task API returned an unrecognized error."
        : (SAFE_ERROR_MESSAGES[code] ?? "Task API returned an unrecognized error."),
    requestId: (parsed.success ? parsed.data.requestId : null) ?? fallbackRequestId,
    code,
    status,
    cause: error
  });
}

function createRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `finite-task-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
