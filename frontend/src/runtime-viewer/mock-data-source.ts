import { RuntimeViewerError } from "./errors";
import { MOCK_CONFIGURED_RESOURCES, MOCK_PREVIEWS } from "./mock-data";
import type {
  ConfiguredRuntimeResourcesResponse,
  RuntimeViewerDataSource,
  WorkerPreviewResponse
} from "./types";

export class MockRuntimeViewerDataSource implements RuntimeViewerDataSource {
  async loadConfiguredResources(
    signal?: AbortSignal
  ): Promise<ConfiguredRuntimeResourcesResponse> {
    throwIfAborted(signal);
    return structuredClone(MOCK_CONFIGURED_RESOURCES);
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
