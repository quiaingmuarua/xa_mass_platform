import { z } from "zod";
import { taskViewSchema, workerGroupViewSchema } from "@/runtime-viewer/schemas";
import { MOCK_TASK_PREVIEW, MOCK_WORKER_GROUPS } from "@/runtime-viewer/mock-data";

export const projectSchema = z
  .object({
    projectId: z.string().min(1),
    managedTaskIds: z.record(z.string(), z.string().min(1))
  })
  .strict();
export type Project = z.infer<typeof projectSchema>;
export const projectTaskSchema = z
  .object({
    taskId: z.string().min(1),
    createdAtMillis: z.number().int().nonnegative(),
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
export type ProjectTask = z.infer<typeof projectTaskSchema>;
const tasksSchema = z
  .object({
    projectId: z.string().min(1),
    truncated: z.boolean(),
    tasks: z.array(projectTaskSchema).max(100)
  })
  .strict();
export type ProjectTasks = z.infer<typeof tasksSchema>;
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

export interface ProjectSource {
  list(signal?: AbortSignal): Promise<Project[]>;
  tasks(project: string, signal?: AbortSignal): Promise<ProjectTasks>;
  task(project: string, task: string, signal?: AbortSignal): Promise<ProjectTask>;
  groups(project: string, signal?: AbortSignal): Promise<ProjectGroup[]>;
}
export function createProjectSource(
  baseUrl: string,
  mode: "api" | "mock"
): ProjectSource {
  if (mode === "mock") return mockProjectSource();
  const get = async (suffix: string, signal?: AbortSignal): Promise<unknown> => {
    const response = await fetch(baseUrl.replace(/\/$/, "") + "/v1/projects" + suffix, {
      signal
    });
    if (!response.ok) throw new Error("Project 请求失败 (" + response.status + ")");
    return response.json();
  };
  const path = (project: string) => "/" + encodeURIComponent(project);
  return {
    async list(signal) {
      return z.array(projectSchema).parse(await get("", signal));
    },
    async tasks(project, signal) {
      const result = tasksSchema.parse(
        await get(path(project) + "/tasks?limit=100", signal)
      );
      if (
        result.projectId !== project ||
        result.tasks.some((row) => row.task && row.task.projectId !== project)
      )
        throw new Error("Project identity differs");
      return result;
    },
    async task(project, taskId, signal) {
      const result = projectTaskSchema.parse(
        await get(path(project) + "/tasks/" + encodeURIComponent(taskId), signal)
      );
      if (
        result.taskId !== taskId ||
        (result.task && result.task.projectId !== project)
      )
        throw new Error("Task identity differs");
      return result;
    },
    async groups(project, signal) {
      return z
        .array(groupSchema)
        .parse(await get(path(project) + "/worker-groups", signal));
    }
  };
}
function mockProjectSource(): ProjectSource {
  const groups: ProjectGroup[] = MOCK_WORKER_GROUPS.map((workerGroup) => ({
    workerGroupId: workerGroup.workerGroupId,
    workerGroup,
    matching: { pools: ["any"], functions: ["worker.any", "workerId"] }
  }));
  const projects: Project[] = [
    {
      projectId: "default",
      managedTaskIds: Object.fromEntries(
        groups.map((g) => [g.workerGroupId, "demo-managed-" + g.workerGroupId])
      )
    }
  ];
  const tasks: ProjectTask[] = MOCK_TASK_PREVIEW.entries.map((row, index) => ({
    taskId: row.taskId,
    task: row.task,
    scoreBand: row.scoreBand,
    createdAtMillis: Date.parse(MOCK_TASK_PREVIEW.generatedAt) - index * 1000
  }));
  for (const group of groups) {
    const taskId = projects[0]!.managedTaskIds[group.workerGroupId]!;
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
          metadata: {},
          config: { priority: "0", maxRetryTimes: "3" },
          refill: [{ poolName: "any", target: {}, count: 100 }]
        }
      });
  }
  const requireProject = (project: string) => {
    if (!projects.some((p) => p.projectId === project))
      throw new Error("Project 不可用");
  };
  return {
    async list() {
      return structuredClone(projects);
    },
    async tasks(project) {
      requireProject(project);
      return structuredClone({ projectId: project, truncated: false, tasks });
    },
    async task(project, taskId) {
      requireProject(project);
      const task = tasks.find((t) => t.taskId === taskId);
      if (!task) throw new Error("Task 不可用");
      return structuredClone(task);
    },
    async groups(project) {
      requireProject(project);
      return structuredClone(groups);
    }
  };
}
