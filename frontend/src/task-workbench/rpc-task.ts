import type { TaskCaller } from "@/task-caller";
import { normalizeTaskUserId } from "@/task-user-id";
import type { RefillTarget } from "@/runtime-viewer/types";

export interface RpcTaskPrepareRequest {
  projectId: string;
  workerGroupId: string;
  name?: string | null;
  metadata: Record<string, string>;
  refill?: RefillTarget[];
}

export interface RpcTaskPreparationClient {
  prepareTask(
    caller: TaskCaller,
    taskId: string,
    request: RpcTaskPrepareRequest
  ): Promise<void>;
}

/** A workbench naming convention; the Server does not decode or enforce it. */
export function rpcTaskId(project: string, user: string, group: string): string {
  const normalized = normalizeTaskUserId(user);
  if (!project || !group) throw new Error("请选择 Project 和 Group。");
  const encode = (value: string) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(value)))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
  return "rpc-workbench-v1." + [project, normalized, group].map(encode).join(".");
}
