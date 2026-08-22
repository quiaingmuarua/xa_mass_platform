import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  isRuntimeViewerCancellation,
  presentRuntimeViewerError,
  type RuntimeViewerErrorPresentation
} from "@/runtime-viewer/errors";
import type {
  ConfiguredRuntimeResourceEntry,
  RuntimeViewerConfig,
  RuntimeViewerDataSource,
  WorkerGroupPreviewResponse,
  WorkerPreviewResponse
} from "@/runtime-viewer/types";

export type SampleLoadStatus = "idle" | "loading" | "refreshing" | "ready" | "error";
export type ResourceLoadStatus = "idle" | "loading" | "ready" | "error";

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

export function createRuntimeViewerStore(
  config: RuntimeViewerConfig,
  dataSource: RuntimeViewerDataSource
) {
  return defineStore("runtimeViewer", () => {
    const resourceLoadStatus = ref<ResourceLoadStatus>("idle");
    const resourceLoadError = ref<RuntimeViewerErrorPresentation>();
    const entries = ref<ConfiguredRuntimeResourceEntry[]>([]);
    const workerGroupPreviewState = reactive<WorkerGroupPreviewState>({
      status: "idle",
      stale: false
    });
    const activeWorkerGroupId = ref<string>();
    const samples = reactive<Record<string, WorkerGroupSampleState>>({});

    let resourceLoadController: AbortController | undefined;
    let resourceLoadPromise: Promise<void> | undefined;
    let workerGroupLoadController: AbortController | undefined;
    let workerGroupLoadPromise: Promise<void> | undefined;
    let workerGroupLoadVersion = 0;
    const sampleControllers = new Map<string, AbortController>();
    const sampleVersions = new Map<string, number>();

    const configuredWorkerGroupIds = computed(() =>
      entries.value.map((entry) => entry.workerGroupId)
    );
    const groups = computed(() =>
      entries.value.flatMap((entry) =>
        entry.workerGroup === null ? [] : [entry.workerGroup]
      )
    );
    const workerGroups = computed(
      () => workerGroupPreviewState.preview?.workerGroups ?? []
    );
    const workerGroupIds = computed(() =>
      workerGroups.value.map((group) => group.workerGroupId)
    );
    const tasks = computed(() =>
      entries.value.flatMap((entry) => (entry.task === null ? [] : [entry.task]))
    );
    const missingWorkerGroupIds = computed(() =>
      entries.value
        .filter((entry) => entry.workerGroup === null)
        .map((entry) => entry.workerGroupId)
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

    function initialize(): Promise<void> {
      if (resourceLoadStatus.value === "ready") {
        return Promise.resolve();
      }
      if (resourceLoadPromise !== undefined) {
        return resourceLoadPromise;
      }
      resourceLoadPromise = loadResourceDirectory();
      return resourceLoadPromise;
    }

    async function loadResourceDirectory(): Promise<void> {
      resourceLoadController?.abort();
      resourceLoadController = new AbortController();
      resourceLoadStatus.value = "loading";
      resourceLoadError.value = undefined;
      try {
        const response = await dataSource.loadConfiguredResources(
          resourceLoadController.signal
        );
        entries.value = response.entries;
        resourceLoadStatus.value = "ready";
      } catch (error) {
        if (isRuntimeViewerCancellation(error)) {
          return;
        }
        resourceLoadStatus.value = "error";
        resourceLoadError.value = presentRuntimeViewerError(error);
      } finally {
        resourceLoadController = undefined;
        resourceLoadPromise = undefined;
      }
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
          null,
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
      resourceLoadController?.abort();
      workerGroupLoadController?.abort();
      sampleControllers.forEach((controller) => controller.abort());
      sampleControllers.clear();
    }

    return {
      mode: config.mode,
      resourceLoadStatus,
      resourceLoadError,
      entries,
      configuredWorkerGroupIds,
      groups,
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
      initialize,
      initializeWorkerView,
      refreshWorkerGroups,
      selectGroup,
      refreshActiveGroup,
      dispose
    };
  })();
}

export type RuntimeViewerStore = ReturnType<typeof createRuntimeViewerStore>;
