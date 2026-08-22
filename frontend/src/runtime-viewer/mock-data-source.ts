import { RuntimeViewerError } from "./errors";
import {
  MOCK_CONFIGURED_RESOURCES,
  MOCK_PREVIEWS,
  MOCK_WORKER_GROUPS
} from "./mock-data";
import type {
  ConfiguredRuntimeResourcesResponse,
  RuntimeViewerDataSource,
  WorkerGroupPreviewResponse,
  WorkerPreviewResponse
} from "./types";

export class MockRuntimeViewerDataSource implements RuntimeViewerDataSource {
  async loadConfiguredResources(
    signal?: AbortSignal
  ): Promise<ConfiguredRuntimeResourcesResponse> {
    throwIfAborted(signal);
    return structuredClone(MOCK_CONFIGURED_RESOURCES);
  }

  async previewWorkerGroups(
    sampleLimit: number,
    signal?: AbortSignal
  ): Promise<WorkerGroupPreviewResponse> {
    throwIfAborted(signal);
    const workerGroups = MOCK_WORKER_GROUPS.slice(0, sampleLimit);
    return structuredClone({
      sampleLimit,
      sampledCount: workerGroups.length,
      returnedCount: workerGroups.length,
      unreadableCount: 0,
      generatedAt: "2026-07-31T12:00:00.000Z",
      workerGroups
    });
  }

  async previewWorkers(
    workerGroupId: string,
    sampleLimit: number,
    _filter: null,
    signal?: AbortSignal
  ): Promise<WorkerPreviewResponse> {
    throwIfAborted(signal);

    const preview = MOCK_PREVIEWS[workerGroupId];
    if (preview === undefined) {
      throw new RuntimeViewerError({
        kind: "http",
        message: "请求的 WorkerGroup 不存在。",
        requestId: "mock-request-15001",
        code: 15001,
        status: 404
      });
    }

    const workers = preview.workers.slice(0, sampleLimit);
    const unreadableCount = sampleLimit > workers.length ? preview.unreadableCount : 0;
    return structuredClone({
      ...preview,
      sampleLimit,
      sampledCount: workers.length + unreadableCount,
      returnedCount: workers.length,
      unreadableCount,
      workers
    });
  }
}

function throwIfAborted(signal?: AbortSignal): void {
  if (!signal?.aborted) {
    return;
  }
  throw new RuntimeViewerError({
    kind: "cancelled",
    message: "请求已取消。"
  });
}
