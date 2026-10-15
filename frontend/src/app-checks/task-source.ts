import {
  createProjectSource,
  countFrom,
  type ProjectTask
} from "@/task-management/projects";
import { HttpFiniteTaskClient } from "@/task-management/http-client";
import {
  type TaskCaller,
  TaskApiError,
  requireCaller,
  callerHeaders
} from "@/task-caller";
import { z } from "zod";
import { configurationViewSchema, type AppCheckConfigurationView } from "./settings";
import type { ImportSnapshot, ImportReceipt } from "./import-model";
import type { PreviewState, ExportFilter, TaskActivity } from "./workbench";
import {
  catalogSchema,
  taskSchema,
  resultSchema,
  listSchema,
  type Catalog,
  type CheckDetail,
  type CheckTask,
  type CreateCheckTask
} from "./model";

export interface AppCheckTaskSource {
  readonly mode: "api" | "mock";
  catalog(signal?: AbortSignal): Promise<Catalog>;
  loadConfiguration(caller: TaskCaller): Promise<AppCheckConfigurationView>;
  saveConfiguration(
    caller: TaskCaller,
    expectedFingerprint: string,
    configuration: Record<string, unknown>
  ): Promise<AppCheckConfigurationView>;
  simulateConfigurationRestart?(caller: TaskCaller): Promise<void>;
  listTasks(
    caller: TaskCaller,
    userId?: string
  ): Promise<{ tasks: CheckTask[]; truncated: boolean }>;
  loadTask(caller: TaskCaller, taskId: string): Promise<CheckDetail>;
  createTask(caller: TaskCaller, input: CreateCheckTask): Promise<{ taskId: string }>;
  importNumbers?(
    caller: TaskCaller,
    taskId: string,
    file: Blob,
    summary?: ImportSnapshot
  ): Promise<ImportReceipt>;
  // Explicit Mock interactions for the design preview; the API capabilities remain unchanged.
  approveTask?(
    caller: TaskCaller,
    taskId: string,
    expectedCount: number
  ): Promise<void>;
  closeTask?(caller: TaskCaller, taskId: string): Promise<void>;
  advanceTask?(caller: TaskCaller, taskId: string, late?: boolean): Promise<void>;
  completeTask?(caller: TaskCaller, taskId: string): Promise<void>;
  previewState?(caller: TaskCaller, taskId: string): PreviewState;
  loadImport?(caller: TaskCaller, taskId: string): Promise<ImportSnapshot | undefined>;
  loadActivity?(caller: TaskCaller, taskId: string): Promise<TaskActivity[]>;
  exportCounts?(
    caller: TaskCaller,
    taskId: string
  ): Promise<Record<ExportFilter, number>>;
  exportTask?(
    caller: TaskCaller,
    taskId: string,
    filter: "all" | "registered" | "unregistered"
  ): Promise<{ blob: Blob; fileName: string; count: number }>;
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
    throw new TaskApiError(
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
  caller?: TaskCaller
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
  private readonly taskClient = new HttpFiniteTaskClient("/api");
  private readonly imports = new Map<string, ImportSnapshot>();
  async catalog(signal?: AbortSignal) {
    return catalogSchema.parse(await request("/catalog", undefined, signal));
  }
  async loadConfiguration(caller: TaskCaller) {
    return configurationViewSchema.parse(
      await request("/configuration", undefined, undefined, caller)
    );
  }
  async saveConfiguration(
    caller: TaskCaller,
    expectedFingerprint: string,
    configuration: Record<string, unknown>
  ) {
    try {
      const response = await requireResponse(
        await fetch("/api/v1/app-checks/configuration", {
          method: "PUT",
          headers: {
            "Content-Type": "application/json",
            ...(caller ? callerHeaders(caller) : {})
          },
          body: JSON.stringify({ expectedFingerprint, configuration })
        })
      );
      return configurationViewSchema.parse(await response.json());
    } catch (error) {
      if (
        error instanceof TaskApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        error.status !== 408
      )
        throw error;
      throw new AppCheckConfigurationUnconfirmed();
    }
  }
  async listTasks(caller: TaskCaller, userId?: string) {
    const source = createProjectSource("/api", "api", requireCaller(caller));
    const page = await source.tasks("app-checks", undefined, userId);
    return listSchema.parse({
      tasks: page.tasks.map(checkTaskView),
      truncated: page.truncated
    });
  }
  async loadTask(caller: TaskCaller, taskId: string): Promise<CheckDetail> {
    caller = requireCaller(caller);
    const entry = await createProjectSource("/api", "api", caller).task(
      "app-checks",
      taskId
    );
    const preview = z
      .object({
        taskId: z.literal(taskId),
        results: z.array(resultSchema).max(100),
        truncated: z.boolean()
      })
      .strict()
      .parse(
        await request(
          `/tasks/${encodeURIComponent(taskId)}/results`,
          undefined,
          undefined,
          caller
        )
      );
    return {
      task: checkTaskView(entry),
      results: preview.results,
      resultsTruncated: preview.truncated
    };
  }
  async createTask(caller: TaskCaller, input: CreateCheckTask) {
    try {
      if ("userId" in input || "role" in input || "simulation" in input)
        throw new TaskApiError(400, "Unknown application check fields");
      const requestBody = {
        requestId: input.requestId,
        appId: input.appId,
        country: input.country
      };
      return z
        .object({ taskId: z.string().min(1) })
        .parse(await request("/tasks", requestBody, undefined, caller));
    } catch (error) {
      if (
        error instanceof TaskApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        error.status !== 408
      )
        throw error;
      throw new AppCheckCreationUnconfirmed(
        error instanceof TaskApiError ? error.taskId : undefined
      );
    }
  }
  async importNumbers(
    caller: TaskCaller,
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
      if (summary)
        this.imports.set(
          caller.userId + "/" + taskId,
          structuredClone({ ...summary, receipt })
        );
      return receipt;
    } catch (error) {
      if (error instanceof TaskApiError && error.status < 500 && error.status !== 408)
        throw error;
      throw new AppCheckImportUnconfirmed(
        taskId,
        error instanceof Error ? error.message : undefined
      );
    }
  }
  async loadImport(caller: TaskCaller, taskId: string) {
    requireCaller(caller);
    const saved = this.imports.get(caller.userId + "/" + taskId);
    return saved ? structuredClone(saved) : undefined;
  }
  async approveTask(caller: TaskCaller, taskId: string, expectedCount: number) {
    await this.taskClient.approveTask(requireCaller(caller), taskId, expectedCount);
  }
  async closeTask(caller: TaskCaller, taskId: string) {
    await this.taskClient.closeTask(requireCaller(caller), taskId);
  }
  async exportTask(caller: TaskCaller, taskId: string, filter: ExportFilter) {
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

function checkTaskView(entry: ProjectTask): CheckTask {
  const metadata = entry.task?.metadata ?? {};
  const row: Record<string, unknown> = {
    taskId: entry.taskId,
    createdAtMillis: entry.createdAtMillis,
    workerGroupId: entry.task?.workerGroupId ?? null,
    managed: entry.task?.idleDisposition === "PARK_WHEN_IDLE",
    state: entry.scoreBand,
    retention: entry.retention,
    ...(entry.task?.name ? { name: entry.task.name } : {}),
    ...(metadata.userId ? { userId: metadata.userId } : {})
  };
  if (metadata["server.inputOwner"] === "app-checks") {
    for (const key of [
      "appId",
      "country",
      "salt",
      "saltDate",
      "inputVersion",
      "contractId",
      "eventCode"
    ])
      if (metadata[key] !== undefined) row[key] = metadata[key];
    row.refill = entry.task?.refill;
    for (const key of ["contractParameters", "workerSelector"]) {
      if (metadata[key]) {
        try {
          row[key] = JSON.parse(metadata[key]);
        } catch {
          row.configurationError = "任务执行配置无法解析";
        }
      }
    }
    if (entry.stats)
      Object.assign(row, {
        totalCount: entry.stats.total,
        activeCount: countFrom(entry.stats, 1),
        failedCount: countFrom(entry.stats, 5),
        succeededCount: countFrom(entry.stats, 6, 9)
      });
  }
  return taskSchema.parse(row);
}
