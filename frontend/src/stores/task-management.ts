import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  browserTaskManagementScheduler,
  buildMockResults,
  buildSeedItems,
  encodeMockJsonl,
  MAX_TASK_SEED_BYTES,
  parseSeedLines,
  presentationStatus,
  TASK_ADMISSION_DELAY_MILLIS,
  TASK_DISPATCH_DELAY_MILLIS
} from "@/task-management/model";
import type {
  AttachMockFiniteTaskSeedRequest,
  CreateMockFiniteTaskRequest,
  MockFiniteTask,
  MockTaskDownload,
  TaskManagementCatalog,
  TaskManagementScheduler
} from "@/task-management/types";

export function createTaskManagementStore(
  catalog: TaskManagementCatalog,
  scheduler: TaskManagementScheduler = browserTaskManagementScheduler
) {
  return defineStore("taskManagement", () => {
    const tasks = ref<MockFiniteTask[]>([]);
    const error = ref<string>();

    const awaitingApprovalCount = computed(
      () =>
        tasks.value.filter((task) => presentationStatus(task) === "awaiting-approval")
          .length
    );
    const schedulingCount = computed(
      () =>
        tasks.value.filter((task) =>
          ["waiting-admission", "dispatch-visible"].includes(presentationStatus(task))
        ).length
    );
    const closedCount = computed(
      () => tasks.value.filter((task) => task.lifecycleState === "TERMINAL").length
    );

    function createTask(
      request: CreateMockFiniteTaskRequest
    ): MockFiniteTask | undefined {
      clearError();
      const taskId = request.taskId.trim();
      if (taskId.length === 0) {
        return fail("Task ID must not be blank.");
      }
      if (tasks.value.some((task) => task.taskId === taskId)) {
        return fail("Task ID already exists in this browser session.");
      }
      if (workerGroup(request.workerGroupId) === undefined) {
        return fail("Select an available WorkerGroup.");
      }
      if (!validConfig(request.config)) {
        return fail("Task scheduling values are outside their supported ranges.");
      }

      const now = timestamp();
      const task: MockFiniteTask = {
        taskId,
        workerGroupId: request.workerGroupId,
        lifecycleState: "PRE_REVIEW",
        seedState: "MISSING",
        allocationRule: {},
        config: { ...request.config },
        results: [],
        createdAt: now,
        updatedAt: now
      };
      tasks.value.unshift(task);
      return task;
    }

    async function attachSeed(
      request: AttachMockFiniteTaskSeedRequest
    ): Promise<MockFiniteTask | undefined> {
      clearError();
      const task = findTask(request.taskId);
      if (task === undefined) {
        return fail("Task does not exist in this browser session.");
      }
      if (task.lifecycleState !== "PRE_REVIEW") {
        return fail("Seeds cannot be replaced after Task approval.");
      }
      const group = workerGroup(task.workerGroupId);
      if (group === undefined || !group.eventCodes.includes(request.eventCode)) {
        return fail("Select an EventCode from the WorkerGroup catalog.");
      }
      const payloadKey = request.payloadKey.trim();
      if (payloadKey.length === 0) {
        return fail("Payload Key must not be blank.");
      }
      if (!request.file.name.toLocaleLowerCase().endsWith(".txt")) {
        return fail("The seed file must use .txt.");
      }
      if (request.file.size > MAX_TASK_SEED_BYTES) {
        return fail("The seed file must not exceed 1 MiB.");
      }

      let content: ArrayBuffer;
      let lines: string[];
      try {
        content = await request.file.arrayBuffer();
        lines = parseSeedLines(content);
      } catch (failure) {
        return fail(
          failure instanceof Error ? failure.message : "The seed file is invalid."
        );
      }
      if (lines.length === 0) {
        return fail("The seed file must contain at least one line.");
      }

      task.seed = {
        originalFileName: request.file.name,
        byteCount: content.byteLength,
        lineCount: lines.length,
        eventCode: request.eventCode,
        payloadKey,
        items: buildSeedItems(lines, payloadKey)
      };
      task.seedState = "READY";
      task.updatedAt = timestamp();
      return task;
    }

    async function approveTask(taskId: string): Promise<boolean> {
      clearError();
      const task = findTask(taskId);
      if (task === undefined) {
        fail("Task does not exist in this browser session.");
        return false;
      }
      if (task.lifecycleState !== "PRE_REVIEW" || task.seed === undefined) {
        fail("Task must have reviewed Seeds before approval.");
        return false;
      }

      task.lifecycleState = "ADMISSION_VISIBLE";
      task.approvedAt = timestamp();
      task.updatedAt = task.approvedAt;

      await scheduler.wait(TASK_ADMISSION_DELAY_MILLIS);
      if (task.lifecycleState !== "ADMISSION_VISIBLE") {
        return false;
      }
      task.lifecycleState = "RUNNING_VISIBLE";
      task.updatedAt = timestamp();

      await scheduler.wait(TASK_DISPATCH_DELAY_MILLIS);
      if (task.lifecycleState !== "RUNNING_VISIBLE") {
        return false;
      }
      task.results = buildMockResults(task);
      task.lifecycleState = "TERMINAL";
      task.outputFile = `${task.taskId}.mock.jsonl`;
      task.closedAt = timestamp();
      task.updatedAt = task.closedAt;
      return true;
    }

    function downloadTask(taskId: string): MockTaskDownload | undefined {
      clearError();
      const task = findTask(taskId);
      if (
        task === undefined ||
        task.lifecycleState !== "TERMINAL" ||
        task.outputFile === undefined
      ) {
        return fail("This Task does not have a published Mock output.");
      }
      return {
        fileName: task.outputFile,
        blob: new Blob([encodeMockJsonl(task.results)], {
          type: "application/x-ndjson;charset=utf-8"
        })
      };
    }

    function clearError(): void {
      error.value = undefined;
    }

    function findTask(taskId: string): MockFiniteTask | undefined {
      return tasks.value.find((task) => task.taskId === taskId);
    }

    function workerGroup(workerGroupId: string): { eventCodes: string[] } | undefined {
      return (
        catalog.entries.find(
          (entry) => entry.workerGroupId === workerGroupId && entry.workerGroup !== null
        )?.workerGroup ?? undefined
      );
    }

    function timestamp(): string {
      return new Date(scheduler.now()).toISOString();
    }

    function fail(message: string): undefined {
      error.value = message;
      return undefined;
    }

    return {
      tasks,
      error,
      awaitingApprovalCount,
      schedulingCount,
      closedCount,
      createTask,
      attachSeed,
      approveTask,
      downloadTask,
      clearError
    };
  })();
}

function validConfig(config: CreateMockFiniteTaskRequest["config"]): boolean {
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
