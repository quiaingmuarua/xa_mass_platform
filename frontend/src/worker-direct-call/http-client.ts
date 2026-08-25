import axios, { AxiosError, type AxiosInstance } from "axios";

import { WorkerDirectCallError } from "./errors";
import { validateWorkerDirectCallRequest } from "./model";
import {
  workerDirectCallApiErrorSchema,
  workerDirectCallResponseSchema
} from "./schemas";
import type {
  WorkerDirectCallClient,
  WorkerDirectCallRequest,
  WorkerDirectCallResult
} from "./types";

const SAFE_ERROR_MESSAGES: Record<number, string> = {
  17001: "Direct Call 请求无效。",
  17002: "Direct Call 目标不存在。",
  17003: "Direct Call 容量已满，请稍后重试。",
  17004: "Direct Call 当前不可用。",
  19001: "Direct Call 请求体无效。"
};

export class HttpWorkerDirectCallClient implements WorkerDirectCallClient {
  private readonly client: AxiosInstance;

  constructor(baseUrl: string, client?: AxiosInstance) {
    this.client = client ?? axios.create({ baseURL: baseUrl });
  }

  async callWorker(
    rawRequest: WorkerDirectCallRequest
  ): Promise<WorkerDirectCallResult> {
    const request = validateWorkerDirectCallRequest(rawRequest);
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/worker-delivery/endpoint-managers/${encodeURIComponent(
          request.endpointManagerId
        )}/direct-calls`,
        {
          workerGroupId: request.workerGroupId,
          workerPayloads: { [request.workerId]: request.payloadText },
          messageType: request.eventName,
          waitTimeoutMillis: request.waitTimeoutMillis
        },
        {
          headers: { "X-Request-Id": requestId },
          timeout: request.waitTimeoutMillis + 5_000
        }
      );
      if (response.status !== 200) throw schemaError(requestId);
      const parsed = workerDirectCallResponseSchema.safeParse(response.data);
      if (!parsed.success) throw schemaError(requestId);
      const resultKeys = Object.keys(parsed.data.results);
      const target = parsed.data.results[request.workerId];
      if (
        resultKeys.length !== 1 ||
        resultKeys[0] !== request.workerId ||
        target === undefined ||
        (target.status === "observed") !== (parsed.data.status === "observed")
      ) {
        throw schemaError(requestId);
      }
      return {
        directCallId: parsed.data.directCallId,
        status: parsed.data.status,
        target
      };
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }
}

function mapHttpError(
  error: unknown,
  fallbackRequestId: string
): WorkerDirectCallError {
  if (error instanceof WorkerDirectCallError) return error;
  if (!(error instanceof AxiosError)) {
    return new WorkerDirectCallError({
      kind: "network",
      message: "无法连接 Direct Call API。",
      requestId: fallbackRequestId,
      cause: error
    });
  }
  const status = error.response?.status;
  const parsed = workerDirectCallApiErrorSchema.safeParse(error.response?.data);
  if (status !== undefined && parsed.success) {
    return new WorkerDirectCallError({
      kind: "http",
      message:
        SAFE_ERROR_MESSAGES[parsed.data.code] ?? "Direct Call API 返回未知错误。",
      requestId: parsed.data.requestId ?? fallbackRequestId,
      code: parsed.data.code,
      status,
      cause: error
    });
  }
  return new WorkerDirectCallError({
    kind: status === undefined ? "network" : "http",
    message:
      status === undefined
        ? "无法连接 Direct Call API。"
        : "Direct Call API 返回无法识别的错误。",
    requestId: fallbackRequestId,
    status,
    cause: error
  });
}

function schemaError(requestId: string): WorkerDirectCallError {
  return new WorkerDirectCallError({
    kind: "schema",
    message: "Direct Call API 返回无法识别的响应。",
    requestId
  });
}

function createRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `worker-direct-call-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
