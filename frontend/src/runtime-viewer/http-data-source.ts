import axios, { AxiosError, type AxiosInstance } from "axios";

import { RuntimeViewerError } from "./errors";
import {
  apiErrorResponseSchema,
  workerGroupBatchGetResponseSchema,
  workerPreviewResponseSchema
} from "./schemas";
import type {
  RuntimeViewerDataSource,
  WorkerGroupBatchGetResponse,
  WorkerPreviewResponse
} from "./types";

const SAFE_ERROR_MESSAGES: Record<number, string> = {
  15001: "请求的 WorkerGroup 不存在。",
  15002: "Runtime View 暂时无法从 Owner 读取数据。",
  15003: "当前版本尚未开放后端 Filter DSL。",
  19001: "Runtime View 请求参数不正确。"
};

export class HttpRuntimeViewerDataSource implements RuntimeViewerDataSource {
  private readonly client: AxiosInstance;

  constructor(baseUrl: string, client?: AxiosInstance) {
    this.client =
      client ??
      axios.create({
        baseURL: baseUrl,
        timeout: 5_000,
        headers: {
          "Content-Type": "application/json"
        }
      });
  }

  async loadWorkerGroups(
    workerGroupIds: string[],
    signal?: AbortSignal
  ): Promise<WorkerGroupBatchGetResponse> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        "/v1/runtime-view/worker-groups:batch-get",
        { workerGroupIds },
        {
          signal,
          headers: {
            "X-Request-Id": requestId
          }
        }
      );
      const parsed = parseResponse(
        workerGroupBatchGetResponseSchema,
        response.data,
        requestId
      );
      assertWorkerGroupResponse(parsed, workerGroupIds, requestId);
      return parsed;
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }

  async previewWorkers(
    workerGroupId: string,
    sampleLimit: number,
    filter: null,
    signal?: AbortSignal
  ): Promise<WorkerPreviewResponse> {
    const requestId = createRequestId();
    try {
      const response = await this.client.post(
        `/v1/runtime-view/worker-groups/${encodeURIComponent(
          workerGroupId
        )}/workers:preview`,
        { sampleLimit, filter },
        {
          signal,
          headers: {
            "X-Request-Id": requestId
          }
        }
      );
      const parsed = parseResponse(
        workerPreviewResponseSchema,
        response.data,
        requestId
      );
      if (
        parsed.workerGroupId !== workerGroupId ||
        parsed.sampleLimit !== sampleLimit
      ) {
        throw schemaError(requestId);
      }
      return parsed;
    } catch (error) {
      throw mapHttpError(error, requestId);
    }
  }
}

function parseResponse<T>(
  schema: {
    safeParse(value: unknown): { success: true; data: T } | { success: false };
  },
  value: unknown,
  requestId: string
): T {
  const parsed = schema.safeParse(value);
  if (!parsed.success) {
    throw schemaError(requestId);
  }
  return parsed.data;
}

function assertWorkerGroupResponse(
  response: WorkerGroupBatchGetResponse,
  requestedIds: string[],
  requestId: string
): void {
  const positions = new Map(
    requestedIds.map((workerGroupId, index) => [workerGroupId, index])
  );
  const returnedIds = [
    ...response.workerGroups.map((group) => group.workerGroupId),
    ...response.missingWorkerGroupIds
  ];
  if (
    returnedIds.length !== requestedIds.length ||
    returnedIds.some((workerGroupId) => !positions.has(workerGroupId)) ||
    !isRequestOrdered(
      response.workerGroups.map((group) => group.workerGroupId),
      positions
    ) ||
    !isRequestOrdered(response.missingWorkerGroupIds, positions)
  ) {
    throw schemaError(requestId);
  }
}

function isRequestOrdered(
  workerGroupIds: string[],
  positions: Map<string, number>
): boolean {
  return workerGroupIds.every(
    (workerGroupId, index) =>
      index === 0 ||
      (positions.get(workerGroupIds[index - 1]) ?? -1) <
        (positions.get(workerGroupId) ?? -1)
  );
}

function schemaError(requestId: string): RuntimeViewerError {
  return new RuntimeViewerError({
    kind: "schema",
    message: "Runtime View 返回了无法识别的数据。",
    requestId
  });
}

function mapHttpError(error: unknown, fallbackRequestId: string): RuntimeViewerError {
  if (error instanceof RuntimeViewerError) {
    return error;
  }
  if (axios.isCancel(error)) {
    return new RuntimeViewerError({
      kind: "cancelled",
      message: "请求已取消。",
      requestId: fallbackRequestId,
      cause: error
    });
  }
  if (!(error instanceof AxiosError)) {
    return new RuntimeViewerError({
      kind: "network",
      message: "无法连接 Runtime View API。",
      requestId: fallbackRequestId,
      cause: error
    });
  }

  const parsedError = apiErrorResponseSchema.safeParse(error.response?.data);
  const code = parsedError.success ? parsedError.data.code : undefined;
  const responseRequestId = parsedError.success
    ? parsedError.data.requestId || undefined
    : undefined;
  const status = error.response?.status;
  const message =
    code === undefined
      ? status === undefined
        ? "无法连接 Runtime View API。"
        : "Runtime View API 返回了读取错误。"
      : (SAFE_ERROR_MESSAGES[code] ?? "Runtime View API 返回了未识别的读取错误。");

  return new RuntimeViewerError({
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
  return `runtime-view-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
