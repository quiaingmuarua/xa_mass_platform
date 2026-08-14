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

export function createRuntimeViewerStore(
  config: RuntimeViewerConfig,
  dataSource: RuntimeViewerDataSource
) {
  return defineStore("runtimeViewer", () => {
    const resourceLoadStatus = ref<ResourceLoadStatus>("idle");
    const resourceLoadError = ref<RuntimeViewerErrorPresentation>();
    const entries = ref<ConfiguredRuntimeResourceEntry[]>([]);
    const activeWorkerGroupId = ref<string>();
    const samples = reactive<Record<string, WorkerGroupSampleState>>({});

    let resourceLoadController: AbortController | undefined;
    let resourceLoadPromise: Promise<void> | undefined;
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
      () => new Map(groups.value.map((group) => [group.workerGroupId, group]))
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
        for (const entry of response.entries) {
          samples[entry.workerGroupId] ??= {
            status: "idle",
            stale: false
          };
        }
        const configured = new Set(configuredWorkerGroupIds.value);
        if (
          activeWorkerGroupId.value === undefined ||
          !configured.has(activeWorkerGroupId.value) ||
          !groupById.value.has(activeWorkerGroupId.value)
        ) {
          activeWorkerGroupId.value = response.entries[0]?.workerGroupId;
        }
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
      await initialize();
      if (
        resourceLoadStatus.value === "ready" &&
        activeWorkerGroupId.value !== undefined
      ) {
        await loadSample(activeWorkerGroupId.value, false);
      }
    }

    async function selectGroup(workerGroupId: string): Promise<void> {
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
      selectGroup,
      refreshActiveGroup,
      dispose
    };
  })();
}

export type RuntimeViewerStore = ReturnType<typeof createRuntimeViewerStore>;
