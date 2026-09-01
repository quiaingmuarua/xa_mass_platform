import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  chunkTaskItems,
  buildSeedItems,
  materializeTaskItems,
  MAX_TASK_SEED_BYTES,
  parseSeedLines
} from "@/task-management/model";
import {
  finiteTaskConfigurationError,
  presentFiniteTaskError,
  type FiniteTaskErrorPresentation
} from "@/task-management/errors";
import type {
  CreateFiniteTaskExecutionRequest,
  FiniteTaskClient,
  FiniteTaskDownload,
  FiniteTaskSession,
  TaskManagementCatalog
} from "@/task-management/types";

export function createTaskManagementStore(
  catalog: TaskManagementCatalog,
  client: FiniteTaskClient
) {
  return defineStore("taskManagement", () => {
    const tasks = ref<FiniteTaskSession[]>([]);
    const error = ref<FiniteTaskErrorPresentation>();
    const notice = ref<string>();
    const activeTaskId = ref<string>();

    const available = computed(() => catalog.mode === "api");
    const isBusy = computed(() => activeTaskId.value !== undefined);
    const appendedCount = computed(
      () => tasks.value.filter((task) => task.stage === "ITEMS_APPENDED").length
    );
    const approvedCount = computed(
      () => tasks.value.filter((task) => task.stage === "APPROVED").length
    );
    const exportReadyCount = computed(
      () => tasks.value.filter((task) => task.stage === "EXPORT_READY").length
    );

    async function createAndAppend(
      request: CreateFiniteTaskExecutionRequest
    ): Promise<FiniteTaskSession | undefined> {
      clearMessages();
      if (!available.value) {
        return fail(
          finiteTaskConfigurationError(
            "Finite Task operations are disabled while the Runtime Viewer uses Mock data."
          )
        );
      }
      if (isBusy.value) {
        return fail(
          finiteTaskConfigurationError("Another finite Task operation is in progress.")
        );
      }

      const group = workerGroup(request.workerGroupId);
      const payloadKey = request.payloadKey.trim();
      if (group === undefined) {
        return fail(finiteTaskConfigurationError("Select an available WorkerGroup."));
      }
      if (!group.eventCodes.includes(request.eventCode)) {
        return fail(
          finiteTaskConfigurationError(
            "Select an EventCode from the WorkerGroup catalog."
          )
        );
      }
      if (payloadKey.length === 0) {
        return fail(finiteTaskConfigurationError("Payload Key must not be blank."));
      }
      if (!validConfig(request.config)) {
        return fail(
          finiteTaskConfigurationError(
            "Task scheduling values are outside their supported ranges."
          )
        );
      }
      if (!request.file.name.toLocaleLowerCase().endsWith(".txt")) {
        return fail(finiteTaskConfigurationError("The input file must use .txt."));
      }
      if (request.file.size > MAX_TASK_SEED_BYTES) {
        return fail(
          finiteTaskConfigurationError("The input file must not exceed 1 MiB.")
        );
      }

      let content: ArrayBuffer;
      let lines: string[];
      try {
        content = await request.file.arrayBuffer();
        lines = parseSeedLines(content);
      } catch (failure) {
        return fail(
          finiteTaskConfigurationError(
            failure instanceof Error ? failure.message : "The input file is invalid."
          )
        );
      }
      if (content.byteLength === 0 || lines.length === 0) {
        return fail(
          finiteTaskConfigurationError("The input file must contain at least one line.")
        );
      }

      activeTaskId.value = "creating";
      try {
        const created = await client.createTask({
          workerGroupId: request.workerGroupId,
          allocationRule: {},
          ...request.config
        });
        const now = new Date().toISOString();
        const task: FiniteTaskSession = {
          taskId: created.taskId,
          workerGroupId: request.workerGroupId,
          eventCode: request.eventCode,
          payloadKey,
          originalFileName: request.file.name,
          byteCount: content.byteLength,
          lineCount: lines.length,
          appendedCount: 0,
          stage: "CREATED",
          config: { ...request.config },
          createdAt: now,
          updatedAt: now
        };
        tasks.value.unshift(task);
        activeTaskId.value = task.taskId;

        const items = materializeTaskItems(
          task.taskId,
          task.eventCode,
          buildSeedItems(lines, payloadKey)
        );
        for (const chunk of chunkTaskItems(items)) {
          const response = await client.appendItems(task.taskId, chunk);
          const rejected = chunk.find(
            (item) => response[item.messageId]?.status !== "applied"
          );
          if (rejected !== undefined) {
            const outcome = response[rejected.messageId];
            throw new Error(
              (outcome?.status === "rejected" ? outcome.message : undefined) ??
                `Item ${rejected.messageId} was not appended.`
            );
          }
          task.appendedCount += chunk.length;
          task.updatedAt = new Date().toISOString();
        }
        task.stage = "ITEMS_APPENDED";
        return task;
      } catch (failure) {
        return fail(failure);
      } finally {
        activeTaskId.value = undefined;
      }
    }

    async function approveTask(taskId: string): Promise<boolean> {
      clearMessages();
      const task = findTask(taskId);
      if (task === undefined || task.stage !== "ITEMS_APPENDED") {
        fail(
          finiteTaskConfigurationError("Task Items must be appended before approval.")
        );
        return false;
      }
      activeTaskId.value = taskId;
      try {
        await client.approveTask(taskId);
        task.stage = "APPROVED";
        task.updatedAt = new Date().toISOString();
        return true;
      } catch (failure) {
        fail(failure);
        return false;
      } finally {
        activeTaskId.value = undefined;
      }
    }

    async function exportTask(taskId: string): Promise<FiniteTaskDownload | undefined> {
      clearMessages();
      const task = findTask(taskId);
      if (
        task === undefined ||
        (task.stage !== "APPROVED" && task.stage !== "EXPORT_READY")
      ) {
        return fail(
          finiteTaskConfigurationError("Only an approved Task can export results.")
        );
      }
      activeTaskId.value = taskId;
      try {
        const exported = await client.exportResults(taskId);
        if (!exported.ready) {
          notice.value = "结果尚未就绪，请稍后手工重试导出。";
          return undefined;
        }
        task.stage = "EXPORT_READY";
        task.updatedAt = new Date().toISOString();
        return { fileName: exported.fileName, blob: exported.blob };
      } catch (failure) {
        return fail(failure);
      } finally {
        activeTaskId.value = undefined;
      }
    }

    function clearMessages(): void {
      error.value = undefined;
      notice.value = undefined;
    }

    function findTask(taskId: string): FiniteTaskSession | undefined {
      return tasks.value.find((task) => task.taskId === taskId);
    }

    function workerGroup(workerGroupId: string): { eventCodes: string[] } | undefined {
      return (
        catalog.workerGroups.find((group) => group.workerGroupId === workerGroupId) ??
        undefined
      );
    }

    function fail(failure: unknown): undefined {
      error.value = presentFiniteTaskError(failure);
      return undefined;
    }

    return {
      tasks,
      error,
      notice,
      activeTaskId,
      available,
      isBusy,
      appendedCount,
      approvedCount,
      exportReadyCount,
      createAndAppend,
      approveTask,
      exportTask,
      clearMessages
    };
  })();
}

function validConfig(config: CreateFiniteTaskExecutionRequest["config"]): boolean {
  return (
    Number.isInteger(config.priority) &&
    config.priority >= 0 &&
    config.priority <= 99 &&
    Number.isInteger(config.maximumCandidateWorkers) &&
    config.maximumCandidateWorkers > 0 &&
    Number.isInteger(config.maxRetryTimes) &&
    config.maxRetryTimes >= 0 &&
    config.maxRetryTimes <= 98
  );
}

export type TaskManagementStore = ReturnType<typeof createTaskManagementStore>;
