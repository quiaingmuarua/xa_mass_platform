import { z } from "zod";
import { type RoleDefinition, isRole, canUseConsole } from "@/access-policy";
import {
  developmentRoles,
  developmentUsers,
  type ConsoleUser
} from "@/development-users";
import { callerHeaders, TaskApiError, type TaskCaller } from "@/task-caller";
import { normalizeTaskUserId } from "@/task-user-id";

export interface UserDirectory {
  roles: readonly RoleDefinition[];
  users: readonly ConsoleUser[];
}
export interface UserDirectorySource {
  readonly initial?: UserDirectory;
  load(): Promise<UserDirectory>;
  save(
    caller: TaskCaller,
    user: ConsoleUser,
    existingUserId?: string
  ): Promise<ConsoleUser>;
}
const permission = z.enum([
  "console.view",
  "users.manage",
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
const user = z
  .object({
    userId: z.string().refine((value) => {
      try {
        return normalizeTaskUserId(value) === value;
      } catch {
        return false;
      }
    }),
    displayName: z.string().min(1).max(80),
    role: z.enum(["admin", "operator"]),
    projectIds: z.array(z.string().min(1)).max(100)
  })
  .strict();
export class ApiUserDirectorySource implements UserDirectorySource {
  private async request(path: string, options?: RequestInit) {
    const response = await fetch("/api/v1/" + path, {
      signal: AbortSignal.timeout(10000),
      ...options
    });
    if (!response.ok) {
      throw new TaskApiError(
        response.status,
        response.status === 403
          ? "当前用户没有用户管理权限"
          : `用户目录请求失败 (${response.status})，请检查 userId、角色及 Project，或刷新列表核对。`
      );
    }
    return response.json();
  }
  async load(): Promise<UserDirectory> {
    const [roles, users] = await Promise.all([
      this.request("roles"),
      this.request("users")
    ]);
    const definitions = z.array(role).length(2).parse(roles);
    if (new Set(definitions.map((entry) => entry.id)).size !== 2)
      throw new Error("角色目录不完整");
    const entries = z.array(user).parse(users);
    if (new Set(entries.map((entry) => entry.userId)).size !== entries.length)
      throw new Error("用户目录包含重复 userId");
    return { roles: definitions, users: entries };
  }
  async save(caller: TaskCaller, input: ConsoleUser, existingUserId?: string) {
    const { userId, ...fields } = input;
    try {
      return user.parse(
        await this.request(
          existingUserId === undefined
            ? "users"
            : "users/" + encodeURIComponent(existingUserId),
          {
            method: existingUserId === undefined ? "POST" : "PUT",
            headers: { "Content-Type": "application/json", ...callerHeaders(caller) },
            body: JSON.stringify(
              existingUserId === undefined ? { userId, ...fields } : fields
            )
          }
        )
      );
    } catch (error) {
      if (error instanceof TaskApiError) throw error;
      throw new Error("保存结果未确认，请刷新用户列表核对。", { cause: error });
    }
  }
}

/** App-lifetime simulation of the Server directory; no browser persistence. */
export class MockUserDirectorySource implements UserDirectorySource {
  private users: ConsoleUser[];
  readonly initial: UserDirectory;
  constructor(initialUsers = developmentUsers) {
    this.users = initialUsers.map((entry) => ({
      ...entry,
      projectIds: [...entry.projectIds]
    }));
    this.initial = { roles: developmentRoles, users: this.users };
  }
  async load(): Promise<UserDirectory> {
    return {
      roles: developmentRoles,
      users: this.users.map((entry) => ({
        ...entry,
        projectIds: [...entry.projectIds]
      }))
    };
  }
  async save(caller: TaskCaller, input: ConsoleUser, existingUserId?: string) {
    const actor = this.users.find(
      (entry) => entry.userId === caller.userId && entry.role === caller.role
    );
    if (!actor || !canUseConsole(actor, "users.manage"))
      throw new TaskApiError(403, "当前用户没有用户管理权限");
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
    const saved = {
      userId,
      displayName,
      role: input.role,
      projectIds: [...input.projectIds]
    };
    this.users =
      existingUserId === undefined
        ? [...this.users, saved]
        : this.users.map((entry) => (entry.userId === existingUserId ? saved : entry));
    return saved;
  }
}
