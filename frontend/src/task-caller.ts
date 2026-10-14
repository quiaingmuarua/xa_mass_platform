import { normalizeTaskUserId } from "@/task-user-id";
import {
  can,
  canAccessTask,
  isRole,
  taskScope,
  type Permission,
  type AccessSubject
} from "@/access-policy";

export type TaskCaller = AccessSubject;

export class TaskApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly taskId?: string
  ) {
    super(status === 403 ? `${message}，请刷新页面或退出后重新登录。` : message);
  }
}

export function requireCaller(caller: TaskCaller): TaskCaller {
  if (!caller || typeof caller.userId !== "string" || !isRole(caller.role))
    throw new TaskApiError(400, "开发调用方无效");
  try {
    return Object.freeze({
      userId: normalizeTaskUserId(caller.userId),
      role: caller.role,
      ...(caller.access ? { access: caller.access } : {})
    });
  } catch (error) {
    throw new TaskApiError(400, (error as Error).message);
  }
}

export function callerHeaders(caller: TaskCaller): Record<string, string> {
  const value = requireCaller(caller);
  const encoded = btoa(String.fromCharCode(...new TextEncoder().encode(value.userId)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return { "X-XA-Dev-User": encoded, "X-XA-Dev-Role": value.role };
}

export function requireCallerPermission(caller: TaskCaller, permission: Permission) {
  if (!can(requireCaller(caller), permission))
    throw new TaskApiError(403, "当前用户没有此操作权限");
}

export function requireTaskCaller(caller: TaskCaller, task: { userId?: string }) {
  const value = requireCaller(caller);
  if (!canAccessTask(value, task))
    throw new TaskApiError(403, "当前开发身份不能访问此任务");
}

export function selectTaskUser(
  caller: TaskCaller,
  requested?: string
): string | undefined {
  const actor = requireCaller(caller);
  const selected = requested === undefined ? undefined : normalizeTaskUserId(requested);
  if (taskScope(actor) === "own") {
    if (selected !== undefined) requireTaskCaller(actor, { userId: selected });
    return actor.userId;
  }
  return selected;
}
