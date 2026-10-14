import type { MockUserDirectorySource } from "@/user-directory-source";
import {
  type TaskCaller,
  TaskApiError,
  callerHeaders,
  requireCaller,
  selectTaskUser,
  requireTaskCaller
} from "@/task-caller";
import { z } from "zod";
import { taskViewSchema, workerGroupViewSchema } from "@/runtime-viewer/schemas";
import { MOCK_TASK_PREVIEW, MOCK_WORKER_GROUPS } from "@/runtime-viewer/mock-data";
import { rpcTaskId } from "@/task-workbench/rpc-task";
import { compareTaskIds, taskUserScore } from "@/task-user-id";

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
    workerGroupIds: z.array(z.string().min(1))
  })
  .strict();
export type Project = z.infer<typeof projectSchema>;
export const projectTaskSchema = z
  .object({
    taskId: z.string().min(1),
    createdAtMillis: z.number().int().nonnegative().nullable(),
    task: taskViewSchema.nullable(),
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
      .object({ pools: z.array(z.string()), functions: z.array(z.string()) })
      .strict()
  })
  .strict()
  .refine(
    (row) =>
      row.workerGroup === null || row.workerGroup.workerGroupId === row.workerGroupId,
    "Group identity differs"
  );
export type ProjectGroup = z.infer<typeof groupSchema>;

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
    return mockProjectSource(caller, directory);
  }
  const get = async (
    suffix: string,
    signal?: AbortSignal,
    body?: unknown,
    actor = requireCaller(caller)
  ): Promise<unknown> => {
    const response = await fetch(baseUrl.replace(/\/$/, "") + "/v1/projects" + suffix, {
      signal,
      method: body === undefined ? "GET" : "POST",
      headers: { "Content-Type": "application/json", ...callerHeaders(actor) },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw new ProjectRequestError(
        response.status,
        typeof body?.code === "number" ? body.code : undefined
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
    async groups(project, signal) {
      return z
        .array(groupSchema)
        .parse(await get(path(project) + "/worker-groups", signal));
    }
  };
}
function mockProjectSource(
  caller: TaskCaller,
  directory: MockUserDirectorySource
): ProjectSource {
  const groups: ProjectGroup[] = MOCK_WORKER_GROUPS.map((workerGroup) => ({
    workerGroupId: workerGroup.workerGroupId,
    workerGroup,
    matching: { pools: ["any"], functions: ["worker.any", "workerId"] }
  }));
  const agentGroups: ProjectGroup[] = [
    "detect-env",
    "instagram",
    "rcs",
    "telegram"
  ].map((name) => {
    const workerGroupId = `agentforge-${name}-workers`;
    return {
      workerGroupId,
      workerGroup:
        name === "detect-env"
          ? {
              workerGroupId,
              attributes: { source: "mock" },
              eventCodes: ["extension.worker.detect-env.scenario-catalog"]
            }
          : null,
      matching: { pools: [], functions: ["workerId"] }
    };
  });
  const projects: Project[] = [
    {
      projectId: "default",
      workerGroupIds: groups.map((g) => g.workerGroupId)
    },
    {
      projectId: "agentforge",
      workerGroupIds: agentGroups.map((g) => g.workerGroupId)
    },
    { projectId: "app-checks", workerGroupIds: ["app-a-sim", "app-b-sim"] },
    { projectId: "messages", workerGroupIds: ["demo-sim", "app-a-sim", "app-b-sim"] }
  ];
  const tasks: ProjectTask[] = MOCK_TASK_PREVIEW.entries.map((row) => ({
    taskId: row.taskId,
    task: row.task,
    scoreBand: row.scoreBand,
    createdAtMillis: row.task?.metadata["server.createdAtMillis"]
      ? Number(row.task.metadata["server.createdAtMillis"])
      : null
  }));
  for (const group of groups) {
    const taskId = rpcTaskId("default", "demo", group.workerGroupId);
    if (!tasks.some((row) => row.taskId === taskId))
      tasks.push({
        taskId,
        createdAtMillis: Date.parse(MOCK_TASK_PREVIEW.generatedAt),
        scoreBand: "running_visible",
        task: {
          taskId,
          projectId: "default",
          workerGroupId: group.workerGroupId,
          idleDisposition: "PARK_WHEN_IDLE",
          name: null,
          metadata: {
            userId: "demo",
            "server.projectId": "default",
            "server.createdAtMillis": String(Date.parse(MOCK_TASK_PREVIEW.generatedAt))
          },
          config: { priority: "0", maxRetryTimes: "3" },
          refill: [{ poolName: "any", target: {}, count: 100 }]
        }
      });
  }
  for (const managed of [true, false]) {
    const groupId = agentGroups[0]!.workerGroupId;
    const taskId = managed
      ? rpcTaskId("agentforge", "demo", groupId)
      : "mock-agentforge-finite";
    tasks.push({
      taskId,
      createdAtMillis: Date.parse(MOCK_TASK_PREVIEW.generatedAt),
      scoreBand: managed ? "running_visible" : "pre_review",
      task: {
        taskId,
        projectId: "agentforge",
        workerGroupId: groupId,
        idleDisposition: managed ? "PARK_WHEN_IDLE" : "CLOSE_WHEN_IDLE",
        name: managed ? "AgentForge RPC 示例" : "AgentForge 有限任务示例",
        metadata: {
          userId: "demo",
          "server.projectId": "agentforge",
          "server.createdAtMillis": String(Date.parse(MOCK_TASK_PREVIEW.generatedAt))
        },
        config: { priority: managed ? "0" : "50", maxRetryTimes: "3" },
        refill: []
      }
    });
  }
  const requireProject = (project: string) => {
    if (!projects.some((p) => p.projectId === project))
      throw new Error("Project 不可用");
  };
  return {
    async list() {
      directory.resolveCaller(caller);
      return structuredClone(projects);
    },
    async tasks(project, _signal, userId) {
      const actor = directory.resolveCaller(caller, project);
      const owner = selectTaskUser(actor, userId);
      requireProject(project);
      const owned = tasks.filter(
        (row) =>
          row.task?.projectId === project &&
          (!owner || row.task.metadata.userId === owner)
      );
      const batch = [...owned]
        .sort(
          (a, b) =>
            taskUserScore(a.task?.metadata.userId) -
              taskUserScore(b.task?.metadata.userId) ||
            compareTaskIds(a.taskId, b.taskId)
        )
        .slice(0, 100);
      batch.sort(
        (a, b) =>
          (b.createdAtMillis ?? -1) - (a.createdAtMillis ?? -1) ||
          compareTaskIds(a.taskId, b.taskId)
      );
      return structuredClone({
        projectId: project,
        truncated: owned.length > 100,
        tasks: batch
      });
    },
    async task(project, taskId) {
      const actor = directory.resolveCaller(caller, project);
      requireProject(project);
      const task = tasks.find(
        (t) => t.taskId === taskId && t.task?.projectId === project
      );
      if (!task) throw new ProjectRequestError(400, 12002);
      requireTaskCaller(actor, {
        userId: task.task?.metadata.userId
      });
      return structuredClone(task);
    },
    async stats(project, taskIds) {
      requireProject(project);
      const actor = directory.resolveCaller(caller, project);
      for (const id of taskIds) {
        const task = tasks.find(
          (row) => row.taskId === id && row.task?.projectId === project
        );
        if (!task) throw new ProjectRequestError(400, 12002);
        requireTaskCaller(actor, { userId: task.task?.metadata.userId });
      }
      return Object.fromEntries(
        taskIds.map((id) => [
          id,
          {
            total: 0,
            countsByTag: Object.fromEntries(
              Array.from({ length: 9 }, (_, i) => [String(i + 1), 0])
            )
          }
        ])
      );
    },
    async groups(project) {
      directory.resolveCaller(caller);
      requireProject(project);
      const known = [...groups, ...agentGroups];
      return structuredClone(
        projects
          .find((entry) => entry.projectId === project)!
          .workerGroupIds.map(
            (workerGroupId) =>
              known.find((entry) => entry.workerGroupId === workerGroupId) ?? {
                workerGroupId,
                workerGroup: null,
                matching: { pools: [], functions: [] }
              }
          )
      );
    }
  };
}
