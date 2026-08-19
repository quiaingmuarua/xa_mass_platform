import type { WorkerView } from "@/runtime-viewer/types";

export type WorkerNetworkState = "connected" | "disconnected" | "unknown";

export type WorkerSchedulingState =
  | "hot-score-overdue"
  | "held-hot"
  | "paused"
  | "recovery"
  | "cold"
  | "missing";

export interface WorkerNetworkObservation {
  workerId: string;
  endpointManagerId: string;
  state: WorkerNetworkState;
  readAt: string;
}

export interface WorkerSchedulingObservation {
  workerId: string;
  workerGroupId: string;
  state: WorkerSchedulingState;
  readAt: string;
}

export interface WorkerStatusDataSource {
  observeNetwork(
    workers: WorkerView[],
    signal?: AbortSignal
  ): Promise<WorkerNetworkObservation[]>;

  observeScheduling(
    workerGroupId: string,
    workerIds: string[],
    signal?: AbortSignal
  ): Promise<WorkerSchedulingObservation[]>;
}

export type WorkerStatusLoadStatus = "idle" | "loading" | "ready" | "error";

export interface WorkerStatusAxis<T> {
  status: WorkerStatusLoadStatus;
  observation?: T;
  stale: boolean;
  error?: string;
}

export interface WorkerStatusEntry {
  workerId: string;
  workerGroupId: string;
  endpointManagerId: string;
  network: WorkerStatusAxis<WorkerNetworkObservation>;
  scheduling: WorkerStatusAxis<WorkerSchedulingObservation>;
}
