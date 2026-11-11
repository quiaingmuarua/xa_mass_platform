import { z } from "zod";
import { type RoleDefinition, type Permission, isRole } from "@/access-policy";
import { bearerHeaders, isAccessKey } from "@/access-session";
import {
  developmentAccessKey,
  developmentRoles,
  developmentUsers,
  type ConsoleUser
} from "@/development-users";
import {
  callerHeaders,
  requireCaller,
  TaskApiError,
  type TaskCaller
} from "@/task-caller";
import { normalizeTaskUserId } from "@/task-user-id";

export interface UserDirectory {
  roles: readonly RoleDefinition[];
  users: readonly ConsoleUser[];
}
export interface AuthenticatedUser {
  user: ConsoleUser;
  role: RoleDefinition;
}
export interface UserDirectorySource {
  /** Resolves the key's directory record; a rejected key is a 401 TaskApiError. */
  authenticate(accessKey: string): Promise<AuthenticatedUser>;
  load(accessKey: string): Promise<UserDirectory>;
  save(
    caller: TaskCaller,
    user: ConsoleUser,
    existingUserId?: string
  ): Promise<ConsoleUser>;
  /** Returns the only copy of the new key. */
  issueKey(caller: TaskCaller, userId: string): Promise<string>;
  clearKey(caller: TaskCaller, userId: string): Promise<ConsoleUser>;
}
const permission = z.enum([
  "console.view",
  "users.manage",
  "credits.view",
  "credits.manage",
  "tasks.retention.manage",
  "runtime.view",
  "workbench.use",
  "messages.use",
  "sms.use",
  "app-checks.tasks",
  "app-checks.configuration",
  "app-checks.diagnostics",
  "reference.view",
  "demo.control"
]);
const role = z
  .object({
    id: z.enum(["admin", "operator"]),
    label: z.string().min(1),
    permissions: z.array(permission),
    taskScope: z.enum(["all", "own"]),
    projectScope: z.enum(["all", "assigned"]),
    home: z
      .string()
      .startsWith("/")
      .refine((value) => !value.startsWith("//"))
  })
  .strict();
const userId = z.string().refine((value) => {
  try {
    return normalizeTaskUserId(value) === value;
  } catch {
    return false;
  }
});
const user = z
  .object({
    userId,
    displayName: z.string().min(1).max(80),
    role: z.enum(["admin", "operator"]),
    projectIds: z.array(z.string().min(1)).max(100),
    hasAccessKey: z.boolean()
  })
  .strict();
const me = z
  .object({
    userId,
    displayName: z.string().min(1).max(80),
    role,
    projectIds: z.array(z.string().min(1)).max(100)
  })
  .strict();
const issued = z.object({ userId, accessKey: z.string().refine(isAccessKey) }).strict();

export class ApiUserDirectorySource implements UserDirectorySource {
  private async request(path: string, options: RequestInit) {
    const response = await fetch("/api/v1/" + path, {
      signal: AbortSignal.timeout(10000),
      ...options
    });
    if (!response.ok) {
      throw new TaskApiError(
        response.status,
        response.status === 401
          ? "访问密钥无效或已被清除"
          : response.status === 403
            ? "当前用户没有用户管理权限"
            : response.status === 409
              ? "用户目录只读，或在 Server 外被修改；请重启 Server 后重试"
              : `用户目录请求失败 (${response.status})，请检查 userId、角色及 Project，或刷新列表核对。`
      );
    }
    return response.json();
  }
  async authenticate(accessKey: string): Promise<AuthenticatedUser> {
    if (!isAccessKey(accessKey)) throw new TaskApiError(401, "访问密钥格式无效");
    const value = me.parse(
      await this.request("me", { headers: bearerHeaders(accessKey) })
    );
    return {
      user: {
        userId: value.userId,
        displayName: value.displayName,
        role: value.role.id,
        projectIds: value.projectIds
      },
      role: value.role
    };
  }
  async load(accessKey: string): Promise<UserDirectory> {
    const headers = bearerHeaders(accessKey);
    const [roles, users] = await Promise.all([
      this.request("roles", { headers }),
      this.request("users", { headers })
    ]);
    const definitions = z.array(role).length(2).parse(roles);
    if (new Set(definitions.map((entry) => entry.id)).size !== 2)
      throw new Error("角色目录不完整");
    const entries = z.array(user).parse(users);
    if (new Set(entries.map((entry) => entry.userId)).size !== entries.length)
      throw new Error("用户目录包含重复 userId");
    return { roles: definitions, users: entries };
  }
  private async write<T>(
    caller: TaskCaller,
    path: string,
    method: string,
    schema: z.ZodType<T>,
    body?: unknown
  ) {
    try {
      return schema.parse(
        await this.request(path, {
          method,
          headers: {
            ...(body === undefined ? {} : { "Content-Type": "application/json" }),
            ...callerHeaders(caller)
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) })
        })
      );
    } catch (error) {
      if (error instanceof TaskApiError) throw error;
      throw new Error("保存结果未确认，请刷新用户列表核对。", { cause: error });
    }
  }
  async save(caller: TaskCaller, input: ConsoleUser, existingUserId?: string) {
    const { userId, displayName, role, projectIds } = input;
    return this.write(
      caller,
      existingUserId === undefined
        ? "users"
        : "users/" + encodeURIComponent(existingUserId),
      existingUserId === undefined ? "POST" : "PUT",
      user,
      existingUserId === undefined
        ? { userId, displayName, role, projectIds }
        : { displayName, role, projectIds }
    );
  }
  async issueKey(caller: TaskCaller, target: string) {
    const value = await this.write(
      caller,
      `users/${encodeURIComponent(target)}/access-key`,
      "POST",
      issued
    );
    return value.accessKey;
  }
  async clearKey(caller: TaskCaller, target: string) {
    return this.write(
      caller,
      `users/${encodeURIComponent(target)}/access-key`,
      "DELETE",
      user
    );
  }
}

function randomAccessKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return (
    "xak_" +
    btoa(String.fromCharCode(...bytes))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "")
  );
}

/** App-lifetime simulation of the Server directory; no browser persistence. */
export class MockUserDirectorySource implements UserDirectorySource {
  private users: ConsoleUser[];
  private readonly roles: readonly RoleDefinition[];
  /** userId -> current key. Built-in users start with their public development keys. */
  private readonly keys = new Map<string, string>();
  containsUser(userId: string) {
    return this.users.some((user) => user.userId === userId);
  }
  constructor(initialUsers = developmentUsers, roles = developmentRoles) {
    this.roles = roles.map((role) =>
      Object.freeze({ ...role, permissions: Object.freeze([...role.permissions]) })
    );
    for (const entry of initialUsers)
      this.keys.set(entry.userId, developmentAccessKey(entry.userId));
    this.users = initialUsers.map((entry) => this.freeze(entry));
  }
  private freeze(entry: ConsoleUser): ConsoleUser {
    return Object.freeze({
      userId: entry.userId,
      displayName: entry.displayName,
      role: entry.role,
      projectIds: Object.freeze([...entry.projectIds]),
      hasAccessKey: this.keys.has(entry.userId)
    });
  }
  resolveCaller(
    declared: TaskCaller,
    projectId?: string,
    permission?: Permission
  ): TaskCaller {
    const caller = requireCaller(declared);
    const user = this.users.find((user) => user.userId === caller.userId);
    const role = this.roles.find((role) => role.id === user?.role);
    if (!user || !role || user.role !== caller.role)
      throw new TaskApiError(403, "用户不存在或角色已在用户目录中变化");
    if (
      projectId &&
      role.projectScope === "assigned" &&
      !user.projectIds.includes(projectId)
    )
      throw new TaskApiError(403, "当前用户未获授权访问此 Project");
    if (permission && !role.permissions.includes(permission))
      throw new TaskApiError(403, "当前用户没有此操作权限");
    return Object.freeze({ userId: user.userId, role: user.role, access: role });
  }
  async authenticate(accessKey: string): Promise<AuthenticatedUser> {
    const userId = [...this.keys].find(([, key]) => key === accessKey)?.[0];
    const user = this.users.find((entry) => entry.userId === userId);
    const role = this.roles.find((entry) => entry.id === user?.role);
    if (!isAccessKey(accessKey) || !user || !role)
      throw new TaskApiError(401, "访问密钥无效或已被清除");
    return { user: { ...user, projectIds: [...user.projectIds] }, role };
  }
  /** Mock-internal read used by other Mock sources and fixtures; the API equivalent requires a key. */
  snapshot(): UserDirectory {
    return {
      roles: this.roles,
      users: this.users.map((entry) => ({
        ...entry,
        projectIds: [...entry.projectIds]
      }))
    };
  }
  async load(accessKey: string): Promise<UserDirectory> {
    await this.authenticate(accessKey);
    return this.snapshot();
  }
  private target(caller: TaskCaller, userId: string) {
    caller = this.resolveCaller(caller, undefined, "users.manage");
    if (!this.users.some((entry) => entry.userId === userId))
      throw new Error("用户不存在");
    if (userId === caller.userId) throw new Error("请使用其他管理员修改当前登录用户");
  }
  private replace(userId: string) {
    const saved = this.freeze(this.users.find((entry) => entry.userId === userId)!);
    this.users = this.users.map((entry) => (entry.userId === userId ? saved : entry));
    return saved;
  }
  async save(caller: TaskCaller, input: ConsoleUser, existingUserId?: string) {
    caller = this.resolveCaller(caller, undefined, "users.manage");
    const userId = normalizeTaskUserId(input.userId);
    const displayName = input.displayName.trim();
    if (!displayName || displayName.length > 80 || !isRole(input.role))
      throw new Error("用户名称或角色无效");
    if (
      existingUserId === undefined &&
      this.users.some((entry) => entry.userId === userId)
    )
      throw new Error("userId 已存在");
    if (
      existingUserId !== undefined &&
      !this.users.some((entry) => entry.userId === existingUserId)
    )
      throw new Error("用户不存在");
    if (existingUserId !== undefined && userId !== existingUserId)
      throw new Error("已创建用户的 userId 不能修改");
    if (existingUserId === caller.userId)
      throw new Error("请使用其他管理员修改当前登录用户");
    const saved = this.freeze({
      userId,
      displayName,
      role: input.role,
      projectIds: input.projectIds
    });
    this.users =
      existingUserId === undefined
        ? [...this.users, saved]
        : this.users.map((entry) => (entry.userId === existingUserId ? saved : entry));
    return saved;
  }
  async issueKey(caller: TaskCaller, userId: string) {
    this.target(caller, userId);
    const accessKey = randomAccessKey();
    this.keys.set(userId, accessKey);
    this.replace(userId);
    return accessKey;
  }
  async clearKey(caller: TaskCaller, userId: string) {
    this.target(caller, userId);
    this.keys.delete(userId);
    return this.replace(userId);
  }
}
