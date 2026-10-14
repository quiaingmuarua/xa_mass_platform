import { consoleIdentity } from "@/console-identity";
import { normalizeTaskUserId } from "@/task-user-id";

export interface TaskCaller {
  readonly userId: string;
  readonly role: "admin" | "operator";
}

export class TaskApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly taskId?: string
  ) {
    super(message);
  }
}

export function requireCaller(caller: TaskCaller): TaskCaller {
  if (
    !caller ||
    typeof caller.userId !== "string" ||
    !["admin", "operator"].includes(caller.role)
  )
    throw new TaskApiError(400, "开发调用方无效");
  try {
    return Object.freeze({
      userId: normalizeTaskUserId(caller.userId),
      role: caller.role
    });
  } catch (error) {
    throw new TaskApiError(400, (error as Error).message);
  }
}

export function captureTaskCaller(): TaskCaller {
  return requireCaller(consoleIdentity.current.value);
}

export function callerHeaders(caller: TaskCaller): Record<string, string> {
  const value = requireCaller(caller);
  const encoded = btoa(String.fromCharCode(...new TextEncoder().encode(value.userId)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return { "X-XA-Dev-User": encoded, "X-XA-Dev-Role": value.role };
}

export function requireAdminCaller(caller: TaskCaller) {
  if (requireCaller(caller).role !== "admin")
    throw new TaskApiError(403, "当前操作仅对管理员开发身份开放");
}

export function requireTaskCaller(caller: TaskCaller, task: { userId?: string }) {
  const value = requireCaller(caller);
  if (value.role !== "admin" && value.userId !== task.userId)
    throw new TaskApiError(403, "当前开发身份不能访问此任务");
}
