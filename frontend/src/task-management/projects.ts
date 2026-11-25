import type { MockUserDirectorySource } from "@/user-directory-source";
import { taskRetentionSchema } from "./retention";
import {
  type TaskCaller,
  TaskApiError,
  callerHeaders,
  requireCaller
} from "@/task-caller";
import { z } from "zod";
import { taskCallDebugOutcomeSchema } from "@/task-call-debug/schemas";
import {
  jsonValueSchema,
  taskViewSchema,
  workerGroupViewSchema
} from "@/runtime-viewer/schemas";
import { createMockProjectSource } from "./mock-project-source";

export class ProjectRequestError extends TaskApiError {
  constructor(
    public readonly status: number,
    public readonly code?: number
  ) {
    super(
      status,
      code === 12002 ? "Task 不存在。" : "Project 请求失败 (" + status + ")"
    );
  }
}

export const projectSchema = z
  .object({
    projectId: z.string().min(1),
    workerGroupIds: z.array(z.string().min(1)),
    workerGroupSource: z.enum(["registered", "declared"])
  })
  .strict();
export type Project = z.infer<typeof projectSchema>;

export const projectTaskSchema = z
  .object({
    taskId: z.string().min(1),
    createdAtMillis: z.number().int().nonnegative().nullable(),
    task: taskViewSchema.nullable(),
    retention: taskRetentionSchema.nullable(),
    scoreBand: z
      .enum(["pre_review", "running-initial", "running_visible", "terminal"])
      .nullable()
  })
  .strict()
  .refine(
    (row) => row.task === null || row.task.taskId === row.taskId,
    "Task identity differs"
  );
export type ProjectTask = z.infer<typeof projectTaskSchema> & { stats?: TaskStats };

export type TaskRow = Omit<ProjectTask, "createdAtMillis" | "retention"> & {
  createdAtMillis?: number | null;
  retention?: ProjectTask["retention"];
};

const tasksSchema = z
  .object({
    projectId: z.string().min(1),
    truncated: z.boolean(),
    tasks: z.array(projectTaskSchema).max(100)
  })
  .strict();
export type ProjectTasks = Omit<z.infer<typeof tasksSchema>, "tasks"> & {
  tasks: ProjectTask[];
};

const groupSchema = z
  .object({
    workerGroupId: z.string().min(1),
    workerGroup: workerGroupViewSchema.nullable(),
    matching: z
      .object({
        pools: z.array(z.string()),
        functions: z.array(
          z
            .object({ name: z.string().min(1), poolName: z.string().min(1).nullable() })
            .strict()
        )
      })
      .strict()
  })
  .strict()
  .refine(
    (row) =>
      row.workerGroup === null || row.workerGroup.workerGroupId === row.workerGroupId,
    "Group identity differs"
  );
export type ProjectGroup = z.infer<typeof groupSchema>;

export const rawResultPreviewSchema = z
  .object({
    results: z
      .array(
        z
          .object({
            messageId: z.string().min(1),
            item: z.record(z.string(), jsonValueSchema).nullable(),
            result: taskCallDebugOutcomeSchema
          })
          .strict()
      )
      .max(100),
    truncated: z.boolean()
  })
  .strict();
export type RawResultPreview = z.infer<typeof rawResultPreviewSchema>;

export const taskStatsSchema = z
  .object({
    total: z.number().int().nonnegative(),
    countsByTag: z.record(z.string(), z.number().int().nonnegative())
  })
  .strict();
export type TaskStats = z.infer<typeof taskStatsSchema>;

export function countFrom(stats: TaskStats, first: number, last = first) {
  let count = 0;
  for (let tag = first; tag <= last; tag++)
    count += stats.countsByTag[String(tag)] ?? 0;
  return count;
}

export interface ProjectSource {
  list(signal?: AbortSignal): Promise<Project[]>;
  tasks(project: string, signal?: AbortSignal, userId?: string): Promise<ProjectTasks>;
  stats(
    project: string,
    taskIds: string[],
    signal?: AbortSignal
  ): Promise<Record<string, TaskStats>>;
  task(project: string, task: string, signal?: AbortSignal): Promise<ProjectTask>;
  groups(project: string, signal?: AbortSignal): Promise<ProjectGroup[]>;
  results(
    project: string,
    task: string,
    signal?: AbortSignal
  ): Promise<RawResultPreview>;
}

export function createProjectSource(
  baseUrl: string,
  mode: "api" | "mock",
  caller: TaskCaller,
  directory?: MockUserDirectorySource
): ProjectSource {
  caller = requireCaller(caller);
  if (mode === "mock") {
    if (!directory) throw new Error("Mock user directory is required");
    return createMockProjectSource(caller, directory);
  }
  const get = async (
    suffix: string,
    signal?: AbortSignal,
    body?: unknown,
    actor = requireCaller(caller),
    resource = "/v1/projects"
  ): Promise<unknown> => {
    const response = await fetch(baseUrl.replace(/\/$/, "") + resource + suffix, {
      signal,
      method: body === undefined ? "GET" : "POST",
      headers: { "Content-Type": "application/json", ...callerHeaders(actor) },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      const errorBody = await response.json().catch(() => null);
      throw new ProjectRequestError(
        response.status,
        typeof errorBody?.code === "number" ? errorBody.code : undefined
      );
    }
    return response.json();
  };
  const path = (project: string) => "/" + encodeURIComponent(project);
  async function stats(
    project: string,
    taskIds: string[],
    signal?: AbortSignal,
    actor = requireCaller(caller)
  ): Promise<Record<string, TaskStats>> {
    if (!taskIds.length) return {};
    const value = z
      .record(z.string(), taskStatsSchema)
      .parse(await get(path(project) + "/tasks:stats", signal, taskIds, actor));
    if (
      Object.keys(value).length !== new Set(taskIds).size ||
      taskIds.some((id) => !value[id])
    )
      throw new Error("Task statistics identities differ");
    return value;
  }
  return {
    async list(signal) {
      return z.array(projectSchema).parse(await get("", signal));
    },
    async tasks(project, signal, userId) {
      const actor = requireCaller(caller);
      const result = tasksSchema.parse(
        await get(
          path(project) +
            "/tasks?limit=100" +
            (userId ? "&userId=" + encodeURIComponent(userId) : ""),
          signal,
          undefined,
          actor
        )
      );
      if (
        result.projectId !== project ||
        result.tasks.some((row) => row.task && row.task.projectId !== project)
      )
        throw new Error("Project identity differs");
      const counts = await stats(
        project,
        result.tasks.filter((row) => row.task).map((row) => row.taskId),
        signal,
        actor
      );
      return {
        ...result,
        tasks: result.tasks.map((row) => ({ ...row, stats: counts[row.taskId] }))
      };
    },
    async task(project, taskId, signal) {
      const actor = requireCaller(caller);
      const result = projectTaskSchema.parse(
        await get(
          path(project) + "/tasks/" + encodeURIComponent(taskId),
          signal,
          undefined,
          actor
        )
      );
      if (
        result.taskId !== taskId ||
        (result.task && result.task.projectId !== project)
      )
        throw new Error("Task identity differs");
      return {
        ...result,
        stats: (await stats(project, [taskId], signal, actor))[taskId]
      };
    },
    stats,
    async results(_project, taskId, signal) {
      return rawResultPreviewSchema.parse(
        await get(
          `/${encodeURIComponent(taskId)}/results:preview`,
          signal,
          undefined,
          requireCaller(caller),
          "/v1/tasks"
        )
      );
    },
    async groups(project, signal) {
      return z
        .array(groupSchema)
        .parse(await get(path(project) + "/worker-groups", signal));
    }
  };
}
