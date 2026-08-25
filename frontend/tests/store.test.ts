import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { RuntimeViewerError } from "@/runtime-viewer/errors";
import { createRuntimeViewerStore } from "@/stores/runtime-viewer";
import type {
  RuntimeViewerConfig,
  RuntimeViewerDataSource,
  WorkerGroupPreviewResponse,
  WorkerPreviewResponse
} from "@/runtime-viewer/types";
import {
  groupPreview,
  preview,
  taskPreview,
  taskPreviewEntry,
  worker
} from "./fixtures";

const config: RuntimeViewerConfig = {
  mode: "api",
  apiBaseUrl: "/api"
};

describe("runtime viewer store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("loads only Task Preview for Tasks and lazily loads Groups for the Workbench", async () => {
    const previewWorkers = vi.fn(
      async (workerGroupId: string): Promise<WorkerPreviewResponse> =>
        preview(workerGroupId, [worker(workerGroupId, `${workerGroupId}-worker`)])
    );
    const dataSource = source(previewWorkers);
    const store = createRuntimeViewerStore(config, dataSource);

    await store.initializeTaskView();
    expect(previewWorkers).not.toHaveBeenCalled();
    expect(store.workerGroupIds).toEqual([]);
    expect(store.tasks).toHaveLength(2);
    expect(dataSource.previewTasks).toHaveBeenCalledWith(100, expect.any(AbortSignal));
    expect(dataSource.previewWorkerGroups).not.toHaveBeenCalled();

    await store.initializeWorkerGroups();
    expect(store.workerGroupIds).toEqual(["group-a", "group-b"]);
    expect(dataSource.previewWorkerGroups).toHaveBeenCalledTimes(1);
    expect(previewWorkers).not.toHaveBeenCalled();

    await store.initializeWorkerView();
    expect(dataSource.previewWorkerGroups).toHaveBeenCalledTimes(1);
    expect(previewWorkers).toHaveBeenCalledTimes(1);
    expect(previewWorkers.mock.calls[0]?.[0]).toBe("group-a");

    await store.selectGroup("group-b");
    expect(previewWorkers).toHaveBeenCalledTimes(2);
    expect(previewWorkers.mock.calls[1]?.[0]).toBe("group-b");

    await store.selectGroup("group-a");
    expect(previewWorkers).toHaveBeenCalledTimes(2);

    await store.refreshActiveGroup();
    expect(previewWorkers).toHaveBeenCalledTimes(3);
    expect(previewWorkers.mock.calls[2]?.[0]).toBe("group-a");
  });

  it("waits for the in-flight Group preview before loading the Worker sample", async () => {
    let resolveGroups!: (value: WorkerGroupPreviewResponse) => void;
    const previewWorkerGroups = vi.fn(
      () =>
        new Promise<WorkerGroupPreviewResponse>((resolve) => {
          resolveGroups = resolve;
        })
    );
    const previewWorkers = vi.fn(async (workerGroupId: string) =>
      preview(workerGroupId, [worker(workerGroupId, "worker-a")])
    );
    const previewTasks = vi.fn(async () => taskPreview([]));
    const store = createRuntimeViewerStore(config, {
      previewTasks,
      previewWorkerGroups,
      previewWorkers
    });

    const firstLoad = store.initializeWorkerView();
    const secondLoad = store.initializeWorkerView();
    expect(previewWorkerGroups).toHaveBeenCalledTimes(1);
    expect(previewWorkers).not.toHaveBeenCalled();

    resolveGroups(groupPreview(["group-a"]));
    await Promise.all([firstLoad, secondLoad]);

    expect(store.entries).toEqual([]);
    expect(previewTasks).not.toHaveBeenCalled();
    expect(previewWorkers).toHaveBeenCalledTimes(1);
    expect(previewWorkers.mock.calls[0]?.[0]).toBe("group-a");
  });

  it("refreshes only the Group sample and preserves cached Workers for surviving Groups", async () => {
    const previewWorkerGroups = vi
      .fn()
      .mockResolvedValueOnce(groupPreview(["group-b", "group-a"]))
      .mockResolvedValueOnce(groupPreview(["group-c", "group-b"]));
    const previewWorkers = vi.fn(async (workerGroupId: string) =>
      preview(workerGroupId, [worker(workerGroupId, `${workerGroupId}-worker`)])
    );
    const store = createRuntimeViewerStore(config, {
      previewTasks: vi.fn(async () => taskPreview([])),
      previewWorkerGroups,
      previewWorkers
    });

    await store.initializeWorkerView();
    expect(store.workerGroupIds).toEqual(["group-a", "group-b"]);
    await store.selectGroup("group-b");
    expect(previewWorkers).toHaveBeenCalledTimes(2);

    await store.refreshWorkerGroups();

    expect(store.workerGroupIds).toEqual(["group-b", "group-c"]);
    expect(store.activeWorkerGroupId).toBe("group-b");
    expect(store.samples["group-a"]).toBeUndefined();
    expect(store.samples["group-b"]?.sample?.workerGroupId).toBe("group-b");
    expect(previewWorkers).toHaveBeenCalledTimes(2);
  });

  it("retains the last good sample and marks it stale after refresh failure", async () => {
    const first = preview("group-a", [worker("group-a", "worker-old")]);
    const previewWorkers = vi
      .fn()
      .mockResolvedValueOnce(first)
      .mockRejectedValueOnce(
        new RuntimeViewerError({
          kind: "http",
          message: "Runtime View 暂时无法从 Owner 读取数据。",
          requestId: "request-503",
          code: 15002,
          status: 503
        })
      );
    const store = createRuntimeViewerStore(config, source(previewWorkers, ["group-a"]));

    await store.initializeWorkerView();
    await store.refreshActiveGroup();

    expect(store.activeSample?.workers[0]?.workerId).toBe("worker-old");
    expect(store.activeSampleState).toMatchObject({
      status: "error",
      stale: true,
      error: {
        requestId: "request-503"
      }
    });
  });

  it("aborts an older refresh and atomically applies only the newest sample", async () => {
    const initial = preview("group-a", [worker("group-a", "worker-initial")]);
    const deferred: Array<{
      resolve(value: WorkerPreviewResponse): void;
      reject(reason: unknown): void;
      signal?: AbortSignal;
    }> = [];
    const previewWorkers = vi
      .fn()
      .mockResolvedValueOnce(initial)
      .mockImplementation(
        (
          _workerGroupId: string,
          _sampleLimit: number,
          _filter: null,
          signal?: AbortSignal
        ) =>
          new Promise<WorkerPreviewResponse>((resolve, reject) => {
            const call = { resolve, reject, signal };
            deferred.push(call);
            signal?.addEventListener(
              "abort",
              () =>
                reject(
                  new RuntimeViewerError({
                    kind: "cancelled",
                    message: "请求已取消。"
                  })
                ),
              { once: true }
            );
          })
      );
    const store = createRuntimeViewerStore(config, source(previewWorkers, ["group-a"]));
    await store.initializeWorkerView();

    const firstRefresh = store.refreshActiveGroup();
    await Promise.resolve();
    const secondRefresh = store.refreshActiveGroup();
    await Promise.resolve();

    expect(deferred[0]?.signal?.aborted).toBe(true);
    deferred[1]?.resolve(
      preview(
        "group-a",
        [worker("group-a", "worker-new")],
        0,
        "2026-07-31T12:01:00.000Z"
      )
    );
    await Promise.all([firstRefresh, secondRefresh]);

    expect(store.activeSample?.workers[0]?.workerId).toBe("worker-new");
    expect(store.activeSampleState?.stale).toBe(false);
  });

  it("does not load WorkerGroups while opening the Task page", async () => {
    const previewTasks = vi.fn(async () => taskPreview([]));
    const previewWorkerGroups = vi.fn(async () => groupPreview([]));
    const store = createRuntimeViewerStore(config, {
      previewTasks,
      previewWorkerGroups,
      previewWorkers: vi.fn()
    });

    await store.initializeTaskView();

    expect(previewTasks).toHaveBeenCalledTimes(1);
    expect(previewWorkerGroups).not.toHaveBeenCalled();
    expect(store.taskPreviewState.status).toBe("ready");
    expect(store.entries).toEqual([]);
  });

  it("preserves the last Task Preview and marks it stale on refresh failure", async () => {
    const previewTasks = vi
      .fn()
      .mockResolvedValueOnce(taskPreview([taskPreviewEntry("group-a")]))
      .mockRejectedValueOnce(
        new RuntimeViewerError({
          kind: "http",
          message: "Runtime View 暂时无法从 Owner 读取数据。",
          requestId: "preview-503",
          code: 15002,
          status: 503
        })
      );
    const store = createRuntimeViewerStore(config, {
      previewTasks,
      previewWorkerGroups: vi.fn(async () => groupPreview(["group-a"])),
      previewWorkers: vi.fn()
    });

    await store.initializeTaskView();
    await store.refreshTasks();

    expect(store.entries).toHaveLength(1);
    expect(store.taskPreviewState.status).toBe("error");
    expect(store.taskPreviewState.stale).toBe(true);
    expect(store.taskPreviewState.error?.requestId).toBe("preview-503");
  });
});

function source(
  previewWorkers: RuntimeViewerDataSource["previewWorkers"],
  workerGroupIds = ["group-a", "group-b"]
): RuntimeViewerDataSource {
  return {
    previewTasks: vi.fn(async () =>
      taskPreview(
        workerGroupIds.map((workerGroupId) => taskPreviewEntry(workerGroupId))
      )
    ),
    previewWorkerGroups: vi.fn(async () => groupPreview(workerGroupIds)),
    previewWorkers
  };
}
