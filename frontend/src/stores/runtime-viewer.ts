import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  isRuntimeViewerCancellation,
  presentRuntimeViewerError,
  type RuntimeViewerErrorPresentation
} from "@/runtime-viewer/errors";
import type {
  RuntimeViewerConfig,
  RuntimeViewerDataSource,
  TaskPreviewResponse,
  WorkerGroupPreviewResponse,
  WorkerPreviewResponse
} from "@/runtime-viewer/types";

export type SampleLoadStatus = "idle" | "loading" | "refreshing" | "ready" | "error";

export interface WorkerGroupSampleState {
  status: SampleLoadStatus;
  sample?: WorkerPreviewResponse;
  stale: boolean;
  error?: RuntimeViewerErrorPresentation;
}

export interface WorkerGroupPreviewState {
  status: SampleLoadStatus;
  preview?: WorkerGroupPreviewResponse;
  stale: boolean;
  error?: RuntimeViewerErrorPresentation;
}

export interface TaskPreviewState {
  status: SampleLoadStatus;
  preview?: TaskPreviewResponse;
  stale: boolean;
  error?: RuntimeViewerErrorPresentation;
}

export function createRuntimeViewerStore(
  config: RuntimeViewerConfig,
  dataSource: RuntimeViewerDataSource
) {
  return defineStore("runtimeViewer", () => {
    const taskPreviewState = reactive<TaskPreviewState>({
      status: "idle",
      stale: false
    });
    const workerGroupPreviewState = reactive<WorkerGroupPreviewState>({
      status: "idle",
      stale: false
    });
    const activeWorkerGroupId = ref<string>();
    const samples = reactive<Record<string, WorkerGroupSampleState>>({});

    let taskPreviewController: AbortController | undefined;
    let taskPreviewPromise: Promise<void> | undefined;
    let taskPreviewVersion = 0;
    let workerGroupLoadController: AbortController | undefined;
    let workerGroupLoadPromise: Promise<void> | undefined;
    let workerGroupLoadVersion = 0;
    const sampleControllers = new Map<string, AbortController>();
    const sampleVersions = new Map<string, number>();

    const workerGroups = computed(
      () => workerGroupPreviewState.preview?.workerGroups ?? []
    );
    const workerGroupIds = computed(() =>
      workerGroups.value.map((group) => group.workerGroupId)
    );
    const entries = computed(() => taskPreviewState.preview?.entries ?? []);
    const tasks = computed(() =>
      entries.value.flatMap((entry) => (entry.task === null ? [] : [entry.task]))
    );
    const missingWorkerGroupIds = computed(() =>
      entries.value
        .filter((entry) => entry.task !== null && entry.workerGroup === null)
        .map((entry) => entry.task!.workerGroupId)
    );
    const missingTaskIds = computed(() =>
      entries.value.filter((entry) => entry.task === null).map((entry) => entry.taskId)
    );
    const groupById = computed(
      () => new Map(workerGroups.value.map((group) => [group.workerGroupId, group]))
    );
    const activeGroup = computed(() =>
      activeWorkerGroupId.value
        ? groupById.value.get(activeWorkerGroupId.value)
        : undefined
    );
    const activeSampleState = computed(() =>
      activeWorkerGroupId.value ? samples[activeWorkerGroupId.value] : undefined
    );
    const activeSample = computed(() => activeSampleState.value?.sample);

    async function initializeTaskView(): Promise<void> {
      await loadTaskPreview(false);
    }

    async function initializeWorkerGroups(): Promise<void> {
      await loadWorkerGroupDirectory(false);
    }

    async function refreshTasks(): Promise<void> {
      await loadTaskPreview(true);
    }

    function loadTaskPreview(force: boolean): Promise<void> {
      if (!force && taskPreviewState.status === "ready" && !taskPreviewState.stale) {
        return Promise.resolve();
      }
      if (!force && taskPreviewPromise !== undefined) {
        return taskPreviewPromise;
      }

      taskPreviewController?.abort();
      const controller = new AbortController();
      taskPreviewController = controller;
      const version = ++taskPreviewVersion;
      const previousPreview = taskPreviewState.preview;
      taskPreviewState.status =
        previousPreview === undefined ? "loading" : "refreshing";
      taskPreviewState.error = undefined;

      const request = (async () => {
        try {
          const response = await dataSource.previewTasks(100, controller.signal);
          if (taskPreviewVersion !== version) {
            return;
          }
          taskPreviewState.preview = response;
          taskPreviewState.status = "ready";
          taskPreviewState.error = undefined;
          taskPreviewState.stale = false;
        } catch (error) {
          if (isRuntimeViewerCancellation(error) || taskPreviewVersion !== version) {
            return;
          }
          taskPreviewState.preview = previousPreview;
          taskPreviewState.status = "error";
          taskPreviewState.error = presentRuntimeViewerError(error);
          taskPreviewState.stale = previousPreview !== undefined;
        } finally {
          if (taskPreviewVersion === version) {
            taskPreviewController = undefined;
            taskPreviewPromise = undefined;
          }
        }
      })();
      taskPreviewPromise = request;
      return request;
    }

    async function initializeWorkerView(): Promise<void> {
      await loadWorkerGroupDirectory(false);
      if (activeWorkerGroupId.value !== undefined) {
        await loadSample(activeWorkerGroupId.value, false);
      }
    }

    async function refreshWorkerGroups(): Promise<void> {
      await loadWorkerGroupDirectory(true);
      if (
        workerGroupPreviewState.status === "ready" &&
        activeWorkerGroupId.value !== undefined &&
        samples[activeWorkerGroupId.value]?.sample === undefined
      ) {
        await loadSample(activeWorkerGroupId.value, false);
      }
    }

    function loadWorkerGroupDirectory(force: boolean): Promise<void> {
      if (!force && workerGroupPreviewState.status === "ready") {
        return Promise.resolve();
      }
      if (!force && workerGroupLoadPromise !== undefined) {
        return workerGroupLoadPromise;
      }

      workerGroupLoadController?.abort();
      const controller = new AbortController();
      workerGroupLoadController = controller;
      const version = ++workerGroupLoadVersion;
      const previousPreview = workerGroupPreviewState.preview;
      workerGroupPreviewState.status =
        previousPreview === undefined ? "loading" : "refreshing";
      workerGroupPreviewState.error = undefined;

      const request = (async () => {
        try {
          const response = await dataSource.previewWorkerGroups(100, controller.signal);
          if (workerGroupLoadVersion !== version) {
            return;
          }
          const sortedGroups = [...response.workerGroups].sort((left, right) =>
            left.workerGroupId.localeCompare(right.workerGroupId)
          );
          const nextPreview = {
            ...response,
            workerGroups: sortedGroups
          };
          const nextIds = new Set(sortedGroups.map((group) => group.workerGroupId));

          Object.keys(samples).forEach((workerGroupId) => {
            if (!nextIds.has(workerGroupId)) {
              sampleControllers.get(workerGroupId)?.abort();
              sampleControllers.delete(workerGroupId);
              sampleVersions.delete(workerGroupId);
              delete samples[workerGroupId];
            }
          });
          sortedGroups.forEach((group) => {
            samples[group.workerGroupId] ??= {
              status: "idle",
              stale: false
            };
          });

          workerGroupPreviewState.preview = nextPreview;
          workerGroupPreviewState.status = "ready";
          workerGroupPreviewState.stale = false;
          workerGroupPreviewState.error = undefined;
          if (
            activeWorkerGroupId.value === undefined ||
            !nextIds.has(activeWorkerGroupId.value)
          ) {
            activeWorkerGroupId.value = sortedGroups[0]?.workerGroupId;
          }
        } catch (error) {
          if (
            isRuntimeViewerCancellation(error) ||
            workerGroupLoadVersion !== version
          ) {
            return;
          }
          workerGroupPreviewState.preview = previousPreview;
          workerGroupPreviewState.status = "error";
          workerGroupPreviewState.stale = previousPreview !== undefined;
          workerGroupPreviewState.error = presentRuntimeViewerError(error);
        } finally {
          if (workerGroupLoadVersion === version) {
            workerGroupLoadController = undefined;
            workerGroupLoadPromise = undefined;
          }
        }
      })();
      workerGroupLoadPromise = request;
      return request;
    }

    async function selectGroup(workerGroupId: string): Promise<void> {
      if (!groupById.value.has(workerGroupId)) {
        return;
      }
      activeWorkerGroupId.value = workerGroupId;
      const state = samples[workerGroupId];
      if (
        groupById.value.has(workerGroupId) &&
        state !== undefined &&
        state.status === "idle" &&
        state.sample === undefined
      ) {
        await loadSample(workerGroupId, false);
      }
    }

    async function refreshActiveGroup(): Promise<void> {
      const workerGroupId = activeWorkerGroupId.value;
      if (workerGroupId === undefined) {
        return;
      }
      await loadSample(workerGroupId, true);
    }

    async function loadSample(workerGroupId: string, force: boolean): Promise<void> {
      const state = samples[workerGroupId];
      if (state === undefined || !groupById.value.has(workerGroupId)) {
        return;
      }
      if (!force && state.sample !== undefined) {
        return;
      }
      if (!force && (state.status === "loading" || state.status === "refreshing")) {
        return;
      }

      sampleControllers.get(workerGroupId)?.abort();
      const controller = new AbortController();
      sampleControllers.set(workerGroupId, controller);
      const version = (sampleVersions.get(workerGroupId) ?? 0) + 1;
      sampleVersions.set(workerGroupId, version);
      const previousSample = state.sample;

      state.status = previousSample === undefined ? "loading" : "refreshing";
      state.error = undefined;

      try {
        const nextSample = await dataSource.previewWorkers(
          workerGroupId,
          100,
          controller.signal
        );
        if (sampleVersions.get(workerGroupId) !== version) {
          return;
        }
        state.sample = nextSample;
        state.status = "ready";
        state.stale = false;
        state.error = undefined;
      } catch (error) {
        if (
          isRuntimeViewerCancellation(error) ||
          sampleVersions.get(workerGroupId) !== version
        ) {
          return;
        }
        state.sample = previousSample;
        state.status = "error";
        state.stale = previousSample !== undefined;
        state.error = presentRuntimeViewerError(error);
      } finally {
        if (sampleVersions.get(workerGroupId) === version) {
          sampleControllers.delete(workerGroupId);
        }
      }
    }

    function dispose(): void {
      taskPreviewController?.abort();
      workerGroupLoadController?.abort();
      sampleControllers.forEach((controller) => controller.abort());
      sampleControllers.clear();
    }

    return {
      mode: config.mode,
      taskPreviewState,
      entries,
      workerGroupPreviewState,
      workerGroups,
      workerGroupIds,
      tasks,
      missingWorkerGroupIds,
      missingTaskIds,
      activeWorkerGroupId,
      activeGroup,
      activeSampleState,
      activeSample,
      samples,
      initializeTaskView,
      initializeWorkerGroups,
      refreshTasks,
      initializeWorkerView,
      refreshWorkerGroups,
      selectGroup,
      refreshActiveGroup,
      dispose
    };
  })();
}

export type RuntimeViewerStore = ReturnType<typeof createRuntimeViewerStore>;
