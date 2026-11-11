import { developmentAccessKey } from "@/development-users";
import { withAccessKey, type TaskCaller } from "@/task-caller";

/** The public Mock and local development key of a built-in user. */
export const devKey = developmentAccessKey;

function caller(userId: string, role: TaskCaller["role"]): TaskCaller {
  return Object.freeze(withAccessKey({ userId, role }, devKey(userId)));
}
export const ADMIN: TaskCaller = caller("1000", "admin");
export const OPERATOR_A: TaskCaller = caller("1001", "operator");
export const OPERATOR_B: TaskCaller = caller("1002", "operator");
