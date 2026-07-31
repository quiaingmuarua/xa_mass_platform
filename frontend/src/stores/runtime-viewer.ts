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
  WorkerGroupView,
  WorkerPreviewResponse
} from "@/runtime-viewer/types";

export type SampleLoadStatus = "idle" | "loading" | "refreshing" | "ready" | "error";

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
    const groupLoadStatus = ref<"idle" | "loading" | "ready" | "error">("idle");
    const groupLoadError = ref<RuntimeViewerErrorPresentation>();
    const groups = ref<WorkerGroupView[]>([]);
    const missingWorkerGroupIds = ref<string[]>([]);
    const activeWorkerGroupId = ref<string>();
    const samples = reactive<Record<string, WorkerGroupSampleState>>({});

    let groupLoadController: AbortController | undefined;
    const sampleControllers = new Map<string, AbortController>();
    const sampleVersions = new Map<string, number>();

    for (const workerGroupId of config.workerGroupIds) {
      samples[workerGroupId] = {
        status: "idle",
        stale: false
      };
    }

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

    async function initialize(): Promise<void> {
      if (groupLoadStatus.value === "loading" || groupLoadStatus.value === "ready") {
        return;
      }

      groupLoadController?.abort();
      groupLoadController = new AbortController();
      groupLoadStatus.value = "loading";
      groupLoadError.value = undefined;
      try {
        const response = await dataSource.loadWorkerGroups(
          config.workerGroupIds,
          groupLoadController.signal
        );
        groups.value = response.workerGroups;
        missingWorkerGroupIds.value = response.missingWorkerGroupIds;
        groupLoadStatus.value = "ready";

        const firstAvailable = config.workerGroupIds.find((workerGroupId) =>
          groupById.value.has(workerGroupId)
        );
        activeWorkerGroupId.value = activeWorkerGroupId.value ?? firstAvailable;
        if (activeWorkerGroupId.value !== undefined) {
          await loadSample(activeWorkerGroupId.value, false);
        }
      } catch (error) {
        if (isRuntimeViewerCancellation(error)) {
          return;
        }
        groupLoadStatus.value = "error";
        groupLoadError.value = presentRuntimeViewerError(error);
      } finally {
        groupLoadController = undefined;
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
      groupLoadController?.abort();
      sampleControllers.forEach((controller) => controller.abort());
      sampleControllers.clear();
    }

    return {
      mode: config.mode,
      configuredWorkerGroupIds: [...config.workerGroupIds],
      groupLoadStatus,
      groupLoadError,
      groups,
      missingWorkerGroupIds,
      activeWorkerGroupId,
      activeGroup,
      activeSampleState,
      activeSample,
      samples,
      initialize,
      selectGroup,
      refreshActiveGroup,
      dispose
    };
  })();
}

export type RuntimeViewerStore = ReturnType<typeof createRuntimeViewerStore>;
