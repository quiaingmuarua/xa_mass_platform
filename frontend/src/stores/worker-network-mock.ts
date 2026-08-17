import { reactive, ref } from "vue";
import { defineStore } from "pinia";

import type { WorkerView } from "@/runtime-viewer/types";

export type WorkerNetworkStatus = "not-observed" | "connected" | "disconnected";
export type WorkerNetworkCloseOutcome = "close-started" | "not-connected";

export interface WorkerNetworkMockObservation {
  workerId: string;
  workerGroupId: string;
  endpointManagerId: string;
  status: WorkerNetworkStatus;
  observedAt?: string;
  stale: boolean;
  loading: boolean;
  lastCloseOutcome?: WorkerNetworkCloseOutcome;
}

const MOCK_LATENCY_MILLIS = 420;

export const useWorkerNetworkMockStore = defineStore("workerNetworkMock", () => {
  const observations = reactive<Record<string, WorkerNetworkMockObservation>>({});
  const batchLoading = ref(false);

  function observation(worker: WorkerView): WorkerNetworkMockObservation {
    const key = observationKey(worker);
    const existing = observations[key];
    if (existing !== undefined) {
      return existing;
    }
    const created: WorkerNetworkMockObservation = {
      workerId: worker.workerId,
      workerGroupId: worker.workerGroupId,
      endpointManagerId: worker.endpointManagerId,
      status: "not-observed",
      stale: false,
      loading: false
    };
    observations[key] = created;
    return created;
  }

  async function observeWorkers(workers: WorkerView[]): Promise<void> {
    if (workers.length === 0 || batchLoading.value) {
      return;
    }
    batchLoading.value = true;
    workers.forEach((worker) => {
      observation(worker).loading = true;
    });
    await delay(MOCK_LATENCY_MILLIS);
    const observedAt = new Date().toISOString();
    workers.forEach((worker) => {
      const value = observation(worker);
      value.status = mockConnected(worker.workerId) ? "connected" : "disconnected";
      value.observedAt = observedAt;
      value.stale = false;
      value.loading = false;
      value.lastCloseOutcome = undefined;
    });
    batchLoading.value = false;
  }

  async function observeWorker(worker: WorkerView): Promise<void> {
    const value = observation(worker);
    if (value.loading) {
      return;
    }
    value.loading = true;
    await delay(MOCK_LATENCY_MILLIS);
    value.status = mockConnected(worker.workerId) ? "connected" : "disconnected";
    value.observedAt = new Date().toISOString();
    value.stale = false;
    value.loading = false;
    value.lastCloseOutcome = undefined;
  }

  async function closeCurrent(worker: WorkerView): Promise<WorkerNetworkCloseOutcome> {
    const value = observation(worker);
    if (value.loading) {
      return value.lastCloseOutcome ?? "not-connected";
    }
    value.loading = true;
    await delay(MOCK_LATENCY_MILLIS);
    const outcome: WorkerNetworkCloseOutcome =
      value.status === "connected" ? "close-started" : "not-connected";
    value.loading = false;
    value.stale = true;
    value.lastCloseOutcome = outcome;
    return outcome;
  }

  function clearGroup(workerGroupId: string): void {
    Object.entries(observations).forEach(([key, value]) => {
      if (value.workerGroupId === workerGroupId) {
        delete observations[key];
      }
    });
  }

  return {
    observations,
    batchLoading,
    observation,
    observeWorkers,
    observeWorker,
    closeCurrent,
    clearGroup
  };
});

function observationKey(worker: WorkerView): string {
  return `${worker.endpointManagerId}\u0000${worker.workerId}`;
}

function mockConnected(workerId: string): boolean {
  let checksum = 0;
  for (let index = 0; index < workerId.length; index += 1) {
    checksum = (checksum + workerId.charCodeAt(index) * (index + 1)) % 97;
  }
  return checksum % 4 !== 0;
}

function delay(durationMillis: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, durationMillis));
}
