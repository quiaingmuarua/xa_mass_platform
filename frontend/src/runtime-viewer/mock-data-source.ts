import type { MockUserDirectorySource } from "@/user-directory-source";
import { RuntimeViewerError } from "./errors";
import { type TaskCaller, requireCaller } from "@/task-caller";
import { MOCK_PREVIEWS, MOCK_TASK_PREVIEW, MOCK_WORKER_GROUPS } from "./mock-data";
import type {
  RuntimeViewerDataSource,
  TaskPreviewResponse,
  WorkerGroupPreviewResponse,
  WorkerPreviewResponse
} from "./types";

export class MockRuntimeViewerDataSource implements RuntimeViewerDataSource {
  constructor(
    private readonly caller: TaskCaller,
    private readonly directory: MockUserDirectorySource
  ) {
    this.caller = requireCaller(caller);
  }
  async previewTasks(
    sampleLimit: number,
    signal?: AbortSignal
  ): Promise<TaskPreviewResponse> {
    this.directory.resolveCaller(this.caller, undefined, "runtime.view");
    throwIfAborted(signal);
    return structuredClone({
      ...MOCK_TASK_PREVIEW,
      sampleLimit,
      entries: MOCK_TASK_PREVIEW.entries.slice(0, sampleLimit)
    });
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
        status: 400
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
