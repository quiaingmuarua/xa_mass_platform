import { RuntimeViewerError } from "./errors";
import { MOCK_PREVIEWS, MOCK_WORKER_GROUPS } from "./mock-data";
import type {
  RuntimeViewerDataSource,
  WorkerGroupBatchGetResponse,
  WorkerPreviewResponse
} from "./types";

export class MockRuntimeViewerDataSource implements RuntimeViewerDataSource {
  async loadWorkerGroups(
    workerGroupIds: string[],
    signal?: AbortSignal
  ): Promise<WorkerGroupBatchGetResponse> {
    throwIfAborted(signal);
    const byId = new Map(
      MOCK_WORKER_GROUPS.map((group) => [group.workerGroupId, group])
    );
    return {
      workerGroups: workerGroupIds
        .map((workerGroupId) => byId.get(workerGroupId))
        .filter((group) => group !== undefined)
        .map((group) => structuredClone(group)),
      missingWorkerGroupIds: workerGroupIds.filter(
        (workerGroupId) => !byId.has(workerGroupId)
      )
    };
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
