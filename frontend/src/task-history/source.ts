import { z } from "zod";
import { taskViewSchema, jsonValueSchema } from "@/runtime-viewer/schemas";
import {
  taskStatsSchema,
  rawResultPreviewSchema,
  createProjectSource,
  type ProjectTask
} from "@/task-management/projects";
import {
  callerHeaders,
  requireTaskCaller,
  selectTaskUser,
  TaskApiError,
  type TaskCaller
} from "@/task-caller";
import type { MockUserDirectorySource } from "@/user-directory-source";
import type { TaskRetention } from "@/task-management/retention";
import { resultSchema as checkResultSchema } from "@/app-checks/model";

const count = z.number().int().nonnegative();
export const historyEntrySchema = z
  .strictObject({
    historyId: z.string().min(1),
    taskId: z.string().min(1),
    createdAtMillis: count.nullable(),
    task: taskViewSchema,
    scoreBand: z.literal("terminal"),
    terminatedAtMillis: count,
    frozenAtMillis: count,
    publishedAtMillis: count,
    expiresAtMillis: count,
    counts: taskStatsSchema,
    itemRows: count,
    resultRows: count
  })
  .refine((row) => row.taskId === row.task.taskId, "历史任务身份不符");
export type HistoryEntry = z.infer<typeof historyEntrySchema>;
export type HistoryKind = "raw" | "app-checks" | "messages";
export type HistoryPage<T> = { entries: T[]; nextCursor: string | null };
const record = z.record(z.string(), jsonValueSchema);
const itemSchema = z.strictObject({
  messageId: z.string(),
  item: record.nullable(),
  state: record.nullable(),
  result: record.nullable()
});
export type HistoryItem = z.infer<typeof itemSchema>;
export type HistoryPreview = { results: Record<string, unknown>[]; truncated: boolean };
export type HistoryFilters = { projectId?: string; userId?: string; taskId?: string };
export interface TaskHistorySource {
  readonly mode: "api" | "mock";
  list(
    caller: TaskCaller,
    filters: HistoryFilters,
    cursor?: string
  ): Promise<HistoryPage<HistoryEntry>>;
  get(caller: TaskCaller, id: string): Promise<HistoryEntry>;
  runtimeTask(
    caller: TaskCaller,
    projectId: string,
    taskId: string
  ): Promise<ProjectTask>;
  items(
    caller: TaskCaller,
    id: string,
    cursor?: string
  ): Promise<HistoryPage<HistoryItem>>;
  preview(caller: TaskCaller, id: string, kind: HistoryKind): Promise<HistoryPreview>;
  export(
    caller: TaskCaller,
    id: string,
    kind: HistoryKind,
    filter: string
  ): Promise<{ blob: Blob; fileName: string }>;
}

export class ApiTaskHistorySource implements TaskHistorySource {
  readonly mode = "api";
  constructor(private readonly base = "/api") {}
  private async request(caller: TaskCaller, path: string, method = "GET") {
    const response = await fetch(`${this.base}/v1/storage${path}`, {
      method,
      headers: callerHeaders(caller)
    });
    if (!response.ok)
      throw new TaskApiError(
        response.status,
        `历史读取失败 (${response.status})，旧快照已保留`
      );
    return response;
  }
  async list(caller: TaskCaller, filters: HistoryFilters, cursor?: string) {
    const query = new URLSearchParams({ limit: "20" });
    for (const [name, value] of Object.entries(filters))
      if (value) query.set(name, value);
    if (cursor) query.set("cursor", cursor);
    return z
      .strictObject({
        entries: z.array(historyEntrySchema).max(20),
        nextCursor: z.string().nullable()
      })
      .parse(await (await this.request(caller, `/tasks?${query}`)).json());
  }
  async get(caller: TaskCaller, id: string) {
    const value = historyEntrySchema.parse(
      await (await this.request(caller, `/tasks/${encodeURIComponent(id)}`)).json()
    );
    if (value.historyId !== id) throw new Error("历史身份不符");
    return value;
  }
  runtimeTask(caller: TaskCaller, projectId: string, taskId: string) {
    return createProjectSource(this.base, "api", caller).task(projectId, taskId);
  }
  async items(caller: TaskCaller, id: string, cursor?: string) {
    const query = new URLSearchParams({ limit: "20" });
    if (cursor) query.set("cursor", cursor);
    return z
      .strictObject({
        entries: z.array(itemSchema).max(20),
        nextCursor: z.string().nullable()
      })
      .parse(
        await (
          await this.request(caller, `/tasks/${encodeURIComponent(id)}/items?${query}`)
        ).json()
      );
  }
  async preview(
    caller: TaskCaller,
    id: string,
    kind: HistoryKind
  ): Promise<HistoryPreview> {
    const prefix = kind === "raw" ? "" : `/${kind}`;
    const json = await (
      await this.request(caller, `${prefix}/tasks/${encodeURIComponent(id)}/results`)
    ).json();
    if (kind === "raw") return rawResultPreviewSchema.parse(json);
    if (kind === "app-checks")
      return z
        .object({
          taskId: z.string(),
          results: z.array(checkResultSchema).max(100),
          truncated: z.boolean()
        })
        .parse(json);
    return z
      .object({
        taskId: z.string(),
        results: z.array(record).max(100),
        truncated: z.boolean()
      })
      .parse(json);
  }
  async export(caller: TaskCaller, id: string, kind: HistoryKind, filter: string) {
    const business = kind === "app-checks";
    const response = await this.request(
      caller,
      `${business ? "/app-checks" : ""}/tasks/${encodeURIComponent(id)}/results:export${business ? `?filter=${encodeURIComponent(filter)}` : ""}`,
      "POST"
    );
    return {
      blob: await response.blob(),
      fileName: `${id}-${filter}.${business ? "csv" : "jsonl"}`
    };
  }
}

type MockTask = {
  taskId: string;
  userId?: string;
  name?: string;
  workerGroupId: string | null;
  createdAtMillis: number | null;
  retention?: TaskRetention | null;
  totalCount?: number;
  sendTotal?: number;
};
type Snapshot = {
  entry: HistoryEntry;
  items: HistoryItem[];
  rows: Record<string, unknown>[];
  exports: Map<string, { blob: Blob; fileName: string }>;
};

/** Session-only design evidence. It never calls a service or implements a Worker algorithm. */
export class MockTaskHistorySource implements TaskHistorySource {
  readonly mode = "mock";
  private readonly snapshots = new Map<string, Snapshot>();
  constructor(
    private readonly directory: MockUserDirectorySource,
    private readonly now = Date.now
  ) {}
  capture(
    project: string,
    task: MockTask,
    rows: Record<string, unknown>[],
    exports = new Map<string, { blob: Blob; fileName: string }>(),
    inputs: HistoryItem[] = []
  ) {
    const retention = task.retention;
    if (
      !retention?.frozenAtMillis ||
      !retention.terminatedAtMillis ||
      !retention.historyExpiresAtMillis ||
      retention.historyExpiresAtMillis <= this.now()
    )
      return;
    const id = `history-mock-${project}-${task.taskId}-${retention.frozenAtMillis}`;
    retention.historyId = id;
    if (this.snapshots.has(id)) return;
    const groups = new Map<string, Record<string, unknown>[]>();
    for (const row of rows) {
      const key = String(row.messageId);
      groups.set(key, [...(groups.get(key) ?? []), row]);
    }
    const observed: HistoryItem[] = [...groups.entries()].map(
      ([messageId, members]) => ({
        messageId,
        item: {
          messageId,
          payload: { members: members as z.infer<typeof jsonValueSchema> },
          mock: true
        },
        state: { band: "terminal", mock: true },
        result: {
          status: members[0]?.resultStatus === "failed" ? "failed" : "succeeded",
          mock: true
        }
      })
    );
    const byId = new Map(inputs.map((row) => [row.messageId, row]));
    for (const row of observed)
      byId.set(row.messageId, {
        ...row,
        item: byId.get(row.messageId)?.item ?? row.item
      });
    const items = [...byId.values()];
    const entry = historyEntrySchema.parse({
      historyId: id,
      taskId: task.taskId,
      createdAtMillis: task.createdAtMillis,
      task: {
        taskId: task.taskId,
        projectId: project,
        workerGroupId: task.workerGroupId ?? "mock-group",
        idleDisposition: "CLOSE_WHEN_IDLE",
        name: task.name ?? null,
        refill: [],
        config: { priority: "1", maxRetryTimes: "3" },
        metadata: {
          "server.projectId": project,
          "server.createdAtMillis": String(task.createdAtMillis ?? 0),
          "server.inputOwner":
            project === "app-checks" || project === "messages" ? project : "raw",
          userId: task.userId ?? "1000",
          mock: "历史展示样例"
        }
      },
      scoreBand: "terminal",
      terminatedAtMillis: retention.terminatedAtMillis,
      frozenAtMillis: retention.frozenAtMillis,
      publishedAtMillis: this.now(),
      expiresAtMillis: retention.historyExpiresAtMillis,
      counts: {
        total: task.totalCount ?? task.sendTotal ?? items.length,
        countsByTag: {}
      },
      itemRows: items.length,
      resultRows: observed.length
    });
    this.snapshots.set(id, {
      entry,
      items: structuredClone(items),
      rows: structuredClone(rows),
      exports
    });
  }
  seedExamples() {
    for (const project of ["default", "app-checks", "messages", "retired-project"]) {
      const ended = this.now() - 8 * 86400000;
      this.capture(
        project,
        {
          taskId: `mock-history-${project}`,
          name: "已退出 Runtime 的历史样例",
          userId: "1001",
          workerGroupId: "demo-sim",
          createdAtMillis: ended - 60000,
          retention: {
            phase: "RELEASED",
            terminatedAtMillis: ended,
            frozenAtMillis: ended + 3600000,
            expiresAtMillis: ended + 7 * 86400000,
            historyExpiresAtMillis: ended + 30 * 86400000,
            historyId: null
          }
        },
        [
          {
            messageId: "example-1",
            seed: "a",
            matched: true,
            details: {
              md5: "0cc175b9c0f1b6a831c399e269772661",
              source: "Mock 固定样例"
            },
            recipientId: "+8613800000000",
            status: "SENT",
            resultStatus: "succeeded"
          }
        ]
      );
    }
  }
  private expire() {
    for (const [id, value] of this.snapshots)
      if (value.entry.expiresAtMillis <= this.now()) this.snapshots.delete(id);
  }
  private require(caller: TaskCaller, id: string) {
    this.expire();
    const value = this.snapshots.get(id);
    if (!value) throw new TaskApiError(400, "历史不存在或已到期");
    caller = this.directory.resolveCaller(caller, value.entry.task.projectId!);
    requireTaskCaller(caller, { userId: value.entry.task.metadata.userId });
    return value;
  }
  async list(caller: TaskCaller, filters: HistoryFilters, cursor?: string) {
    this.expire();
    caller = this.directory.resolveCaller(caller, filters.projectId);
    if (!filters.projectId && caller.role !== "admin")
      throw new TaskApiError(403, "跨 Project 历史仅管理员可读");
    const user = selectTaskUser(caller, filters.userId);
    const rows = [...this.snapshots.values()]
      .map((value) => value.entry)
      .filter(
        (row) =>
          (!filters.projectId || row.task.projectId === filters.projectId) &&
          (!user || row.task.metadata.userId === user) &&
          (!filters.taskId || row.taskId === filters.taskId)
      )
      .sort(
        (a, b) =>
          b.terminatedAtMillis - a.terminatedAtMillis ||
          b.historyId.localeCompare(a.historyId)
      );
    let remaining = rows;
    if (cursor) {
      let after: [number, string];
      try {
        after = z.tuple([count, z.string().min(1)]).parse(JSON.parse(cursor));
      } catch {
        throw new TaskApiError(400, "历史游标无效");
      }
      remaining = rows.filter(
        (row) =>
          row.terminatedAtMillis < after[0] ||
          (row.terminatedAtMillis === after[0] &&
            row.historyId.localeCompare(after[1]) < 0)
      );
    }
    const entries = remaining.slice(0, 20),
      last = entries.at(-1);
    return {
      entries: structuredClone(entries),
      nextCursor:
        remaining.length > 20 && last
          ? JSON.stringify([last.terminatedAtMillis, last.historyId])
          : null
    };
  }
  async get(caller: TaskCaller, id: string) {
    return structuredClone(this.require(caller, id).entry);
  }
  runtimeTask(caller: TaskCaller, projectId: string, taskId: string) {
    return createProjectSource("/api", "mock", caller, this.directory).task(
      projectId,
      taskId
    );
  }
  async items(caller: TaskCaller, id: string, cursor?: string) {
    const rows = this.require(caller, id).items;
    const start = cursor ? rows.findIndex((row) => row.messageId === cursor) + 1 : 0;
    const entries = rows.slice(start, start + 20);
    return {
      entries: structuredClone(entries),
      nextCursor: rows.length > start + 20 ? entries.at(-1)!.messageId : null
    };
  }
  async preview(
    caller: TaskCaller,
    id: string,
    kind: HistoryKind
  ): Promise<HistoryPreview> {
    const value = this.require(caller, id);
    const rows =
      kind === "raw"
        ? value.items.map((row) => ({
            messageId: row.messageId,
            item: row.item,
            result: row.result
          }))
        : value.rows;
    return {
      results: structuredClone(rows.slice(0, 100)),
      truncated: rows.length > 100
    };
  }
  async export(caller: TaskCaller, id: string, kind: HistoryKind, filter: string) {
    const value = this.require(caller, id);
    if (kind !== "app-checks")
      throw new TaskApiError(400, "Mock 不执行原始 JSONL 导出");
    const file = value.exports.get(filter);
    if (!file) throw new TaskApiError(400, "此 Mock 样例未保存 CSV");
    return file;
  }
}
