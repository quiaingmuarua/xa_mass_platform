import type { ProjectAccessSubject, RoleDefinition } from "./access-policy";

export interface ConsoleUser extends ProjectAccessSubject {
  readonly displayName: string;
}
/** Explicit Mock fixtures. Real API mode never uses these defaults. */
export const developmentRoles: readonly RoleDefinition[] = [
  {
    id: "admin",
    label: "管理员",
    permissions: [
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
    ],
    taskScope: "all",
    projectScope: "all",
    home: "/runtime/workers"
  },
  {
    id: "operator",
    label: "普通用户",
    permissions: ["console.view", "app-checks.tasks", "messages.use"],
    taskScope: "own",
    projectScope: "assigned",
    home: "/app-checks"
  }
];

// Development data only. Neither pages nor permission rules depend on these IDs.
export const developmentUsers: readonly ConsoleUser[] = Object.freeze([
  Object.freeze({
    userId: "1000",
    displayName: "管理员",
    role: "admin",
    projectIds: Object.freeze([])
  }),
  Object.freeze({
    userId: "1001",
    displayName: "用户 A",
    role: "operator",
    projectIds: Object.freeze(["app-checks", "messages"])
  }),
  Object.freeze({
    userId: "1002",
    displayName: "用户 B",
    role: "operator",
    projectIds: Object.freeze(["app-checks", "messages"])
  })
]);
