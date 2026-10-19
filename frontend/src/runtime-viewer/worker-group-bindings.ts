import axios from "axios";
import { reactive } from "vue";
import { z } from "zod";
import { workerGroupViewSchema } from "./schemas";
import { MOCK_WORKER_GROUPS } from "./mock-data";
import { MOCK_PROJECTS, MOCK_AGENT_GROUPS } from "./mock-projects";
import type { RuntimeViewerConfig } from "./types";

const name = z.string().min(1);
export const workerGroupBindingsSchema = z.strictObject({
  workerGroupId: name,
  workerGroup: workerGroupViewSchema.nullable(),
  matching: z.strictObject({
    pools: z.array(name),
    functions: z.array(z.strictObject({ name, poolName: name.nullable() }))
  }),
  fixedWindowPools: z.array(
    z.strictObject({
      poolName: name,
      functionName: name,
      timestampProperty: name,
      countProperty: name,
      windowMillis: z.number().int().positive(),
      maxCount: z.number().int().positive(),
      feedback: z.strictObject({
        observationEventName: z.literal("worker.assigned"),
        messageEvents: z.array(name).min(1)
      })
    })
  ),
  projects: z.array(
    z.strictObject({
      projectId: name,
      workerGroupSource: z.enum(["registered", "declared"])
    })
  ),
  readAt: z.string().datetime({ offset: true })
});
export const workerGroupBindingsDirectorySchema = z.strictObject({
  workerGroupIds: z.array(name),
  readAt: z.string().datetime({ offset: true })
});
export type WorkerGroupBindings = z.infer<typeof workerGroupBindingsSchema>;
export type WorkerGroupBindingsDirectory = z.infer<
  typeof workerGroupBindingsDirectorySchema
>;
export type WorkerGroupBindingsLoader = (
  group: string,
  signal?: AbortSignal
) => Promise<WorkerGroupBindings>;

export function mockWorkerGroupBindings(workerGroupId: string): WorkerGroupBindings {
  const workerGroup =
    MOCK_WORKER_GROUPS.find((group) => group.workerGroupId === workerGroupId) ??
    MOCK_AGENT_GROUPS.find((group) => group.workerGroupId === workerGroupId)
      ?.workerGroup ??
    null;
  const anyEnabled = MOCK_WORKER_GROUPS.some(
    (group) => group.workerGroupId === workerGroupId
  );
  const window =
    workerGroupId === "scenario-phone-number-workers"
      ? {
          poolName: "phone-window-demo",
          functionName: "worker.phone-window-demo",
          timestampProperty: "demoAssignedAt",
          countProperty: "demoAssignmentCount",
          windowMillis: 60000,
          maxCount: 10,
          feedback: {
            observationEventName: "worker.assigned" as const,
            messageEvents: ["extension.worker.phonenumber.original-carrier"]
          }
        }
      : workerGroupId === "android-demo-workers"
        ? {
            poolName: "device-window",
            functionName: "worker.device.available",
            timestampProperty: "deviceAssignedAt",
            countProperty: "deviceAssignmentCount",
            windowMillis: 60000,
            maxCount: 10,
            feedback: {
              observationEventName: "worker.assigned" as const,
              messageEvents: ["extension.worker.android.string.digest"]
            }
          }
        : null;
  return structuredClone({
    workerGroupId,
    workerGroup,
    readAt: new Date().toISOString(),
    projects: MOCK_PROJECTS.filter((p) =>
      p.workerGroupSource === "registered"
        ? workerGroup !== null
        : p.workerGroupIds.includes(workerGroupId)
    ).map(({ projectId, workerGroupSource }) => ({ projectId, workerGroupSource })),
    matching: {
      pools: [...(anyEnabled ? ["any"] : []), ...(window ? [window.poolName] : [])],
      functions: [
        { name: "workerId", poolName: null },
        ...(anyEnabled ? [{ name: "worker.any", poolName: "any" }] : []),
        ...(window ? [{ name: window.functionName, poolName: window.poolName }] : [])
      ]
    },
    fixedWindowPools: window ? [window] : []
  });
}
export function createWorkerGroupBindingsDirectoryLoader(config: RuntimeViewerConfig) {
  if (config.mode === "mock")
    return async (_signal?: AbortSignal): Promise<WorkerGroupBindingsDirectory> => ({
      workerGroupIds: [
        ...new Set([
          ...MOCK_WORKER_GROUPS.map((g) => g.workerGroupId),
          ...MOCK_PROJECTS.flatMap((p) => p.workerGroupIds)
        ])
      ].sort(),
      readAt: new Date().toISOString()
    });
  const client = axios.create({ baseURL: config.apiBaseUrl, timeout: 5000 });
  return async (signal?: AbortSignal) =>
    workerGroupBindingsDirectorySchema.parse(
      (
        await client.get("/v1/runtime-view/worker-groups/bindings", {
          signal,
          headers: { "X-Request-Id": `group-directory-${crypto.randomUUID()}` }
        })
      ).data
    );
}
export function createWorkerGroupBindingsLoader(
  config: RuntimeViewerConfig
): WorkerGroupBindingsLoader {
  if (config.mode === "mock") return async (group) => mockWorkerGroupBindings(group);
  const client = axios.create({ baseURL: config.apiBaseUrl, timeout: 5000 });
  return async (group, signal) => {
    const response = await client.get(
      `/v1/runtime-view/worker-groups/${encodeURIComponent(group)}/bindings`,
      { signal, headers: { "X-Request-Id": `group-bindings-${crypto.randomUUID()}` } }
    );
    const value = workerGroupBindingsSchema.parse(response.data);
    if (
      value.workerGroupId !== group ||
      (value.workerGroup !== null && value.workerGroup.workerGroupId !== group)
    )
      throw new Error("Group 绑定响应身份不一致");
    return value;
  };
}

export function createWorkerGroupBindingsState(load: WorkerGroupBindingsLoader) {
  interface Entry {
    snapshot?: WorkerGroupBindings;
    loading: boolean;
    stale: boolean;
    error: string;
  }
  const entries = reactive<Record<string, Entry>>({});
  const requests = new Map<string, AbortController>();
  let disposed = false;
  function entry(group: string): Entry {
    if (!entries[group]) entries[group] = { loading: false, stale: false, error: "" };
    return entries[group];
  }
  async function refresh(group: string) {
    if (disposed) return;
    requests.get(group)?.abort();
    const request = new AbortController();
    requests.set(group, request);
    const state = entry(group);
    state.loading = true;
    state.error = "";
    try {
      const snapshot = await load(group, request.signal);
      if (disposed || requests.get(group) !== request) return;
      if (snapshot.workerGroupId !== group) throw new Error("Group 绑定响应身份不一致");
      state.snapshot = snapshot;
      state.stale = false;
    } catch {
      if (disposed || requests.get(group) !== request || request.signal.aborted) return;
      state.stale = true;
      state.error = "资源绑定读取失败，请手动重试。已有快照保持不变。";
    } finally {
      if (requests.get(group) === request) {
        state.loading = false;
        requests.delete(group);
      }
    }
  }
  function dispose() {
    disposed = true;
    requests.forEach((request) => request.abort());
    requests.clear();
  }
  return { entry, refresh, dispose };
}
