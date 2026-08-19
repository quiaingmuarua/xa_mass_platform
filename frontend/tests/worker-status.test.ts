import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AxiosInstance } from "axios";

import { createWorkerStatusStore } from "@/stores/worker-status";
import { ApiWorkerStatusDataSource } from "@/worker-status/api-data-source";
import { createWorkerStatusDataSource } from "@/worker-status/data-source";
import {
  MockWorkerStatusDataSource,
  networkState,
  schedulingState
} from "@/worker-status/mock-data-source";
import {
  presentNetworkState,
  presentSchedulingState,
  presentStatusAxis
} from "@/worker-status/presentation";
import type {
  WorkerNetworkObservation,
  WorkerSchedulingObservation,
  WorkerStatusDataSource
} from "@/worker-status/types";
import type { WorkerView } from "@/runtime-viewer/types";
import { worker } from "./fixtures";

describe("Mock Worker status data source", () => {
  it("is deterministic, preserves input order, and covers every mock state", async () => {
    const workers = Array.from({ length: 10 }, (_, index) =>
      worker("phone", `scenario-phone-number-worker-${index + 1}`)
    );
    const source = new MockWorkerStatusDataSource({
      networkLatencyMillis: 0,
      schedulingLatencyMillis: 0
    });

    const [network, scheduling] = await Promise.all([
      source.observeNetwork(workers),
      source.observeScheduling(
        "phone",
        workers.map((value) => value.workerId)
      )
    ]);

    expect(network.map((value) => value.workerId)).toEqual(
      workers.map((value) => value.workerId)
    );
    expect(scheduling.map((value) => value.workerId)).toEqual(
      workers.map((value) => value.workerId)
    );
    expect(new Set(network.map((value) => value.state))).toEqual(
      new Set(["connected", "disconnected", "unknown"])
    );
    expect(new Set(scheduling.map((value) => value.state))).toEqual(
      new Set([
        "hot-score-overdue",
        "held-hot",
        "paused",
        "recovery",
        "cold",
        "missing"
      ])
    );
    expect((await source.observeNetwork([workers[0]!]))[0]?.state).toBe(
      networkState(workers[0]!.workerId)
    );
    expect(
      (await source.observeScheduling("phone", [workers[0]!.workerId]))[0]?.state
    ).toBe(schedulingState(workers[0]!.workerId));
  });

  it("cancels an observation through AbortSignal", async () => {
    const source = new MockWorkerStatusDataSource({ networkLatencyMillis: 1_000 });
    const controller = new AbortController();
    const observation = source.observeNetwork(
      [worker("group-a", "worker-a")],
      controller.signal
    );

    controller.abort();

    await expect(observation).rejects.toMatchObject({ name: "AbortError" });
  });
});

describe("Worker status data source selection", () => {
  it("uses real Network and Scheduling APIs only in API mode", () => {
    expect(
      createWorkerStatusDataSource({ mode: "api", apiBaseUrl: "/api" })
    ).toBeInstanceOf(ApiWorkerStatusDataSource);
    expect(
      createWorkerStatusDataSource({ mode: "mock", apiBaseUrl: "/api" })
    ).toBeInstanceOf(MockWorkerStatusDataSource);
  });

  it("groups real Network reads by Adapter and restores Worker order", async () => {
    const post = vi.fn().mockImplementation(async (path: string) => {
      if (path.includes("adapter-a")) {
        return {
          data: {
            endpointManagerId: "adapter-a",
            readAt: "2026-08-19T12:00:00Z",
            statesByWorkerId: {
              "worker-2": "disconnected",
              "worker-1": "connected"
            }
          }
        };
      }
      return {
        data: {
          endpointManagerId: "adapter-b",
          readAt: "2026-08-19T12:00:01Z",
          statesByWorkerId: { "worker-3": "unknown" }
        }
      };
    });
    const source = new ApiWorkerStatusDataSource("/api", {
      post
    } as unknown as AxiosInstance);
    const workers = [
      { ...worker("group-a", "worker-3"), endpointManagerId: "adapter-b" },
      { ...worker("group-a", "worker-2"), endpointManagerId: "adapter-a" },
      { ...worker("group-a", "worker-1"), endpointManagerId: "adapter-a" }
    ];

    await expect(source.observeNetwork(workers)).resolves.toEqual([
      {
        workerId: "worker-3",
        endpointManagerId: "adapter-b",
        state: "unknown",
        readAt: "2026-08-19T12:00:01Z"
      },
      {
        workerId: "worker-2",
        endpointManagerId: "adapter-a",
        state: "disconnected",
        readAt: "2026-08-19T12:00:00Z"
      },
      {
        workerId: "worker-1",
        endpointManagerId: "adapter-a",
        state: "connected",
        readAt: "2026-08-19T12:00:00Z"
      }
    ]);
    expect(post).toHaveBeenCalledTimes(2);
    expect(post).toHaveBeenCalledWith(
      "/v1/runtime-view/endpoint-managers/adapter-b/workers:network-observe",
      { workerIds: ["worker-3"] },
      expect.objectContaining({ signal: undefined })
    );
    expect(post).toHaveBeenCalledWith(
      "/v1/runtime-view/endpoint-managers/adapter-a/workers:network-observe",
      { workerIds: ["worker-2", "worker-1"] },
      expect.objectContaining({ signal: undefined })
    );
  });

  it("rejects Adapter Network identity drift instead of inventing a state", async () => {
    const post = vi.fn().mockResolvedValue({
      data: {
        endpointManagerId: "adapter-a",
        readAt: "2026-08-19T12:00:00Z",
        statesByWorkerId: { "another-worker": "connected" }
      }
    });
    const source = new ApiWorkerStatusDataSource("/api", {
      post
    } as unknown as AxiosInstance);
    const current = {
      ...worker("group-a", "worker-1"),
      endpointManagerId: "adapter-a"
    };

    await expect(source.observeNetwork([current])).rejects.toThrow(
      "identities do not match"
    );
  });

  it("maps one bounded semantic scheduling response in request order", async () => {
    const post = vi.fn().mockResolvedValue({
      data: {
        workerGroupId: "group/a",
        readAt: "2026-08-19T12:00:00Z",
        statesByWorkerId: {
          "worker-2": "recovery",
          "worker-1": "hot-score-overdue"
        }
      }
    });
    const source = new ApiWorkerStatusDataSource("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(
      source.observeScheduling("group/a", ["worker-2", "worker-1"])
    ).resolves.toEqual([
      {
        workerId: "worker-2",
        workerGroupId: "group/a",
        state: "recovery",
        readAt: "2026-08-19T12:00:00Z"
      },
      {
        workerId: "worker-1",
        workerGroupId: "group/a",
        state: "hot-score-overdue",
        readAt: "2026-08-19T12:00:00Z"
      }
    ]);
    expect(post.mock.calls[0]?.[0]).toBe(
      "/v1/runtime-view/worker-groups/group%2Fa/workers:scheduling-observe"
    );
    expect(post.mock.calls[0]?.[1]).toEqual({
      workerIds: ["worker-2", "worker-1"]
    });
    expect(post.mock.calls[0]?.[2].headers["X-Request-Id"]).toEqual(expect.any(String));
  });

  it("rejects identity drift instead of inventing a scheduling state", async () => {
    const post = vi.fn().mockResolvedValue({
      data: {
        workerGroupId: "group-a",
        readAt: "2026-08-19T12:00:00Z",
        statesByWorkerId: { "another-worker": "hot-score-overdue" }
      }
    });
    const source = new ApiWorkerStatusDataSource("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(source.observeScheduling("group-a", ["worker-1"])).rejects.toThrow(
      "identities do not match"
    );
  });
});

describe("Worker status presentation", () => {
  it("keeps owner states distinct from unavailable observation lifecycle", () => {
    expect(presentNetworkState("connected").label).toBe("Connected");
    expect(presentNetworkState("connected").description).not.toMatch(/online/i);
    expect(presentSchedulingState("hot-score-overdue").description).toContain("不证明");
    expect(presentSchedulingState("held-hot").description).toContain("不证明");
    expect(presentSchedulingState("missing").label).toBe("Score Missing");
    expect(presentStatusAxis({ status: "error", stale: false }).label).toBe(
      "Unavailable"
    );
    expect(presentStatusAxis({ status: "error", stale: false }).label).not.toBe(
      presentNetworkState("disconnected").label
    );
    expect(presentStatusAxis({ status: "error", stale: false }).label).not.toBe(
      presentSchedulingState("missing").label
    );
  });
});

describe("Worker status store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("loads both axes once for a sample and reuses the group cache", async () => {
    const source = new ControllableStatusDataSource();
    const store = createWorkerStatusStore(source);
    const workers = [worker("group-a", "worker-a"), worker("group-a", "worker-b")];

    await store.ensureSample("group-a", workers);
    await store.ensureSample("group-a", workers);

    expect(source.observeNetwork).toHaveBeenCalledTimes(1);
    expect(source.observeScheduling).toHaveBeenCalledTimes(1);
    expect(store.status(workers[0]!).network.status).toBe("ready");
    expect(store.status(workers[0]!).scheduling.status).toBe("ready");

    await store.replaceSample("group-a", [workers[0]!]);
    expect(source.observeNetwork).toHaveBeenCalledTimes(2);
    expect(source.observeScheduling).toHaveBeenCalledTimes(2);
    expect(Object.values(store.entries)).toHaveLength(1);
  });

  it("keeps the last Network value stale when only that axis fails", async () => {
    const source = new ControllableStatusDataSource();
    const store = createWorkerStatusStore(source);
    const current = worker("group-a", "worker-a");
    await store.ensureSample("group-a", [current]);
    const oldSchedulingReadAt = store.status(current).scheduling.observation?.readAt;

    source.failNetwork = true;
    await store.refreshWorkers("group-a", [current]);

    expect(store.status(current).network).toMatchObject({
      status: "error",
      stale: true,
      observation: { state: "connected" }
    });
    expect(store.status(current).scheduling.status).toBe("ready");
    expect(store.status(current).scheduling.stale).toBe(false);
    expect(store.status(current).scheduling.observation?.readAt).not.toBe(
      oldSchedulingReadAt
    );
  });

  it("refreshes one Worker without changing its sample peers", async () => {
    const source = new ControllableStatusDataSource();
    const store = createWorkerStatusStore(source);
    const workers = [worker("group-a", "worker-a"), worker("group-a", "worker-b")];
    await store.ensureSample("group-a", workers);
    const peerReadAt = store.status(workers[1]!).network.observation?.readAt;

    await store.refreshWorker(workers[0]!);

    expect(source.observeNetwork).toHaveBeenLastCalledWith(
      [workers[0]],
      expect.any(AbortSignal)
    );
    expect(store.status(workers[1]!).network.observation?.readAt).toBe(peerReadAt);
  });

  it("does not let an old observation recreate a replaced sample", async () => {
    let resolveOldNetwork!: (observations: WorkerNetworkObservation[]) => void;
    let networkCall = 0;
    const source: WorkerStatusDataSource = {
      observeNetwork: vi.fn(async (workers: WorkerView[]) => {
        networkCall += 1;
        if (networkCall === 1) {
          return new Promise<WorkerNetworkObservation[]>((resolve) => {
            resolveOldNetwork = resolve;
          });
        }
        return networkObservations(workers, `network-${networkCall}`);
      }),
      observeScheduling: vi.fn(
        async (
          workerGroupId: string,
          workerIds: string[]
        ): Promise<WorkerSchedulingObservation[]> =>
          workerIds.map((workerId) => ({
            workerId,
            workerGroupId,
            state: "hot-score-overdue",
            readAt: "scheduling"
          }))
      )
    };
    const store = createWorkerStatusStore(source);
    const oldWorker = worker("group-a", "worker-old");
    const newWorker = worker("group-a", "worker-new");

    const oldLoad = store.ensureSample("group-a", [oldWorker]);
    await Promise.resolve();
    await store.replaceSample("group-a", [newWorker]);
    resolveOldNetwork(networkObservations([oldWorker], "network-old"));
    await oldLoad;

    expect(Object.values(store.entries).map((entry) => entry.workerId)).toEqual([
      "worker-new"
    ]);
  });
});

class ControllableStatusDataSource implements WorkerStatusDataSource {
  failNetwork = false;
  private sequence = 0;

  readonly observeNetwork = vi.fn(
    async (workers: WorkerView[]): Promise<WorkerNetworkObservation[]> => {
      this.sequence += 1;
      if (this.failNetwork) {
        throw new Error("network unavailable");
      }
      return workers.map((value) => ({
        workerId: value.workerId,
        endpointManagerId: value.endpointManagerId,
        state: "connected",
        readAt: `network-${this.sequence}`
      }));
    }
  );

  readonly observeScheduling = vi.fn(
    async (
      workerGroupId: string,
      workerIds: string[]
    ): Promise<WorkerSchedulingObservation[]> => {
      this.sequence += 1;
      return workerIds.map((workerId) => ({
        workerId,
        workerGroupId,
        state: "hot-score-overdue",
        readAt: `scheduling-${this.sequence}`
      }));
    }
  );
}

function networkObservations(
  workers: WorkerView[],
  readAt: string
): WorkerNetworkObservation[] {
  return workers.map((value) => ({
    workerId: value.workerId,
    endpointManagerId: value.endpointManagerId,
    state: "connected",
    readAt
  }));
}
