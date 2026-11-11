import { normalizeTaskUserId } from "@/task-user-id";
import {
  can,
  canAccessTask,
  isRole,
  taskScope,
  type Permission,
  type AccessSubject
} from "@/access-policy";
import { bearerHeaders, isAccessKey } from "@/access-session";

/**
 * A captured login. `accessKey` is non-enumerable so that copies, snapshots and serialization never carry it;
 * requests made for this caller authenticate as this login even after another login replaces the session.
 */
export type TaskCaller = AccessSubject & { readonly accessKey?: string };

export class TaskApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly taskId?: string
  ) {
    super(
      status === 403
        ? `${message}，请刷新页面或退出后重新登录。`
        : status === 401
          ? `${message}，请使用有效访问密钥重新登录。`
          : message
    );
  }
}

/** Attaches an access key without making it enumerable. */
export function withAccessKey<T extends object>(
  value: T,
  accessKey: string | undefined
): T {
  if (accessKey !== undefined)
    Object.defineProperty(value, "accessKey", { value: accessKey, enumerable: false });
  return value;
}

export function requireCaller(caller: TaskCaller): TaskCaller {
  if (!caller || typeof caller.userId !== "string" || !isRole(caller.role))
    throw new TaskApiError(400, "调用方无效");
  try {
    return Object.freeze(
      withAccessKey(
        {
          userId: normalizeTaskUserId(caller.userId),
          role: caller.role,
          ...(caller.access ? { access: caller.access } : {})
        },
        caller.accessKey
      )
    );
  } catch (error) {
    throw new TaskApiError(400, (error as Error).message);
  }
}

export function callerHeaders(caller: TaskCaller): Record<string, string> {
  const accessKey = requireCaller(caller).accessKey;
  if (!isAccessKey(accessKey)) throw new TaskApiError(401, "当前登录没有访问密钥");
  return bearerHeaders(accessKey);
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
