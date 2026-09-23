import { z } from "zod";
import {
  catalogSchema,
  detailSchema,
  listSchema,
  type Catalog,
  type CheckDetail,
  type CheckTask,
  type CreateCheckTask
} from "./model";

export interface AppCheckTaskSource {
  readonly mode: "api" | "mock";
  catalog(signal?: AbortSignal): Promise<Catalog>;
  listTasks(): Promise<{ tasks: CheckTask[]; truncated: boolean }>;
  loadTask(taskId: string): Promise<CheckDetail>;
  createTask(input: CreateCheckTask): Promise<{ taskId: string }>;
}
export class AppCheckApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly taskId?: string
  ) {
    super(message);
  }
}
export class AppCheckCreationUnconfirmed extends Error {
  constructor(public readonly taskId?: string) {
    super("提交结果未确认。请核对任务列表，不会自动重试或重新创建。");
  }
}
async function request(
  path: string,
  body?: unknown,
  signal?: AbortSignal
): Promise<unknown> {
  const response = await fetch(`/api/v1/app-checks${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal
  });
  if (!response.ok) {
    const failure = z
      .object({ message: z.string().optional(), taskId: z.string().optional() })
      .safeParse(await response.json().catch(() => null));
    throw new AppCheckApiError(
      response.status,
      failure.success
        ? (failure.data.message ?? `请求失败 (${response.status})`)
        : `请求失败 (${response.status})`,
      failure.success ? failure.data.taskId : undefined
    );
  }
  return response.json();
}
export class ApiAppCheckTaskSource implements AppCheckTaskSource {
  readonly mode = "api";
  async catalog(signal?: AbortSignal) {
    return catalogSchema.parse(await request("/catalog", undefined, signal));
  }
  async listTasks() {
    return listSchema.parse(await request("/tasks?limit=100"));
  }
  async loadTask(taskId: string) {
    const value = detailSchema.parse(
      await request(`/tasks/${encodeURIComponent(taskId)}`)
    );
    if (value.task.taskId !== taskId) throw new Error("返回的任务身份不符");
    return value;
  }
  async createTask(input: CreateCheckTask) {
    try {
      return z
        .object({ taskId: z.string().min(1) })
        .parse(await request("/tasks", input));
    } catch (error) {
      if (
        error instanceof AppCheckApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        error.status !== 408
      )
        throw error;
      throw new AppCheckCreationUnconfirmed(
        error instanceof AppCheckApiError ? error.taskId : undefined
      );
    }
  }
}
