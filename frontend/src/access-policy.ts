import { shallowReactive } from "vue";

export type Permission =
  | "console.view"
  | "users.manage"
  | "runtime.view"
  | "workbench.use"
  | "messages.use"
  | "sms.use"
  | "app-checks.tasks"
  | "app-checks.configuration"
  | "app-checks.diagnostics"
  | "reference.view"
  | "demo.control";

export interface RoleDefinition {
  readonly id: RoleId;
  readonly label: string;
  readonly permissions: readonly Permission[];
  readonly taskScope: "all" | "own";
  readonly projectScope: "all" | "assigned";
  readonly home: string;
}

export type RoleId = "admin" | "operator";
export const roles = shallowReactive({} as Record<RoleId, RoleDefinition>);
export function installRoles(definitions: readonly RoleDefinition[]) {
  for (const definition of definitions)
    roles[definition.id] = Object.freeze({
      ...definition,
      permissions: Object.freeze([...definition.permissions])
    });
}
export interface AccessSubject {
  readonly userId: string;
  readonly role: RoleId;
  /** Directory-admitted Mock operation snapshot; never sent as HTTP authority. */
  readonly access?: RoleDefinition;
}
export interface ProjectAccessSubject extends AccessSubject {
  readonly projectIds: readonly string[];
}

// Console feature bindings; these do not add fields to the HTTP caller contract.
const permissionProjects: Partial<Record<Permission, string>> = {
  "messages.use": "messages",
  "app-checks.tasks": "app-checks",
  "app-checks.configuration": "app-checks",
  "app-checks.diagnostics": "app-checks",
  "sms.use": "sms"
};
export const projectEntrypoints = [
  { projectId: "app-checks", permission: "app-checks.tasks", path: "/app-checks" },
  { projectId: "messages", permission: "messages.use", path: "/messages" },
  { projectId: "sms", permission: "sms.use", path: "/sms" }
] as const;

export function projectScope(subject: AccessSubject): "all" | "assigned" {
  return (subject.access ?? roles[subject.role]).projectScope;
}
export function canAccessProject(subject: ProjectAccessSubject, projectId: string) {
  return projectScope(subject) === "all" || subject.projectIds.includes(projectId);
}
export function canUseConsole(
  subject: ProjectAccessSubject | null,
  permission: Permission
) {
  const project = permissionProjects[permission];
  return (
    can(subject, permission) &&
    !!subject &&
    (!project || canAccessProject(subject, project))
  );
}
export function consoleHome(subject: ProjectAccessSubject): string {
  const preferred = roles[subject.role].home;
  const binding = projectEntrypoints.find((entry) => entry.path === preferred);
  if (!binding || canUseConsole(subject, binding.permission)) return preferred;
  return (
    projectEntrypoints.find((entry) => canUseConsole(subject, entry.permission))
      ?.path ?? "/?view=home"
  );
}

export function isRole(value: unknown): value is RoleId {
  return value === "admin" || value === "operator";
}
export function can(subject: AccessSubject | null, permission: Permission): boolean {
  return (
    !!subject &&
    isRole(subject.role) &&
    !!(subject.access ?? roles[subject.role])?.permissions.includes(permission)
  );
}
export function taskScope(subject: AccessSubject): "all" | "own" {
  return (subject.access ?? roles[subject.role]).taskScope;
}
export function canAccessTask(
  subject: AccessSubject,
  task: { userId?: string }
): boolean {
  return taskScope(subject) === "all" || subject.userId === task.userId;
}
