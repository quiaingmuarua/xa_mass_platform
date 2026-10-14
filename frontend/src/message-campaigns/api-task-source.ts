import { type TaskCaller, requireCaller } from "@/task-caller";
import {
  createProjectSource,
  countFrom,
  type ProjectTask
} from "@/task-management/projects";
import { HttpFiniteTaskClient } from "@/task-management/http-client";
import { z } from "zod";
import { api, MessageApiError, type Catalog } from "./api";
import { apiApplications } from "./workbench";
import {
  MessageTaskCreationUnconfirmed,
  type CreateMessageTask,
  type MessageTaskSource
} from "./task-source";

const country = z.enum(["CN", "US", "GB"]);
const count = z.number().int().nonnegative();
const task = z.object({
  taskId: z.string().min(1),
  userId: z.string().optional(),
  name: z.string().optional(),
  appId: z.string().optional(),
  inputVersion: z.string().optional(),
  createdAtMillis: count.nullable(),
  workerGroupId: z.string().nullable(),
  managed: z.boolean(),
  state: z
    .enum(["pre_review", "running-initial", "running_visible", "terminal"])
    .nullable(),
  recipientCountry: country.optional(),
  senderCountry: country.nullable().optional(),
  sendTotal: count.optional(),
  sentCount: count.optional(),
  deliveredCount: count.optional(),
  readCount: count.optional(),
  repliedCount: count.optional(),
  failedCount: count.optional(),
  body: z.string().optional()
});
const result = z.object({
  messageId: z.string().min(1),
  recipientId: z.string().optional(),
  resultStatus: z.enum(["succeeded", "failed"]),
  status: z
    .enum(["SENT", "DELIVERED", "READ", "REPLIED", "EXECUTION_FAILED"])
    .optional(),
  contentError: z.string().optional(),
  phone: z.string().optional(),
  workerId: z.string().optional(),
  reply: z.string().optional(),
  observedAtMillis: count.optional()
});
const list = z.object({ tasks: z.array(task).max(100), truncated: z.boolean() });

export class ApiMessageTaskSource implements MessageTaskSource {
  readonly mode = "api";
  private readonly taskClient = new HttpFiniteTaskClient("/api");
  constructor(private readonly catalog: () => Catalog | undefined) {}
  get applications() {
    return apiApplications(this.catalog());
  }
  async listTasks(caller: TaskCaller) {
    const page = await createProjectSource("/api", "api", requireCaller(caller)).tasks(
      "messages"
    );
    return list.parse({
      tasks: page.tasks.map(messageTaskView),
      truncated: page.truncated
    });
  }
  async loadTask(caller: TaskCaller, taskId: string) {
    caller = requireCaller(caller);
    const entry = await createProjectSource("/api", "api", caller).task(
      "messages",
      taskId
    );
    const preview = z
      .object({
        taskId: z.literal(taskId),
        results: z.array(result).max(100),
        truncated: z.boolean()
      })
      .strict()
      .parse(
        await api<unknown>(
          `/tasks/${encodeURIComponent(taskId)}/results`,
          undefined,
          undefined,
          "json",
          caller
        )
      );
    return {
      task: messageTaskView(entry),
      results: preview.results,
      resultsTruncated: preview.truncated
    };
  }
  async importRecipients(caller: TaskCaller, taskId: string, text: string) {
    const value = z
      .object({
        taskId: z.string(),
        inputCount: count,
        emptyCount: count,
        duplicateCount: count,
        uniqueCount: count,
        confirmedAddedCount: count,
        existingCount: count
      })
      .parse(
        await api<unknown>(
          `/tasks/${encodeURIComponent(taskId)}/recipients:import`,
          text,
          undefined,
          "text",
          requireCaller(caller)
        )
      );
    if (value.taskId !== taskId) throw new Error("导入回执的任务身份不符，请核对任务");
    return value;
  }
  async approveTask(caller: TaskCaller, taskId: string, expectedCount: number) {
    await this.taskClient.approveTask(requireCaller(caller), taskId, expectedCount);
  }
  async closeTask(caller: TaskCaller, taskId: string) {
    await this.taskClient.closeTask(requireCaller(caller), taskId);
  }

  async createTask(caller: TaskCaller, input: CreateMessageTask) {
    if (!this.applications.some((app) => app.id === input.appId))
      throw new Error("应用配置不可用，不能为此应用创建任务。");
    const { appId, requestId, name, recipientCountry, senderCountry, body } = input;
    try {
      return z.object({ taskId: z.string().min(1) }).parse(
        await api<unknown>(
          "/tasks",
          {
            appId,
            requestId,
            name,
            recipientCountry,
            senderCountry,
            body
          },
          undefined,
          "json",
          requireCaller(caller)
        )
      );
    } catch (error) {
      if (error instanceof MessageApiError && error.status >= 400 && error.status < 500)
        throw error;
      throw new MessageTaskCreationUnconfirmed(
        "提交结果未确认，不会自动重试。",
        error instanceof MessageApiError ? error.taskId : undefined
      );
    }
  }
}

function messageTaskView(entry: ProjectTask) {
  const metadata = entry.task?.metadata ?? {};
  const row: Record<string, unknown> = {
    taskId: entry.taskId,
    createdAtMillis: entry.createdAtMillis,
    workerGroupId: entry.task?.workerGroupId ?? null,
    managed: entry.task?.idleDisposition === "PARK_WHEN_IDLE",
    state: entry.scoreBand,
    ...(entry.task?.name ? { name: entry.task.name } : {}),
    ...(metadata.userId ? { userId: metadata.userId } : {})
  };
  if (metadata["server.inputOwner"] === "messages") {
    for (const key of ["appId", "recipientCountry", "body", "inputVersion"])
      if (metadata[key] !== undefined) row[key] = metadata[key];
    row.senderCountry = metadata.senderCountry ?? null;
    if (entry.stats)
      Object.assign(row, {
        sendTotal: entry.stats.total,
        sentCount: countFrom(entry.stats, 6, 9),
        deliveredCount: countFrom(entry.stats, 7, 9),
        readCount: countFrom(entry.stats, 8, 9),
        repliedCount: countFrom(entry.stats, 9),
        failedCount: countFrom(entry.stats, 5)
      });
  }
  return task.parse(row);
}
