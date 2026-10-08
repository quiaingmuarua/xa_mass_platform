import { z } from "zod";
import type { ImportSnapshot, ImportReceipt } from "./import-model";
import type { PreviewState, ExportFilter, TaskActivity } from "./workbench";
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
  importNumbers?(
    taskId: string,
    file: Blob,
    summary?: ImportSnapshot
  ): Promise<ImportReceipt>;
  // Explicit Mock interactions for the design preview; the API capabilities remain unchanged.
  approveTask?(taskId: string, expectedCount: number): Promise<void>;
  closeTask?(taskId: string): Promise<void>;
  advanceTask?(taskId: string, late?: boolean): Promise<void>;
  completeTask?(taskId: string): Promise<void>;
  previewState?(taskId: string): PreviewState;
  loadImport?(taskId: string): Promise<ImportSnapshot | undefined>;
  loadActivity?(taskId: string): Promise<TaskActivity[]>;
  exportCounts?(taskId: string): Promise<Record<ExportFilter, number>>;
  exportTask?(
    taskId: string,
    filter: "all" | "registered" | "unregistered"
  ): Promise<{ blob: Blob; fileName: string; count: number }>;
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
export class AppCheckImportUnconfirmed extends Error {
  constructor(
    public readonly taskId: string,
    message = "导入结果未确认，请核对实际数量或显式重新导入。"
  ) {
    super(message);
  }
}
async function requireResponse(response: Response): Promise<Response> {
  if (!response.ok) {
    const value = z
      .object({ message: z.string().optional(), taskId: z.string().optional() })
      .safeParse(await response.json().catch(() => null));
    throw new AppCheckApiError(
      response.status,
      value.success
        ? (value.data.message ?? `请求失败 (${response.status})`)
        : `请求失败 (${response.status})`,
      value.success ? value.data.taskId : undefined
    );
  }
  return response;
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
  return (await requireResponse(response)).json();
}
export class ApiAppCheckTaskSource implements AppCheckTaskSource {
  readonly mode = "api";
  private readonly imports = new Map<string, ImportSnapshot>();
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
      const requestBody = {
        requestId: input.requestId,
        appId: input.appId,
        country: input.country,
        simulation: input.simulation
      };
      return z
        .object({ taskId: z.string().min(1) })
        .parse(await request("/tasks", requestBody));
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
  async importNumbers(
    taskId: string,
    file: Blob,
    summary?: ImportSnapshot
  ): Promise<ImportReceipt> {
    try {
      const response = await requireResponse(
        await fetch(
          `/api/v1/app-checks/tasks/${encodeURIComponent(taskId)}/numbers:import`,
          {
            method: "POST",
            headers: { "Content-Type": "text/plain;charset=UTF-8" },
            body: file
          }
        )
      );
      const count = z.number().int().nonnegative();
      const receipt = z
        .object({
          taskId: z.literal(taskId),
          inputCount: count,
          emptyCount: count,
          duplicateCount: count,
          uniqueCount: count,
          addedCount: count,
          existingCount: count
        })
        .parse(await response.json());
      if (summary) this.imports.set(taskId, structuredClone({ ...summary, receipt }));
      return receipt;
    } catch (error) {
      if (
        error instanceof AppCheckApiError &&
        error.status < 500 &&
        error.status !== 408
      )
        throw error;
      throw new AppCheckImportUnconfirmed(
        taskId,
        error instanceof Error ? error.message : undefined
      );
    }
  }
  async loadImport(taskId: string) {
    const saved = this.imports.get(taskId);
    return saved ? structuredClone(saved) : undefined;
  }
  async approveTask(taskId: string, expectedCount: number) {
    await request(`/tasks/${encodeURIComponent(taskId)}/approve`, expectedCount);
  }
  async closeTask(taskId: string) {
    await request(`/tasks/${encodeURIComponent(taskId)}/close`, {});
  }
  async exportTask(taskId: string, filter: ExportFilter) {
    const response = await requireResponse(
      await fetch(
        `/api/v1/app-checks/tasks/${encodeURIComponent(taskId)}/results:export?filter=${filter}`,
        { method: "POST" }
      )
    );
    const header = response.headers.get("X-Export-Count");
    if (header === null) throw new Error("导出数量未确认");
    const count = z.number().int().nonnegative().parse(Number(header));
    return { blob: await response.blob(), fileName: `${taskId}-${filter}.csv`, count };
  }
}
