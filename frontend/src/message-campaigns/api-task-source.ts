import { z } from "zod";
import { api, MessageApiError } from "./api";
import {
  MessageTaskCreationUnconfirmed,
  type CreateMessageTask,
  type MessageTaskSource
} from "./task-source";

const country = z.enum(["CN", "US", "GB"]);
const count = z.number().int().nonnegative();
const task = z.object({
  taskId: z.string().min(1),
  name: z.string().optional(),
  createdAtMillis: count,
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
  senderPhone: z.string().optional(),
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
const detail = z.object({
  task,
  results: z.array(result).max(100),
  resultsTruncated: z.boolean()
});

export class ApiMessageTaskSource implements MessageTaskSource {
  readonly mode = "api";
  async listTasks() {
    return list.parse(await api<unknown>("/tasks?limit=100"));
  }
  async loadTask(taskId: string) {
    const value = detail.parse(
      await api<unknown>(`/tasks/${encodeURIComponent(taskId)}`)
    );
    if (value.task.taskId !== taskId) throw new Error("返回的任务身份不符");
    return value;
  }
  async createTask(input: CreateMessageTask) {
    try {
      return z
        .object({ taskId: z.string().min(1) })
        .parse(await api<unknown>("/tasks", input));
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
