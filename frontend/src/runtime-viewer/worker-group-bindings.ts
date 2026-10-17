import axios from "axios";
import { reactive } from "vue";
import { z } from "zod";
import { workerGroupViewSchema } from "./schemas";
import { MOCK_WORKER_GROUPS } from "./mock-data";
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
      maxCount: z.number().int().positive()
    })
  ),
  feedbacks: z.array(
    z.strictObject({
      name,
      poolName: name,
      observationEventName: z.literal("worker.assigned"),
      messageEvents: z.array(name),
      timestampProperty: name,
      countProperty: name,
      windowMillis: z.number().int().positive()
    })
  ),
  readAt: z.string().datetime({ offset: true })
});
export type WorkerGroupBindings = z.infer<typeof workerGroupBindingsSchema>;
export type WorkerGroupBindingsLoader = (
  group: string,
  signal?: AbortSignal
) => Promise<WorkerGroupBindings>;

export function createWorkerGroupBindingsLoader(
  config: RuntimeViewerConfig
): WorkerGroupBindingsLoader {
  if (config.mode === "mock")
    return async (workerGroupId) => {
      const workerGroup =
        MOCK_WORKER_GROUPS.find((group) => group.workerGroupId === workerGroupId) ??
        null;
      const window = workerGroupId === "scenario-phone-number-workers";
      return structuredClone({
        workerGroupId,
        workerGroup,
        readAt: new Date().toISOString(),
        matching: {
          pools: window ? ["any", "phone-window-demo"] : ["any"],
          functions: [
            { name: "workerId", poolName: null },
            { name: "worker.any", poolName: "any" },
            ...(window
              ? [{ name: "worker.phone-window-demo", poolName: "phone-window-demo" }]
              : [])
          ]
        },
        fixedWindowPools: window
          ? [
              {
                poolName: "phone-window-demo",
                functionName: "worker.phone-window-demo",
                timestampProperty: "demoAssignedAt",
                countProperty: "demoAssignmentCount",
                windowMillis: 60000,
                maxCount: 10
              }
            ]
          : [],
        feedbacks: window
          ? [
              {
                name: "phone-observation-demo",
                poolName: "phone-window-demo",
                observationEventName: "worker.assigned" as const,
                messageEvents: ["extension.worker.phonenumber.original-carrier"],
                timestampProperty: "demoAssignedAt",
                countProperty: "demoAssignmentCount",
                windowMillis: 60000
              }
            ]
          : []
      });
    };
  const client = axios.create({ baseURL: config.apiBaseUrl, timeout: 5000 });
  return async (group, signal) => {
    const response = await client.get(
      `/v1/runtime-view/worker-groups/${encodeURIComponent(group)}/bindings`,
      {
        signal,
        headers: { "X-Request-Id": `group-bindings-${crypto.randomUUID()}` }
      }
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
