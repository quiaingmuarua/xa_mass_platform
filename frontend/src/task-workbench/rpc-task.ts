import type { TaskCaller } from "@/task-caller";
import { normalizeTaskUserId } from "@/task-user-id";
import type { RefillTarget } from "@/runtime-viewer/types";

export interface RpcTaskPrepareRequest {
  projectId: string;
  workerGroupId: string;
  ownerUserId?: string;
  name?: string | null;
  metadata: Record<string, string>;
  refill?: RefillTarget[];
}

export interface RpcTaskPreparationClient {
  prepareTask(caller: TaskCaller, request: RpcTaskPrepareRequest): Promise<string>;
}

/** UI-local session slot key for tracking an in-flight RPC channel preparation. Never sent as a Task ID. */
export function rpcChannelSlotKey(project: string, ownerUserId: string, group: string): string {
  const normalized = normalizeTaskUserId(ownerUserId);
  if (!project || !group) throw new Error("请选择 Project 和 Group。");
  return `${project}/${normalized}/${group}`;
}
