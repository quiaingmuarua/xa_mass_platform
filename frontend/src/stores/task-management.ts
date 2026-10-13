import { reactive, ref } from "vue";
import { defineStore } from "pinia";
import { chunkTaskItems } from "@/task-management/model";
import {
  presentFiniteTaskError,
  type FiniteTaskErrorPresentation
} from "@/task-management/errors";
import type { ProjectTask } from "@/task-management/projects";
import type {
  FiniteTaskClient,
  ImportReceipt,
  TaskCreateApiRequest,
  TaskItemApiRequest
} from "@/task-management/types";

export function canManageRawTask(task: ProjectTask, fresh: boolean): boolean {
  return (
    fresh &&
    task.task?.projectId === "default" &&
    task.task.idleDisposition === "CLOSE_WHEN_IDLE" &&
    task.scoreBand !== null &&
    task.scoreBand !== "terminal"
  );
}
export function newRawTaskDraft() {
  return {
    groupId: "",
    format: "txt" as "txt" | "jsonl",
    eventCode: "",
    payloadKey: "",
    selectorText: "",
    refillText: "[]",
    priority: 50,
    retries: 3,
    file: undefined as File | undefined,
    content: undefined as ArrayBuffer | undefined,
    inputError: ""
  };
}
export function createTaskManagementStore(
  mode: "api" | "mock",
  client: FiniteTaskClient
) {
  return defineStore("taskManagement", () => {
    const busy = ref(false);
    const submission = ref<ImportReceipt>();
    const error = ref<FiniteTaskErrorPresentation>();
    const drafts = reactive<Record<string, ReturnType<typeof newRawTaskDraft>>>({});
    const submissionKey = ref("");
    function draft(key: string) {
      drafts[key] ??= newRawTaskDraft();
      return drafts[key]!;
    }
    function admit() {
      if (mode !== "api") throw new Error("Mock 不执行原始任务写操作。");
      if (busy.value) throw new Error("操作进行中，请等待本次请求结束。");
      error.value = undefined;
    }
    function finishSubmission() {
      if (busy.value) return;
      const current = drafts[submissionKey.value];
      if (current) {
        current.file = undefined;
        current.content = undefined;
        current.inputError = "";
      }
      submissionKey.value = "";
      submission.value = undefined;
      error.value = undefined;
    }
    async function appendBatches(
      taskId: string,
      items: TaskItemApiRequest[],
      receipt: ImportReceipt
    ) {
      for (const batch of chunkTaskItems(items)) {
        receipt.notSubmitted -= batch.length;
        receipt.unconfirmed += batch.length;
        const response = await client.appendItems(taskId, batch);
        for (const item of batch) {
          const outcome = response[item.messageId];
          if (outcome?.status === "applied") {
            receipt.applied++;
            receipt.unconfirmed--;
          } else if (outcome?.status === "rejected") {
            receipt.rejected++;
            receipt.unconfirmed--;
          }
        }
        if (receipt.rejected || receipt.unconfirmed)
          throw new Error("本批包含拒绝或未确认项，后续批次已停止。");
      }
      receipt.complete = true;
    }
    async function submit(request: {
      create?: TaskCreateApiRequest;
      existing?: ProjectTask;
      fresh?: boolean;
      fileName: string;
      total: number;
      items: (taskId: string) => TaskItemApiRequest[];
    }): Promise<string | undefined> {
      admit();
      if (submission.value)
        throw new Error("请先结束上一次提交；不会自动重传或重建任务。");
      if (
        request.create?.projectId !== "default" &&
        !(
          request.existing &&
          canManageRawTask(request.existing, request.fresh === true) &&
          request.existing.scoreBand === "pre_review"
        )
      )
        throw new Error("只能向 default 的待审核有限任务追加。");
      busy.value = true;
      submissionKey.value = request.existing?.taskId ?? "create";
      submission.value = {
        taskId: request.existing?.taskId,
        fileName: request.fileName,
        total: request.total,
        applied: 0,
        rejected: 0,
        unconfirmed: 0,
        notSubmitted: request.total,
        complete: false
      };
      const receipt = submission.value;
      try {
        if (request.create)
          receipt.taskId = (await client.createTask(request.create)).taskId;
        if (!receipt.taskId) throw new Error("创建结果未确认，请人工核对任务列表。");
        await appendBatches(receipt.taskId, request.items(receipt.taskId), receipt);
      } catch (failure) {
        error.value = presentFiniteTaskError(failure);
        receipt.taskId ??= error.value.taskId;
        receipt.error = error.value.message;
      } finally {
        busy.value = false;
      }
      return receipt.taskId;
    }
    async function ensureDirectory(): Promise<boolean> {
      admit();
      const receipt = submission.value;
      if (!receipt?.taskId) return false;
      busy.value = true;
      try {
        await client.ensureDirectory(receipt.taskId);
        receipt.error =
          "任务目录已确认。请读取原任务，再显式追加号码或 Item；不会自动续提。";
        return true;
      } catch (failure) {
        error.value = presentFiniteTaskError(failure);
        receipt.error = error.value.message;
        return false;
      } finally {
        busy.value = false;
      }
    }
    async function mutate(
      task: ProjectTask,
      action: "approve" | "close",
      fresh: boolean
    ): Promise<boolean> {
      admit();
      if (
        !canManageRawTask(task, fresh) ||
        (action === "approve" && task.scoreBand !== "pre_review")
      )
        throw new Error("当前快照不允许此操作，请重新读取。");
      busy.value = true;
      try {
        if (action === "approve") await client.approveTask(task.taskId);
        else await client.closeTask(task.taskId);
        return true;
      } catch (failure) {
        error.value = presentFiniteTaskError(failure);
        return false;
      } finally {
        busy.value = false;
      }
    }
    return {
      busy,
      submission,
      submissionKey,
      error,
      draft,
      submit,
      ensureDirectory,
      mutate,
      finishSubmission
    };
  })();
}
export type TaskManagementStore = ReturnType<typeof createTaskManagementStore>;
