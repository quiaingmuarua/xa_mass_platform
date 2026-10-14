import type { TaskCaller } from "@/task-caller";
export const ADMIN: TaskCaller = Object.freeze({ userId: "1000", role: "admin" });
export const OPERATOR_A: TaskCaller = Object.freeze({
  userId: "1001",
  role: "operator"
});
export const OPERATOR_B: TaskCaller = Object.freeze({
  userId: "1002",
  role: "operator"
});
