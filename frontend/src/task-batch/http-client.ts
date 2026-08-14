import axios, { AxiosError, type AxiosInstance } from "axios";
import type { ZodType } from "zod";

import { TaskBatchError } from "./errors";
import {
  taskBatchApiErrorResponseSchema,
  taskBatchInputUploadResponseSchema,
  taskBatchRunResponseSchema
} from "./schemas";
import type {
  TaskBatchClient,
  TaskBatchInputUploadResponse,
  TaskBatchRunRequest,
  TaskBatchRunResponse
} from "./types";

const SAFE_ERROR_MESSAGES: Record<number, string> = {
  16001: "The WorkerGroup, Event, Payload Key, file, or wait value is invalid.",
  16002: "The configured WorkerGroup, input file, or output file was not found.",
  16003: "The input or output file already exists.",
  16004: "Task Batch cannot access its internal Task, results, or files right now."
};

export class HttpTaskBatchClient implements TaskBatchClient {
  private readonly client: AxiosInstance;

  constructor(baseUrl: string, client?: AxiosInstance) {
    this.client = client ?? axios.create({ baseURL: baseUrl, timeout: 5_000 });
  }

  async uploadInput(
    fileName: string,
    content: ArrayBuffer
  ): Promise<TaskBatchInputUploadResponse> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/task-batches/input-files/${encodeURIComponent(fileName)}`,
        content,
        {
          headers: {
            ...requestHeaders(requestId),
            "Content-Type": "text/plain; charset=utf-8"
          }
        }
      );
      const parsed = parseResponse(
        taskBatchInputUploadResponseSchema,
        response.data,
        requestId
      );
      if (parsed.fileName !== fileName || parsed.byteCount !== content.byteLength) {
        throw schemaError(requestId);
      }
      return parsed;
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async run(request: TaskBatchRunRequest): Promise<TaskBatchRunResponse> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post("/v1/task-batches/runs", request, {
        headers: requestHeaders(requestId),
        timeout: Math.min(request.maximumWaitMillis + 30_000, 330_000)
      });
      const parsed = parseResponse(
        taskBatchRunResponseSchema,
        response.data,
        requestId
      );
      if (
        parsed.workerGroupId !== request.workerGroupId ||
        parsed.eventCode !== request.eventCode ||
        parsed.payloadKey !== request.payloadKey ||
        parsed.inputFile !== request.inputFile
      ) {
        throw schemaError(requestId);
      }
      return parsed;
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async downloadOutput(fileName: string): Promise<Blob> {
    const requestId = createRequestId();
    try {
      const response = await this.client.get(
        `/v1/task-batches/output-files/${encodeURIComponent(fileName)}`,
        {
          headers: requestHeaders(requestId),
          responseType: "blob"
        }
      );
      if (!(response.data instanceof Blob)) {
        throw schemaError(requestId);
      }
      return response.data;
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
  if (!parsed.success) {
    throw schemaError(requestId);
  }
  return parsed.data;
}

function schemaError(requestId: string): TaskBatchError {
  return new TaskBatchError({
    kind: "schema",
    message: "Task Batch returned an unrecognized response.",
    requestId
  });
}

function mapHttpError(error: unknown, fallbackRequestId: string): TaskBatchError {
  if (error instanceof TaskBatchError) {
    return error;
  }
  if (!(error instanceof AxiosError)) {
    return new TaskBatchError({
      kind: "network",
      message: "Unable to connect to the Task Batch API.",
      requestId: fallbackRequestId,
      cause: error
    });
  }

  const parsedError = taskBatchApiErrorResponseSchema.safeParse(error.response?.data);
  const code = parsedError.success ? parsedError.data.code : undefined;
  const responseRequestId = parsedError.success
    ? parsedError.data.requestId || undefined
    : undefined;
  const status = error.response?.status;
  const message =
    code === undefined
      ? status === undefined
        ? "Unable to connect to the Task Batch API."
        : "Task Batch returned an unrecognized error."
      : (SAFE_ERROR_MESSAGES[code] ?? "Task Batch returned an unrecognized error.");

  return new TaskBatchError({
    kind: status === undefined ? "network" : "http",
    message,
    requestId: responseRequestId ?? fallbackRequestId,
    code,
    status,
    cause: error
  });
}

function createRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `task-batch-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
