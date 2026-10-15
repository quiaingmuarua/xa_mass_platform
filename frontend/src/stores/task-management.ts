import { type TaskCaller, requireCaller } from "@/task-caller";
import { captureTaskCaller } from "@/console-identity";
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

export function canManageRawTask(
  task: ProjectTask,
  projectId: string,
  fresh: boolean
): boolean {
  return (
    fresh &&
    !!projectId &&
    task.task?.projectId === projectId &&
    task.task.idleDisposition === "CLOSE_WHEN_IDLE" &&
    task.scoreBand !== null &&
    task.scoreBand !== "terminal"
  );
}
export function newRawTaskDraft() {
  return {
    caller: captureTaskCaller(),
    requestId: crypto.randomUUID(),
    groupId: "",
    format: "payload" as "payload" | "txt" | "jsonl",
    inputMode: "paste" as "paste" | "file",
    pasteText: "",
    eventCode: "",
    payloadKey: "",
    executorName: "",
    queryInput: "{}",
    refillText: "[]",
    refillMode: "rows" as "rows" | "json",
    supplyRows: [] as { poolName: string; targetText: string; count: number }[],
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
    let disposed = false;
    const busy = ref(false);
    const projects = reactive<
      Record<
        string,
        {
          submission?: ImportReceipt;
          error?: FiniteTaskErrorPresentation;
          submissionKey: string;
          drafts: Record<string, ReturnType<typeof newRawTaskDraft>>;
        }
      >
    >(Object.create(null));
    function project(projectId: string) {
      if (!projectId) throw new Error("请选择 Project。");
      projects[projectId] ??= { submissionKey: "", drafts: Object.create(null) };
      return projects[projectId]!;
    }
    function draft(projectId: string, key: string) {
      const state = project(projectId);
      state.drafts[key] ??= newRawTaskDraft();
      return state.drafts[key]!;
    }
    function admit() {
      if (disposed) throw new Error("当前用户已退出");
      if (mode !== "api") throw new Error("Mock 不执行原始任务写操作。");
      if (busy.value) throw new Error("操作进行中，请等待本次请求结束。");
    }
    function finishSubmission(projectId: string) {
      if (busy.value) return;
      const state = project(projectId);
      const current = state.drafts[state.submissionKey];
      if (current) {
        current.requestId = crypto.randomUUID();
        current.caller = captureTaskCaller();
        current.file = undefined;
        current.content = undefined;
        current.pasteText = "";
        current.inputError = "";
      }
      state.submissionKey = "";
      state.submission = undefined;
      state.error = undefined;
    }
    async function appendBatches(
      taskId: string,
      items: TaskItemApiRequest[],
      receipt: ImportReceipt
    ) {
      for (const batch of chunkTaskItems(items)) {
        if (disposed) return;
        receipt.notSubmitted -= batch.length;
        receipt.unconfirmed += batch.length;
        const response = await client.appendItems(receipt.caller, taskId, batch);
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
      caller: TaskCaller;
      projectId: string;
      create?: TaskCreateApiRequest;
      existing?: ProjectTask;
      fresh?: boolean;
      fileName: string;
      total: number;
      items: (taskId: string) => TaskItemApiRequest[];
    }): Promise<string | undefined> {
      admit();
      const state = project(request.projectId);
      if (state.submission)
        throw new Error("请先结束上一次提交；不会自动重传或重建任务。");
      if (
        Boolean(request.create) === Boolean(request.existing) ||
        (request.create
          ? request.create.projectId !== request.projectId
          : !request.existing ||
            !canManageRawTask(
              request.existing,
              request.projectId,
              request.fresh === true
            ) ||
            request.existing.scoreBand !== "pre_review" ||
            request.existing.task?.metadata["server.inputOwner"] !== "raw")
      )
        throw new Error("只能创建当前 Project 的任务或向其待审核有限任务追加。");
      state.error = undefined;
      busy.value = true;
      state.submissionKey = request.existing?.taskId ?? "create";
      state.submission = {
        caller: requireCaller(request.caller),
        taskId: request.existing?.taskId,
        fileName: request.fileName,
        total: request.total,
        applied: 0,
        rejected: 0,
        unconfirmed: 0,
        notSubmitted: request.total,
        complete: false
      };
      const receipt = state.submission;
      try {
        if (request.create)
          receipt.taskId = (
            await client.createTask(receipt.caller, request.create)
          ).taskId;
        if (!receipt.taskId) throw new Error("创建结果未确认，请人工核对任务列表。");
        if (!disposed)
          await appendBatches(receipt.taskId, request.items(receipt.taskId), receipt);
      } catch (failure) {
        state.error = presentFiniteTaskError(failure);
        receipt.taskId ??= state.error.taskId;
        receipt.error = state.error.message;
      } finally {
        busy.value = false;
      }
      return receipt.taskId;
    }
    async function ensureDirectory(projectId: string): Promise<boolean> {
      admit();
      const state = project(projectId);
      const receipt = state.submission;
      if (!receipt?.taskId) return false;
      state.error = undefined;
      busy.value = true;
      try {
        await client.ensureDirectory(receipt.caller, receipt.taskId);
        receipt.error =
          "任务目录已确认。请读取原任务，再显式追加号码或 Item；不会自动续提。";
        return true;
      } catch (failure) {
        state.error = presentFiniteTaskError(failure);
        receipt.error = state.error.message;
        return false;
      } finally {
        busy.value = false;
      }
    }
    async function mutate(
      projectId: string,
      task: ProjectTask,
      action: "approve" | "close",
      fresh: boolean
    ): Promise<boolean> {
      admit();
      if (
        !canManageRawTask(task, projectId, fresh) ||
        (action === "approve" && task.scoreBand !== "pre_review")
      )
        throw new Error("当前快照不允许此操作，请重新读取。");
      const state = project(projectId);
      state.error = undefined;
      busy.value = true;
      try {
        const caller = captureTaskCaller();
        if (action === "approve") {
          if (!task.stats || task.stats.total <= 0)
            throw new Error("请刷新并核对非空任务的 Item 数量。");
          await client.approveTask(caller, task.taskId, task.stats.total);
        } else await client.closeTask(caller, task.taskId);
        return true;
      } catch (failure) {
        state.error = presentFiniteTaskError(failure);
        return false;
      } finally {
        busy.value = false;
      }
    }
    return {
      busy,
      project,
      draft,
      submit,
      ensureDirectory,
      mutate,
      finishSubmission,
      dispose() {
        disposed = true;
      }
    };
  })();
}
export type TaskManagementStore = ReturnType<typeof createTaskManagementStore>;
