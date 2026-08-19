import type { WorkerView } from "@/runtime-viewer/types";

import type {
  WorkerNetworkObservation,
  WorkerNetworkState,
  WorkerSchedulingObservation,
  WorkerSchedulingState,
  WorkerStatusDataSource
} from "./types";

const NETWORK_PATTERN: readonly WorkerNetworkState[] = [
  "connected",
  "connected",
  "disconnected",
  "unknown"
];

const SCHEDULING_PATTERN: readonly WorkerSchedulingState[] = [
  "hot-score-overdue",
  "hot-score-overdue",
  "held-hot",
  "paused",
  "recovery",
  "cold",
  "missing"
];

export interface MockWorkerStatusOptions {
  networkLatencyMillis?: number;
  schedulingLatencyMillis?: number;
}

export class MockWorkerStatusDataSource implements WorkerStatusDataSource {
  private readonly networkLatencyMillis: number;
  private readonly schedulingLatencyMillis: number;

  constructor(options: MockWorkerStatusOptions = {}) {
    this.networkLatencyMillis = options.networkLatencyMillis ?? 320;
    this.schedulingLatencyMillis = options.schedulingLatencyMillis ?? 520;
  }

  async observeNetwork(
    workers: WorkerView[],
    signal?: AbortSignal
  ): Promise<WorkerNetworkObservation[]> {
    await delay(this.networkLatencyMillis, signal);
    const readAt = new Date().toISOString();
    return workers.map((worker) => ({
      workerId: worker.workerId,
      endpointManagerId: worker.endpointManagerId,
      state: networkState(worker.workerId),
      readAt
    }));
  }

  async observeScheduling(
    workerGroupId: string,
    workerIds: string[],
    signal?: AbortSignal
  ): Promise<WorkerSchedulingObservation[]> {
    await delay(this.schedulingLatencyMillis, signal);
    const readAt = new Date().toISOString();
    return workerIds.map((workerId) => ({
      workerId,
      workerGroupId,
      state: schedulingState(workerId),
      readAt
    }));
  }
}

export function networkState(workerId: string): WorkerNetworkState {
  const ordinal = stableOrdinal(workerId);
  return NETWORK_PATTERN[(ordinal * 3 + 1) % NETWORK_PATTERN.length]!;
}

export function schedulingState(workerId: string): WorkerSchedulingState {
  const ordinal = stableOrdinal(workerId);
  return SCHEDULING_PATTERN[(ordinal * 5 + 2) % SCHEDULING_PATTERN.length]!;
}

function stableOrdinal(workerId: string): number {
  const suffix = /(?:^|\D)(\d+)$/.exec(workerId)?.[1];
  if (suffix !== undefined) {
    return Math.max(0, Number.parseInt(suffix, 10) - 1);
  }

  let hash = 0;
  for (let index = 0; index < workerId.length; index += 1) {
    hash = (hash * 31 + workerId.charCodeAt(index)) >>> 0;
  }
  return hash;
}

function delay(durationMillis: number, signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) {
    return Promise.reject(abortError());
  }
  if (durationMillis <= 0) {
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    const timer = globalThis.setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, durationMillis);
    const onAbort = (): void => {
      globalThis.clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      reject(abortError());
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function abortError(): Error {
  const error = new Error("Worker status observation was cancelled");
  error.name = "AbortError";
  return error;
}
