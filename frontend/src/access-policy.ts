export type Permission =
  | "runtime.view"
  | "workbench.use"
  | "messages.use"
  | "sms.use"
  | "app-checks.tasks"
  | "app-checks.configuration"
  | "app-checks.diagnostics"
  | "reference.view"
  | "demo.control";

interface RoleDefinition {
  readonly label: string;
  readonly permissions: readonly Permission[];
  readonly taskScope: "all" | "own";
  readonly home: string;
}

export const roles = {
  admin: {
    label: "管理员",
    permissions: [
      "runtime.view",
      "workbench.use",
      "messages.use",
      "sms.use",
      "app-checks.tasks",
      "app-checks.configuration",
      "app-checks.diagnostics",
      "reference.view",
      "demo.control"
    ],
    taskScope: "all",
    home: "/runtime/workers"
  },
  operator: {
    label: "普通用户",
    permissions: ["app-checks.tasks"],
    taskScope: "own",
    home: "/app-checks"
  }
} as const satisfies Record<string, RoleDefinition>;
export type RoleId = keyof typeof roles;
export interface AccessSubject {
  readonly userId: string;
  readonly role: RoleId;
}

export function isRole(value: unknown): value is RoleId {
  return typeof value === "string" && Object.hasOwn(roles, value);
}
export function can(subject: AccessSubject | null, permission: Permission): boolean {
  return (
    !!subject &&
    isRole(subject.role) &&
    (roles[subject.role].permissions as readonly Permission[]).includes(permission)
  );
}
export function taskScope(subject: AccessSubject): "all" | "own" {
  return roles[subject.role].taskScope;
}
export function canAccessTask(
  subject: AccessSubject,
  task: { userId?: string }
): boolean {
  return taskScope(subject) === "all" || subject.userId === task.userId;
}
