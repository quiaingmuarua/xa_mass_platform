import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { RuntimeViewerError } from "@/runtime-viewer/errors";
import { createRuntimeViewerStore } from "@/stores/runtime-viewer";
import type {
  ConfiguredRuntimeResourcesResponse,
  RuntimeViewerConfig,
  RuntimeViewerDataSource,
  WorkerPreviewResponse
} from "@/runtime-viewer/types";
import { configuredEntry, preview, worker } from "./fixtures";

const config: RuntimeViewerConfig = {
  mode: "api",
  apiBaseUrl: "/api"
};

describe("runtime viewer store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("loads only the active group, caches each group, and resamples only on refresh", async () => {
    const previewWorkers = vi.fn(
      async (workerGroupId: string): Promise<WorkerPreviewResponse> =>
        preview(workerGroupId, [worker(workerGroupId, `${workerGroupId}-worker`)])
    );
    const dataSource = source(previewWorkers);
    const store = createRuntimeViewerStore(config, dataSource);

    await store.initialize();
    expect(previewWorkers).not.toHaveBeenCalled();
    expect(store.configuredWorkerGroupIds).toEqual(["group-a", "group-b"]);
    expect(store.tasks).toHaveLength(2);

    await store.initializeWorkerView();
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

  it("waits for the in-flight resource directory before loading the worker sample", async () => {
    let resolveResources!: (value: ConfiguredRuntimeResourcesResponse) => void;
    const loadConfiguredResources = vi.fn(
      () =>
        new Promise<ConfiguredRuntimeResourcesResponse>((resolve) => {
          resolveResources = resolve;
        })
    );
    const previewWorkers = vi.fn(async (workerGroupId: string) =>
      preview(workerGroupId, [worker(workerGroupId, "worker-a")])
    );
    const store = createRuntimeViewerStore(config, {
      loadConfiguredResources,
      previewWorkers
    });

    const catalogLoad = store.initialize();
    const workerLoad = store.initializeWorkerView();
    expect(loadConfiguredResources).toHaveBeenCalledTimes(1);
    expect(previewWorkers).not.toHaveBeenCalled();

    resolveResources({ entries: [configuredEntry("group-a")] });
    await Promise.all([catalogLoad, workerLoad]);

    expect(previewWorkers).toHaveBeenCalledTimes(1);
    expect(previewWorkers.mock.calls[0]?.[0]).toBe("group-a");
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
});

function source(
  previewWorkers: RuntimeViewerDataSource["previewWorkers"],
  workerGroupIds = ["group-a", "group-b"]
): RuntimeViewerDataSource {
  return {
    loadConfiguredResources: vi.fn(async () => ({
      entries: workerGroupIds.map((workerGroupId) => configuredEntry(workerGroupId))
    })),
    previewWorkers
  };
}
