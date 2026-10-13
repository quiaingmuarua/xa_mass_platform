import { z } from "zod";
import type { ConsoleIdentity } from "@/console-identity";
import {
  configurationViewSchema,
  type AppCheckSettings,
  type AppCheckConfigurationView
} from "./settings";
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

export type AppCheckCaller = Readonly<Pick<ConsoleIdentity, "userId" | "role">>;
export interface AppCheckTaskSource {
  readonly mode: "api" | "mock";
  catalog(signal?: AbortSignal): Promise<Catalog>;
  loadConfiguration(caller: AppCheckCaller): Promise<AppCheckConfigurationView>;
  saveConfiguration(
    caller: AppCheckCaller,
    expectedRevision: number,
    configuration: AppCheckSettings
  ): Promise<AppCheckConfigurationView>;
  simulateConfigurationRestart?(caller: AppCheckCaller): Promise<void>;
  listTasks(
    caller: AppCheckCaller
  ): Promise<{ tasks: CheckTask[]; truncated: boolean }>;
  loadTask(caller: AppCheckCaller, taskId: string): Promise<CheckDetail>;
  createTask(
    caller: AppCheckCaller,
    input: CreateCheckTask
  ): Promise<{ taskId: string }>;
  importNumbers?(
    caller: AppCheckCaller,
    taskId: string,
    file: Blob,
    summary?: ImportSnapshot
  ): Promise<ImportReceipt>;
  // Explicit Mock interactions for the design preview; the API capabilities remain unchanged.
  approveTask?(
    caller: AppCheckCaller,
    taskId: string,
    expectedCount: number
  ): Promise<void>;
  closeTask?(caller: AppCheckCaller, taskId: string): Promise<void>;
  advanceTask?(caller: AppCheckCaller, taskId: string, late?: boolean): Promise<void>;
  completeTask?(caller: AppCheckCaller, taskId: string): Promise<void>;
  previewState?(caller: AppCheckCaller, taskId: string): PreviewState;
  loadImport?(
    caller: AppCheckCaller,
    taskId: string
  ): Promise<ImportSnapshot | undefined>;
  loadActivity?(caller: AppCheckCaller, taskId: string): Promise<TaskActivity[]>;
  exportCounts?(
    caller: AppCheckCaller,
    taskId: string
  ): Promise<Record<ExportFilter, number>>;
  exportTask?(
    caller: AppCheckCaller,
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
export function requireCaller(caller: AppCheckCaller): AppCheckCaller {
  if (
    !caller ||
    typeof caller.userId !== "string" ||
    !caller.userId.trim() ||
    caller.userId.trim().length > 128 ||
    !["admin", "operator"].includes(caller.role)
  )
    throw new AppCheckApiError(400, "开发调用方无效");
  return Object.freeze({ userId: caller.userId.trim(), role: caller.role });
}
export function requireAdminCaller(caller: AppCheckCaller) {
  if (requireCaller(caller).role !== "admin")
    throw new AppCheckApiError(403, "当前操作仅对管理员开发身份开放");
}
export function requireTaskCaller(caller: AppCheckCaller, task: CheckTask) {
  const value = requireCaller(caller);
  if (value.role !== "admin" && value.userId !== task.userId)
    throw new AppCheckApiError(403, "当前开发身份不能访问此任务");
}
export function callerHeaders(caller: AppCheckCaller): Record<string, string> {
  const value = requireCaller(caller);
  const encoded = btoa(String.fromCharCode(...new TextEncoder().encode(value.userId)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return { "X-XA-Dev-User": encoded, "X-XA-Dev-Role": value.role };
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
export class AppCheckConfigurationUnconfirmed extends Error {
  constructor() {
    super("配置保存未确认，编辑内容已保留。请重新读取核对，不会自动重试保存。");
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
  signal?: AbortSignal,
  caller?: AppCheckCaller
): Promise<unknown> {
  const response = await fetch(`/api/v1/app-checks${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      "Content-Type": "application/json",
      ...(caller ? callerHeaders(caller) : {})
    },
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
  async loadConfiguration(caller: AppCheckCaller) {
    return configurationViewSchema.parse(
      await request("/configuration", undefined, undefined, caller)
    );
  }
  async saveConfiguration(
    caller: AppCheckCaller,
    expectedRevision: number,
    configuration: AppCheckSettings
  ) {
    try {
      const response = await requireResponse(
        await fetch("/api/v1/app-checks/configuration", {
          method: "PUT",
          headers: {
            "Content-Type": "application/json",
            ...(caller ? callerHeaders(caller) : {})
          },
          body: JSON.stringify({ expectedRevision, configuration })
        })
      );
      return configurationViewSchema.parse(await response.json());
    } catch (error) {
      if (
        error instanceof AppCheckApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        error.status !== 408
      )
        throw error;
      throw new AppCheckConfigurationUnconfirmed();
    }
  }
  async listTasks(caller: AppCheckCaller) {
    return listSchema.parse(
      await request("/tasks?limit=100", undefined, undefined, caller)
    );
  }
  async loadTask(caller: AppCheckCaller, taskId: string) {
    const value = detailSchema.parse(
      await request(
        `/tasks/${encodeURIComponent(taskId)}`,
        undefined,
        undefined,
        caller
      )
    );
    if (value.task.taskId !== taskId) throw new Error("返回的任务身份不符");
    return value;
  }
  async createTask(caller: AppCheckCaller, input: CreateCheckTask) {
    try {
      if ("userId" in input || "role" in input)
        throw new AppCheckApiError(400, "Unknown application check fields");
      const requestBody = {
        requestId: input.requestId,
        appId: input.appId,
        country: input.country,
        simulation: input.simulation
      };
      return z
        .object({ taskId: z.string().min(1) })
        .parse(await request("/tasks", requestBody, undefined, caller));
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
    caller: AppCheckCaller,
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
            headers: {
              "Content-Type": "text/plain;charset=UTF-8",
              ...callerHeaders(caller)
            },
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
  async loadImport(caller: AppCheckCaller, taskId: string) {
    requireCaller(caller);
    const saved = this.imports.get(taskId);
    return saved ? structuredClone(saved) : undefined;
  }
  async approveTask(caller: AppCheckCaller, taskId: string, expectedCount: number) {
    await request(
      `/tasks/${encodeURIComponent(taskId)}/approve`,
      expectedCount,
      undefined,
      caller
    );
  }
  async closeTask(caller: AppCheckCaller, taskId: string) {
    await request(`/tasks/${encodeURIComponent(taskId)}/close`, {}, undefined, caller);
  }
  async exportTask(caller: AppCheckCaller, taskId: string, filter: ExportFilter) {
    const response = await requireResponse(
      await fetch(
        `/api/v1/app-checks/tasks/${encodeURIComponent(taskId)}/results:export?filter=${filter}`,
        { method: "POST", headers: callerHeaders(caller) }
      )
    );
    const header = response.headers.get("X-Export-Count");
    if (header === null) throw new Error("导出数量未确认");
    const count = z.number().int().nonnegative().parse(Number(header));
    return { blob: await response.blob(), fileName: `${taskId}-${filter}.csv`, count };
  }
}
