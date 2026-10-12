export interface RpcTaskPrepareRequest {
  projectId: string;
  workerGroupId: string;
  name?: string | null;
  metadata: Record<string, string>;
}

export interface RpcTaskPreparationClient {
  prepareTask(taskId: string, request: RpcTaskPrepareRequest): Promise<void>;
}

/** A workbench naming convention; the Server does not decode or enforce it. */
export function rpcTaskId(project: string, user: string, group: string): string {
  const normalized = user.trim();
  if (!project || !group || !normalized || normalized.length > 128)
    throw new Error("请输入 1–128 个字符的调用方标识并选择 Group。");
  const encode = (value: string) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(value)))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
  return "rpc-workbench-v1." + [project, normalized, group].map(encode).join(".");
}
