import type { AccessSubject } from "./access-policy";

export interface ConsoleUser extends AccessSubject {
  readonly displayName: string;
}

// Development data only. Neither pages nor permission rules depend on these IDs.
export const developmentUsers: readonly ConsoleUser[] = Object.freeze([
  Object.freeze({ userId: "1000", displayName: "管理员", role: "admin" }),
  Object.freeze({ userId: "1001", displayName: "用户 A", role: "operator" }),
  Object.freeze({ userId: "1002", displayName: "用户 B", role: "operator" })
]);
