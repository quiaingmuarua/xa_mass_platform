import { inject, type InjectionKey } from "vue";

import type { RuntimeViewerConfig } from "@/runtime-viewer/types";
import type { RuntimeViewerStore } from "@/stores/runtime-viewer";
import type { TaskBatchStore } from "@/stores/task-batch";
import type { WorkerStatusStore } from "@/stores/worker-status";

export const runtimeViewerConfigKey: InjectionKey<RuntimeViewerConfig> =
  Symbol("runtimeViewerConfig");
export const runtimeViewerStoreKey: InjectionKey<RuntimeViewerStore> =
  Symbol("runtimeViewerStore");
export const taskBatchStoreKey: InjectionKey<TaskBatchStore> = Symbol("taskBatchStore");
export const workerStatusStoreKey: InjectionKey<WorkerStatusStore> =
  Symbol("workerStatusStore");

export function useRuntimeViewerConfig(): RuntimeViewerConfig {
  const config = inject(runtimeViewerConfigKey);
  if (config === undefined) {
    throw new Error("Runtime Viewer config was not provided");
  }
  return config;
}

export function useRuntimeViewerStore(): RuntimeViewerStore {
  const store = inject(runtimeViewerStoreKey);
  if (store === undefined) {
    throw new Error("Runtime Viewer store was not provided");
  }
  return store;
}

export function useTaskBatchStore(): TaskBatchStore {
  const store = inject(taskBatchStoreKey);
  if (store === undefined) {
    throw new Error("Task Batch store was not provided");
  }
  return store;
}

export function useWorkerStatusStore(): WorkerStatusStore {
  const store = inject(workerStatusStoreKey);
  if (store === undefined) {
    throw new Error("Worker Status store was not provided");
  }
  return store;
}
