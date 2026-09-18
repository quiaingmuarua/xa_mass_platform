import { z } from "zod";
import { taskViewSchema } from "@/runtime-viewer/schemas";

export const projectSchema = z.object({
  projectId: z.string().min(1),
  managedTaskIds: z.record(z.string(), z.string().min(1))
});
export type Project = z.infer<typeof projectSchema>;
const tasksSchema = z.object({
  projectId: z.string().min(1),
  truncated: z.boolean(),
  tasks: z.array(
    z.object({
      taskId: z.string().min(1),
      createdAtMillis: z.number().int().nonnegative(),
      task: taskViewSchema.nullable(),
      scoreBand: z
        .enum(["pre_review", "running-initial", "running_visible", "terminal"])
        .nullable()
    })
  )
});
export type ProjectTasks = z.infer<typeof tasksSchema>;

async function get(
  baseUrl: string,
  projectId: string,
  suffix: string,
  signal?: AbortSignal
): Promise<unknown> {
  const response = await fetch(
    `${baseUrl.replace(/\/$/, "")}/v1/projects/${encodeURIComponent(projectId)}${suffix}`,
    { signal }
  );
  if (!response.ok) throw new Error(`Project 请求失败 (${response.status})`);
  return response.json();
}
export async function loadProject(
  baseUrl: string,
  projectId: string,
  signal?: AbortSignal
): Promise<Project> {
  return projectSchema.parse(await get(baseUrl, projectId, "", signal));
}
export async function loadProjectTasks(
  baseUrl: string,
  projectId: string,
  signal?: AbortSignal
): Promise<ProjectTasks> {
  return tasksSchema.parse(await get(baseUrl, projectId, "/tasks?limit=100", signal));
}
