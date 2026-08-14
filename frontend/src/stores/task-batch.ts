import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  presentTaskBatchError,
  taskBatchConfigurationError,
  type TaskBatchErrorPresentation
} from "@/task-batch/errors";
import type {
  TaskBatchClient,
  TaskBatchExecutionRequest,
  TaskBatchRunRecord
} from "@/task-batch/types";
import type { RuntimeViewerConfig } from "@/runtime-viewer/types";
import type { RuntimeViewerStore } from "@/stores/runtime-viewer";

export type TaskBatchExecutionPhase = "idle" | "uploading" | "running";

const MAX_INPUT_BYTES = 1024 * 1024;
const MAXIMUM_WAIT_MILLIS = 300_000;

export function createTaskBatchStore(
  config: RuntimeViewerConfig,
  client: TaskBatchClient,
  runtimeStore: RuntimeViewerStore
) {
  return defineStore("taskBatch", () => {
    const executionPhase = ref<TaskBatchExecutionPhase>("idle");
    const runs = ref<TaskBatchRunRecord[]>([]);
    const error = ref<TaskBatchErrorPresentation>();
    const downloadingFile = ref<string>();
    let lastInputMillis = -1;

    const available = computed(() => config.mode === "api");
    const isExecuting = computed(() => executionPhase.value !== "idle");

    async function execute(request: TaskBatchExecutionRequest): Promise<void> {
      if (isExecuting.value) {
        setError(taskBatchConfigurationError("A Task Batch is already running here."));
        return;
      }
      if (!validateRequest(request)) {
        return;
      }

      error.value = undefined;
      try {
        executionPhase.value = "uploading";
        const content = await request.file.arrayBuffer();
        const inputFile = nextInputFile(request.eventCode);
        await client.uploadInput(inputFile, content);

        executionPhase.value = "running";
        const completed = await client.run({
          workerGroupId: request.workerGroupId,
          eventCode: request.eventCode,
          payloadKey: request.payloadKey,
          inputFile,
          maximumWaitMillis: request.maximumWaitMillis
        });
        runs.value.unshift({ ...completed, originalFileName: request.file.name });
      } catch (failure) {
        error.value = presentTaskBatchError(failure);
      } finally {
        executionPhase.value = "idle";
      }
    }

    async function downloadRun(
      runId: string
    ): Promise<{ fileName: string; blob: Blob } | undefined> {
      const run = runs.value.find((candidate) => candidate.runId === runId);
      if (run === undefined) {
        setError(taskBatchConfigurationError("This Task Batch has no output."));
        return undefined;
      }
      error.value = undefined;
      downloadingFile.value = run.outputFile;
      try {
        return {
          fileName: run.outputFile,
          blob: await client.downloadOutput(run.outputFile)
        };
      } catch (failure) {
        error.value = presentTaskBatchError(failure);
        return undefined;
      } finally {
        downloadingFile.value = undefined;
      }
    }

    function validateRequest(request: TaskBatchExecutionRequest): boolean {
      if (!available.value) {
        setError(
          taskBatchConfigurationError(
            "Task Batch is available only with the real scenario-workers Profile."
          )
        );
        return false;
      }
      const entry = runtimeStore.entries.find(
        (candidate) => candidate.workerGroupId === request.workerGroupId
      );
      if (entry?.workerGroup === null || entry?.workerGroup === undefined) {
        setError(taskBatchConfigurationError("Select an available WorkerGroup."));
        return false;
      }
      if (!entry.workerGroup.eventCodes.includes(request.eventCode)) {
        setError(taskBatchConfigurationError("Select a configured EventCode."));
        return false;
      }
      if (request.payloadKey.trim().length === 0) {
        setError(taskBatchConfigurationError("Payload Key must not be blank."));
        return false;
      }
      if (!request.file.name.toLocaleLowerCase().endsWith(".txt")) {
        setError(taskBatchConfigurationError("The input file must use .txt."));
        return false;
      }
      if (request.file.size > MAX_INPUT_BYTES) {
        setError(taskBatchConfigurationError("The input file must not exceed 1 MiB."));
        return false;
      }
      if (
        !Number.isInteger(request.maximumWaitMillis) ||
        request.maximumWaitMillis < 1 ||
        request.maximumWaitMillis > MAXIMUM_WAIT_MILLIS
      ) {
        setError(
          taskBatchConfigurationError(
            "Maximum Wait must be a positive integer up to 300000 ms."
          )
        );
        return false;
      }
      return true;
    }

    function nextInputFile(eventCode: string): string {
      const millis = Math.max(Date.now(), lastInputMillis + 1);
      lastInputMillis = millis;
      const normalized = eventCode
        .replace(/[^A-Za-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "")
        .toLocaleLowerCase();
      return `${normalized}-${millis}.txt`;
    }

    function clearError(): void {
      error.value = undefined;
    }

    function setError(failure: unknown): void {
      error.value = presentTaskBatchError(failure);
    }

    return {
      available,
      executionPhase,
      isExecuting,
      runs,
      error,
      downloadingFile,
      execute,
      downloadRun,
      clearError
    };
  })();
}

export type TaskBatchStore = ReturnType<typeof createTaskBatchStore>;
