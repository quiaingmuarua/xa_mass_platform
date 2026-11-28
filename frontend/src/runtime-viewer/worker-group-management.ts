import axios from "axios";
import { z } from "zod";
import { callerHeaders, TaskApiError, type TaskCaller } from "@/task-caller";
import { MOCK_WORKER_GROUPS } from "./mock-data";
import { MOCK_PROJECTS } from "./mock-projects";
import type { RuntimeViewerConfig } from "./types";

const nonEmpty = z.string().min(1);

export const workerGroupWorkerCountsSchema = z.strictObject({
  total: z.number().int().nonnegative(),
  activeHot: z.number().int().nonnegative(),
  pausedActive: z.number().int().nonnegative(),
  recovery: z.number().int().nonnegative(),
  cold: z.number().int().nonnegative(),
  pausedOffline: z.number().int().nonnegative(),
  activeTotal: z.number().int().nonnegative()
});

export const workerGroupManagedViewSchema = z.strictObject({
  workerGroupId: nonEmpty,
  attributes: z.record(z.string(), z.unknown()),
  eventCodes: z.array(nonEmpty),
  source: z.enum(["startup_seeded", "startup_bound", "dynamic"]),
  editable: z.boolean(),
  deletableByConfig: z.boolean(),
  protectionReasons: z.array(nonEmpty),
  declaredProjects: z.array(nonEmpty),
  workerCounts: workerGroupWorkerCountsSchema
});

export const workerGroupDirectoryPageSchema = z.strictObject({
  nextCursor: z.string().regex(/^[0-9]+$/),
  unreadableCount: z.number().int().nonnegative(),
  groups: z.array(workerGroupManagedViewSchema),
  readAt: z.string().datetime({ offset: true })
});

export const workerGroupRegisterResponseSchema = z.strictObject({
  workerGroupId: nonEmpty,
  status: nonEmpty
});

export const workerGroupDeleteResponseSchema = z.strictObject({
  workerGroupId: nonEmpty,
  status: nonEmpty,
  removedWorkers: z.number().int().nonnegative()
});

export const workerDeleteResponseSchema = z.strictObject({
  workerGroupId: nonEmpty,
  workerId: nonEmpty,
  status: nonEmpty
});

export type WorkerGroupWorkerCounts = z.infer<typeof workerGroupWorkerCountsSchema>;
export type WorkerGroupManagedView = z.infer<typeof workerGroupManagedViewSchema>;
export type WorkerGroupDirectoryPage = z.infer<typeof workerGroupDirectoryPageSchema>;
export type WorkerGroupRegisterResponse = z.infer<
  typeof workerGroupRegisterResponseSchema
>;
export type WorkerGroupDeleteResponse = z.infer<typeof workerGroupDeleteResponseSchema>;
export type WorkerDeleteResponse = z.infer<typeof workerDeleteResponseSchema>;

export interface WorkerGroupManagementSource {
  list(
    caller: TaskCaller,
    cursor?: string,
    limit?: number,
    signal?: AbortSignal
  ): Promise<WorkerGroupDirectoryPage>;
  register(
    caller: TaskCaller,
    workerGroupId: string,
    attributes: Record<string, unknown>,
    eventCodes: string[]
  ): Promise<WorkerGroupRegisterResponse>;
  update(
    caller: TaskCaller,
    workerGroupId: string,
    attributes: Record<string, unknown>,
    eventCodes: string[]
  ): Promise<WorkerGroupRegisterResponse>;
  deleteGroup(
    caller: TaskCaller,
    workerGroupId: string
  ): Promise<WorkerGroupDeleteResponse>;
  deleteOfflineWorker(
    caller: TaskCaller,
    workerGroupId: string,
    workerId: string
  ): Promise<WorkerDeleteResponse>;
}

function toApiError(error: unknown, fallback: string): Error {
  if (error instanceof TaskApiError) return error;
  if (axios.isAxiosError(error)) {
    const status = error.response?.status ?? 503;
    const data = error.response?.data as { message?: unknown } | undefined;
    const message =
      typeof data?.message === "string" && data.message.trim().length > 0
        ? data.message
        : fallback;
    return new TaskApiError(status, message);
  }
  return error instanceof Error ? error : new Error(fallback);
}

export class ApiWorkerGroupManagementSource implements WorkerGroupManagementSource {
  private readonly client;

  constructor(apiBaseUrl: string) {
    this.client = axios.create({ baseURL: apiBaseUrl, timeout: 10000 });
  }

  async list(
    caller: TaskCaller,
    cursor = "0",
    limit = 100,
    signal?: AbortSignal
  ): Promise<WorkerGroupDirectoryPage> {
    try {
      const response = await this.client.get("/v1/worker-groups", {
        params: { cursor, limit },
        signal,
        headers: {
          "X-Request-Id": `worker-group-list-${crypto.randomUUID()}`,
          ...callerHeaders(caller)
        }
      });
      return workerGroupDirectoryPageSchema.parse(response.data);
    } catch (error) {
      throw toApiError(error, "读取 WorkerGroup 目录失败");
    }
  }

  async register(
    caller: TaskCaller,
    workerGroupId: string,
    attributes: Record<string, unknown>,
    eventCodes: string[]
  ): Promise<WorkerGroupRegisterResponse> {
    try {
      const response = await this.client.post(
        `/v1/worker-groups/${encodeURIComponent(workerGroupId)}:register`,
        { attributes, eventCodes },
        {
          headers: {
            "X-Request-Id": `worker-group-register-${crypto.randomUUID()}`,
            ...callerHeaders(caller)
          }
        }
      );
      return workerGroupRegisterResponseSchema.parse(response.data);
    } catch (error) {
      throw toApiError(error, "注册 WorkerGroup 失败");
    }
  }

  async update(
    caller: TaskCaller,
    workerGroupId: string,
    attributes: Record<string, unknown>,
    eventCodes: string[]
  ): Promise<WorkerGroupRegisterResponse> {
    try {
      const response = await this.client.put(
        `/v1/worker-groups/${encodeURIComponent(workerGroupId)}`,
        { attributes, eventCodes },
        {
          headers: {
            "X-Request-Id": `worker-group-update-${crypto.randomUUID()}`,
            ...callerHeaders(caller)
          }
        }
      );
      return workerGroupRegisterResponseSchema.parse(response.data);
    } catch (error) {
      throw toApiError(error, "更新 WorkerGroup 元数据失败");
    }
  }

  async deleteGroup(
    caller: TaskCaller,
    workerGroupId: string
  ): Promise<WorkerGroupDeleteResponse> {
    try {
      const response = await this.client.delete(
        `/v1/worker-groups/${encodeURIComponent(workerGroupId)}`,
        {
          headers: {
            "X-Request-Id": `worker-group-delete-${crypto.randomUUID()}`,
            ...callerHeaders(caller)
          }
        }
      );
      return workerGroupDeleteResponseSchema.parse(response.data);
    } catch (error) {
      throw toApiError(error, "移除 WorkerGroup 失败");
    }
  }

  async deleteOfflineWorker(
    caller: TaskCaller,
    workerGroupId: string,
    workerId: string
  ): Promise<WorkerDeleteResponse> {
    try {
      const response = await this.client.delete(
        `/v1/worker-groups/${encodeURIComponent(workerGroupId)}/workers/${encodeURIComponent(workerId)}`,
        {
          headers: {
            "X-Request-Id": `worker-delete-${crypto.randomUUID()}`,
            ...callerHeaders(caller)
          }
        }
      );
      return workerDeleteResponseSchema.parse(response.data);
    } catch (error) {
      throw toApiError(error, "移除离线 Worker 失败");
    }
  }
}

export class MockWorkerGroupManagementSource implements WorkerGroupManagementSource {
  private readonly groups = new Map<string, WorkerGroupManagedView>();
  private readonly removedWorkers = new Set<string>();

  constructor() {
    for (const seed of MOCK_WORKER_GROUPS) {
      const declaredProjects = MOCK_PROJECTS.filter(
        (p) =>
          p.workerGroupSource === "declared" &&
          p.workerGroupIds.includes(seed.workerGroupId)
      ).map((p) => p.projectId);
      const isSeeded = seed.workerGroupId === "android-demo-workers";
      const reasons = [
        ...(isSeeded ? ["host_seed"] : []),
        ...declaredProjects.map((p) => `project:${p}`),
        "matching"
      ];
      this.groups.set(seed.workerGroupId, {
        workerGroupId: seed.workerGroupId,
        attributes: structuredClone(seed.attributes),
        eventCodes: [...seed.eventCodes].sort(),
        source: isSeeded ? "startup_seeded" : "startup_bound",
        editable: true,
        deletableByConfig: true,
        protectionReasons: reasons,
        declaredProjects,
        workerCounts: {
          total: 2,
          activeHot: 1,
          pausedActive: 0,
          recovery: 0,
          cold: 1,
          pausedOffline: 0,
          activeTotal: 1
        }
      });
    }
    this.groups.set("ops-sandbox-workers", {
      workerGroupId: "ops-sandbox-workers",
      attributes: { env: "sandbox", owner: "ops" },
      eventCodes: ["extension.worker.sandbox.echo"],
      source: "dynamic",
      editable: true,
      deletableByConfig: true,
      protectionReasons: [],
      declaredProjects: [],
      workerCounts: {
        total: 1,
        activeHot: 0,
        pausedActive: 0,
        recovery: 0,
        cold: 1,
        pausedOffline: 0,
        activeTotal: 0
      }
    });
  }

  private assertAdmin(caller: TaskCaller) {
    if (caller.role !== "admin") {
      throw new TaskApiError(403, "仅管理员可管理 WorkerGroup 与离线 Worker");
    }
  }

  async list(
    caller: TaskCaller,
    cursor = "0",
    limit = 100
  ): Promise<WorkerGroupDirectoryPage> {
    this.assertAdmin(caller);
    const start = Number.parseInt(cursor, 10) || 0;
    const ordered = [...this.groups.values()].sort((a, b) =>
      a.workerGroupId.localeCompare(b.workerGroupId)
    );
    const slice = ordered
      .slice(start, start + limit)
      .map((item) => structuredClone(item));
    const nextCursor = start + limit < ordered.length ? String(start + limit) : "0";
    return {
      nextCursor,
      unreadableCount: 0,
      groups: slice,
      readAt: new Date().toISOString()
    };
  }

  async register(
    caller: TaskCaller,
    workerGroupId: string,
    attributes: Record<string, unknown>,
    eventCodes: string[]
  ): Promise<WorkerGroupRegisterResponse> {
    this.assertAdmin(caller);
    const id = workerGroupId.trim();
    if (!id) throw new TaskApiError(400, "workerGroupId 不能为空");
    const sortedEvents = [...new Set(eventCodes)].sort();
    const existing = this.groups.get(id);
    if (existing) {
      const same =
        JSON.stringify(existing.attributes) === JSON.stringify(attributes) &&
        JSON.stringify(existing.eventCodes) === JSON.stringify(sortedEvents);
      if (same) return { workerGroupId: id, status: "already_registered" };
      throw new TaskApiError(409, "WorkerGroup 已存在且描述不同");
    }
    this.groups.set(id, {
      workerGroupId: id,
      attributes: structuredClone(attributes),
      eventCodes: sortedEvents,
      source: "dynamic",
      editable: true,
      deletableByConfig: true,
      protectionReasons: [],
      declaredProjects: [],
      workerCounts: {
        total: 0,
        activeHot: 0,
        pausedActive: 0,
        recovery: 0,
        cold: 0,
        pausedOffline: 0,
        activeTotal: 0
      }
    });
    return { workerGroupId: id, status: "registered" };
  }

  async update(
    caller: TaskCaller,
    workerGroupId: string,
    attributes: Record<string, unknown>,
    eventCodes: string[]
  ): Promise<WorkerGroupRegisterResponse> {
    this.assertAdmin(caller);
    const existing = this.groups.get(workerGroupId);
    if (!existing) throw new TaskApiError(400, "WorkerGroup 不存在");
    const sortedEvents = [...new Set(eventCodes)].sort();
    const same =
      JSON.stringify(existing.attributes) === JSON.stringify(attributes) &&
      JSON.stringify(existing.eventCodes) === JSON.stringify(sortedEvents);
    if (same) return { workerGroupId, status: "unchanged" };
    existing.attributes = structuredClone(attributes);
    existing.eventCodes = sortedEvents;
    return { workerGroupId, status: "updated" };
  }

  async deleteGroup(
    caller: TaskCaller,
    workerGroupId: string
  ): Promise<WorkerGroupDeleteResponse> {
    this.assertAdmin(caller);
    if (!workerGroupId.trim()) throw new TaskApiError(400, "workerGroupId 不能为空");
    const existing = this.groups.get(workerGroupId);
    if (existing && existing.workerCounts.activeTotal > 0) {
      throw new TaskApiError(
        400,
        `WorkerGroup 仍有 ${existing.workerCounts.activeTotal} 个活跃 Worker，请先断开或迁移`
      );
    }
    const removedWorkers = existing?.workerCounts.total ?? 0;
    this.groups.delete(workerGroupId);
    return { workerGroupId, status: "deleted", removedWorkers };
  }

  async deleteOfflineWorker(
    caller: TaskCaller,
    workerGroupId: string,
    workerId: string
  ): Promise<WorkerDeleteResponse> {
    this.assertAdmin(caller);
    if (!workerGroupId.trim() || !workerId.trim()) {
      throw new TaskApiError(400, "workerGroupId 和 workerId 不能为空");
    }
    const key = `${workerGroupId}::${workerId}`;
    if (this.removedWorkers.has(key)) {
      return { workerGroupId, workerId, status: "deleted" };
    }
    this.removedWorkers.add(key);
    const group = this.groups.get(workerGroupId);
    if (group && group.workerCounts.cold > 0) {
      group.workerCounts.cold -= 1;
      group.workerCounts.total = Math.max(0, group.workerCounts.total - 1);
    }
    return { workerGroupId, workerId, status: "deleted" };
  }
}

export function createWorkerGroupManagementSource(
  config: RuntimeViewerConfig,
  mockSource?: MockWorkerGroupManagementSource
): WorkerGroupManagementSource {
  if (config.mode === "mock") {
    return mockSource ?? new MockWorkerGroupManagementSource();
  }
  return new ApiWorkerGroupManagementSource(config.apiBaseUrl);
}
