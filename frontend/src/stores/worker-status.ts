import { reactive } from "vue";
import { defineStore } from "pinia";

import type { WorkerView } from "@/runtime-viewer/types";
import type {
  WorkerNetworkObservation,
  WorkerSchedulingObservation,
  WorkerStatusAxis,
  WorkerStatusDataSource,
  WorkerStatusEntry
} from "@/worker-status/types";

interface StatusOperation {
  workerGroupId: string;
  controller: AbortController;
}

const STATUS_UNAVAILABLE = "Worker 状态观测暂时不可用。";

export function createWorkerStatusStore(dataSource: WorkerStatusDataSource) {
  return defineStore("workerStatus", () => {
    const entries = reactive<Record<string, WorkerStatusEntry>>({});
    const sampleSignatures = new Map<string, string>();
    const networkVersions = new Map<string, number>();
    const schedulingVersions = new Map<string, number>();
    const networkOperations = new Map<string, StatusOperation>();
    const schedulingOperations = new Map<string, StatusOperation>();

    function status(worker: WorkerView): WorkerStatusEntry {
      const key = statusKey(worker.workerGroupId, worker.workerId);
      const existing = entries[key];
      if (existing !== undefined) {
        return existing;
      }
      const created: WorkerStatusEntry = {
        workerId: worker.workerId,
        workerGroupId: worker.workerGroupId,
        endpointManagerId: worker.endpointManagerId,
        network: emptyAxis<WorkerNetworkObservation>(),
        scheduling: emptyAxis<WorkerSchedulingObservation>()
      };
      entries[key] = created;
      return created;
    }

    async function ensureSample(
      workerGroupId: string,
      workers: WorkerView[]
    ): Promise<void> {
      const signature = sampleSignature(workers);
      if (
        sampleSignatures.get(workerGroupId) === signature &&
        workers.every(
          (worker) => entries[statusKey(workerGroupId, worker.workerId)] !== undefined
        )
      ) {
        return;
      }
      await replaceSample(workerGroupId, workers);
    }

    async function replaceSample(
      workerGroupId: string,
      workers: WorkerView[]
    ): Promise<void> {
      clearGroup(workerGroupId);
      sampleSignatures.set(workerGroupId, sampleSignature(workers));
      workers.forEach(status);
      await observeWorkers(workerGroupId, workers);
    }

    async function refreshWorkers(
      workerGroupId: string,
      workers: WorkerView[]
    ): Promise<void> {
      workers.forEach(status);
      await observeWorkers(workerGroupId, workers);
    }

    async function refreshWorker(worker: WorkerView): Promise<void> {
      status(worker);
      await Promise.allSettled([
        observeNetwork(
          worker.workerGroupId,
          [worker],
          `worker:${statusKey(worker.workerGroupId, worker.workerId)}`
        ),
        observeScheduling(
          worker.workerGroupId,
          [worker],
          `worker:${statusKey(worker.workerGroupId, worker.workerId)}`
        )
      ]);
    }

    function isLoading(workers: WorkerView[]): boolean {
      return workers.some((worker) => {
        const value = status(worker);
        return (
          value.network.status === "loading" || value.scheduling.status === "loading"
        );
      });
    }

    function clearGroup(workerGroupId: string): void {
      abortGroup(networkOperations, workerGroupId);
      abortGroup(schedulingOperations, workerGroupId);
      Object.entries(entries).forEach(([key, value]) => {
        if (value.workerGroupId === workerGroupId) {
          bumpVersion(networkVersions, key);
          bumpVersion(schedulingVersions, key);
          delete entries[key];
        }
      });
      sampleSignatures.delete(workerGroupId);
    }

    function dispose(): void {
      abortAll(networkOperations);
      abortAll(schedulingOperations);
    }

    async function observeWorkers(
      workerGroupId: string,
      workers: WorkerView[]
    ): Promise<void> {
      if (workers.length === 0) {
        return;
      }
      await Promise.allSettled([
        observeNetwork(workerGroupId, workers, `batch:${workerGroupId}`),
        observeScheduling(workerGroupId, workers, `batch:${workerGroupId}`)
      ]);
    }

    async function observeNetwork(
      workerGroupId: string,
      workers: WorkerView[],
      operationKey: string
    ): Promise<void> {
      const controller = startOperation(networkOperations, operationKey, workerGroupId);
      const versions = new Map<string, number>();
      workers.forEach((worker) => {
        const key = statusKey(workerGroupId, worker.workerId);
        const version = bumpVersion(networkVersions, key);
        versions.set(key, version);
        beginLoad(status(worker).network);
      });

      try {
        const observations = await dataSource.observeNetwork(
          workers,
          controller.signal
        );
        requireNetworkMatch(workers, observations);
        observations.forEach((observation, index) => {
          const worker = workers[index]!;
          const key = statusKey(workerGroupId, worker.workerId);
          if (networkVersions.get(key) !== versions.get(key)) {
            return;
          }
          completeLoad(status(worker).network, observation);
        });
      } catch (error) {
        if (isCancellation(error)) {
          return;
        }
        workers.forEach((worker) => {
          const key = statusKey(workerGroupId, worker.workerId);
          if (networkVersions.get(key) === versions.get(key)) {
            failLoad(status(worker).network);
          }
        });
      } finally {
        finishOperation(networkOperations, operationKey, controller);
      }
    }

    async function observeScheduling(
      workerGroupId: string,
      workers: WorkerView[],
      operationKey: string
    ): Promise<void> {
      const controller = startOperation(
        schedulingOperations,
        operationKey,
        workerGroupId
      );
      const versions = new Map<string, number>();
      workers.forEach((worker) => {
        const key = statusKey(workerGroupId, worker.workerId);
        const version = bumpVersion(schedulingVersions, key);
        versions.set(key, version);
        beginLoad(status(worker).scheduling);
      });

      try {
        const observations = await dataSource.observeScheduling(
          workerGroupId,
          workers.map((worker) => worker.workerId),
          controller.signal
        );
        requireSchedulingMatch(workerGroupId, workers, observations);
        observations.forEach((observation, index) => {
          const worker = workers[index]!;
          const key = statusKey(workerGroupId, worker.workerId);
          if (schedulingVersions.get(key) !== versions.get(key)) {
            return;
          }
          completeLoad(status(worker).scheduling, observation);
        });
      } catch (error) {
        if (isCancellation(error)) {
          return;
        }
        workers.forEach((worker) => {
          const key = statusKey(workerGroupId, worker.workerId);
          if (schedulingVersions.get(key) === versions.get(key)) {
            failLoad(status(worker).scheduling);
          }
        });
      } finally {
        finishOperation(schedulingOperations, operationKey, controller);
      }
    }

    return {
      entries,
      status,
      ensureSample,
      replaceSample,
      refreshWorkers,
      refreshWorker,
      isLoading,
      clearGroup,
      dispose
    };
  })();
}

export type WorkerStatusStore = ReturnType<typeof createWorkerStatusStore>;

function emptyAxis<T>(): WorkerStatusAxis<T> {
  return {
    status: "idle",
    stale: false
  };
}

function beginLoad<T>(axis: WorkerStatusAxis<T>): void {
  axis.status = "loading";
  axis.error = undefined;
}

function completeLoad<T>(axis: WorkerStatusAxis<T>, observation: T): void {
  axis.status = "ready";
  axis.observation = observation;
  axis.stale = false;
  axis.error = undefined;
}

function failLoad<T>(axis: WorkerStatusAxis<T>): void {
  axis.status = "error";
  axis.stale = axis.observation !== undefined;
  axis.error = STATUS_UNAVAILABLE;
}

function statusKey(workerGroupId: string, workerId: string): string {
  return `${workerGroupId}\u0000${workerId}`;
}

function sampleSignature(workers: WorkerView[]): string {
  return workers
    .map(
      (worker) =>
        `${worker.workerGroupId}\u0000${worker.workerId}\u0000${worker.endpointManagerId}`
    )
    .join("\u0001");
}

function bumpVersion(versions: Map<string, number>, key: string): number {
  const version = (versions.get(key) ?? 0) + 1;
  versions.set(key, version);
  return version;
}

function startOperation(
  operations: Map<string, StatusOperation>,
  operationKey: string,
  workerGroupId: string
): AbortController {
  operations.get(operationKey)?.controller.abort();
  const controller = new AbortController();
  operations.set(operationKey, { workerGroupId, controller });
  return controller;
}

function finishOperation(
  operations: Map<string, StatusOperation>,
  operationKey: string,
  controller: AbortController
): void {
  if (operations.get(operationKey)?.controller === controller) {
    operations.delete(operationKey);
  }
}

function abortGroup(
  operations: Map<string, StatusOperation>,
  workerGroupId: string
): void {
  operations.forEach((operation, key) => {
    if (operation.workerGroupId === workerGroupId) {
      operation.controller.abort();
      operations.delete(key);
    }
  });
}

function abortAll(operations: Map<string, StatusOperation>): void {
  operations.forEach((operation) => operation.controller.abort());
  operations.clear();
}

function isCancellation(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

function requireNetworkMatch(
  workers: WorkerView[],
  observations: WorkerNetworkObservation[]
): void {
  if (
    observations.length !== workers.length ||
    observations.some(
      (observation, index) =>
        observation.workerId !== workers[index]?.workerId ||
        observation.endpointManagerId !== workers[index]?.endpointManagerId
    )
  ) {
    throw new Error("Worker Network observation does not match the request");
  }
}

function requireSchedulingMatch(
  workerGroupId: string,
  workers: WorkerView[],
  observations: WorkerSchedulingObservation[]
): void {
  if (
    observations.length !== workers.length ||
    observations.some(
      (observation, index) =>
        observation.workerId !== workers[index]?.workerId ||
        observation.workerGroupId !== workerGroupId
    )
  ) {
    throw new Error("Worker Scheduling observation does not match the request");
  }
}
