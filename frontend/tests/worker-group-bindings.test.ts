import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick } from "vue";
import { ElButton } from "element-plus";
import axios, { type AxiosInstance } from "axios";
import WorkerGroupBindingsPanel from "../src/components/WorkerGroupBindings.vue";
import { createProjectSource } from "../src/task-management/projects";
import { MockUserDirectorySource } from "../src/user-directory-source";
import { ADMIN } from "./identity-fixture";
import {
  createWorkerGroupBindingsLoader,
  createWorkerGroupBindingsDirectoryLoader,
  createWorkerGroupBindingsState,
  type WorkerGroupBindings
} from "../src/runtime-viewer/worker-group-bindings";

const config = { mode: "mock" as const, apiBaseUrl: "/api" };
const example = (group: string) => createWorkerGroupBindingsLoader(config)(group);
afterEach(() => {
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

describe("WorkerGroup effective bindings", () => {
  it("Project scope and Group window enablement are independent with zero service calls", async () => {
    const create = vi.spyOn(axios, "create");
    const fetcher = vi.spyOn(globalThis, "fetch");
    const source = createProjectSource(
      config.apiBaseUrl,
      config.mode,
      ADMIN,
      new MockUserDirectorySource()
    );
    const projects = await source.list();
    expect(projects).toContainEqual({
      projectId: "pool-validation",
      workerGroupSource: "declared",
      workerGroupIds: ["android-demo-workers", "scenario-string-utils-workers"]
    });
    const inherited = await source.groups("pool-validation");
    const defaults = await source.groups("default");
    const view = await example("android-demo-workers");
    expect(inherited).toHaveLength(2);
    expect(inherited[1]!.matching.pools).not.toContain("device-window");
    expect(
      (await example("scenario-phone-number-workers")).projects.some(
        (p) => p.projectId === "pool-validation"
      )
    ).toBe(false);
    expect(inherited[0]!.matching).toEqual(view.matching);
    expect(
      defaults.find((g) => g.workerGroupId === "android-demo-workers")?.matching
    ).toEqual(view.matching);
    expect(view.fixedWindowPools[0]?.poolName).toBe("device-window");
    expect(view.fixedWindowPools[0]?.feedback.messageEvents).toEqual([
      "extension.worker.android.string.digest"
    ]);
    expect(create).not.toHaveBeenCalled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("Mock displays declarations and independent identity functions without service requests", async () => {
    const create = vi.spyOn(axios, "create");
    const fetcher = vi.spyOn(globalThis, "fetch");
    const load = createWorkerGroupBindingsLoader(config);
    const value = await load("scenario-phone-number-workers");
    expect(value.fixedWindowPools[0]?.poolName).toBe("phone-window-demo");
    expect(value.fixedWindowPools[0]?.feedback.messageEvents).toEqual([
      "extension.worker.phonenumber.original-carrier"
    ]);
    expect(value.matching.functions).toContainEqual({
      name: "workerId",
      poolName: null
    });
    expect((await load("scenario-string-utils-workers")).fixedWindowPools).toEqual([]);
    expect((await load("missing")).workerGroup).toBeNull();
    expect(create).not.toHaveBeenCalled();
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("directory reads only startup identities through its own endpoint", async () => {
    const directory = { workerGroupIds: ["a", "c"], readAt: "2026-10-19T00:00:00Z" };
    const get = vi.fn().mockResolvedValue({ data: directory });
    vi.spyOn(axios, "create").mockReturnValue({ get } as unknown as AxiosInstance);
    await expect(
      createWorkerGroupBindingsDirectoryLoader({ ...config, mode: "api" })()
    ).resolves.toEqual(directory);
    expect(get).toHaveBeenCalledWith(
      "/v1/runtime-view/worker-groups/bindings",
      expect.any(Object)
    );
  });

  it("API reads one known Group and rejects mismatched response identity", async () => {
    const value = await example("scenario-phone-number-workers");
    const get = vi.fn().mockResolvedValue({ data: value });
    vi.spyOn(axios, "create").mockReturnValue({ get } as unknown as AxiosInstance);
    const load = createWorkerGroupBindingsLoader({ ...config, mode: "api" });
    await expect(load(value.workerGroupId)).resolves.toEqual(value);
    expect(get).toHaveBeenCalledWith(
      `/v1/runtime-view/worker-groups/${value.workerGroupId}/bindings`,
      expect.any(Object)
    );
    await expect(load("wrong-group")).rejects.toThrow("身份不一致");
    get.mockRejectedValue(new Error("unavailable"));
    await expect(load(value.workerGroupId)).rejects.toThrow("unavailable");
  });

  it("late reads remain with their Group and failed refresh preserves the original timestamp", async () => {
    let resolveA!: (value: WorkerGroupBindings) => void;
    const b = await example("scenario-string-utils-workers");
    const load = vi.fn().mockImplementation((group: string) =>
      group === "a"
        ? new Promise<WorkerGroupBindings>((resolve) => {
            resolveA = resolve;
          })
        : Promise.resolve(b)
    );
    const state = createWorkerGroupBindingsState(load);
    const pending = state.refresh("a");
    await state.refresh(b.workerGroupId);
    resolveA({ ...(await example("a")), workerGroupId: "a" });
    await pending;
    expect(state.entry(b.workerGroupId).snapshot).toEqual(b);
    load.mockRejectedValue(new Error("Owner read failed"));
    await state.refresh(b.workerGroupId);
    expect(state.entry(b.workerGroupId).snapshot?.readAt).toBe(b.readAt);
    expect(state.entry(b.workerGroupId).stale).toBe(true);
    expect(state.entry(b.workerGroupId).error).toContain("读取失败");
    state.dispose();
  });

  it("a superseded request cannot replace a newer read or update after disposal", async () => {
    const requests: Array<(value: WorkerGroupBindings) => void> = [];
    const state = createWorkerGroupBindingsState(
      () =>
        new Promise((resolve) => {
          requests.push(resolve);
        })
    );
    const old = state.refresh("g"),
      newer = state.refresh("g");
    const value = await example("g");
    requests[1]!(value);
    await newer;
    requests[0]!({ ...value, readAt: "2020-01-01T00:00:00Z" });
    await old;
    expect(state.entry("g").snapshot?.readAt).toBe(value.readAt);
    const final = state.refresh("g");
    state.dispose();
    requests[2]!({ ...value, readAt: "2021-01-01T00:00:00Z" });
    await final;
    expect(state.entry("g").snapshot?.readAt).toBe(value.readAt);
  });

  it("the full relation panel shows independent Project scope and paired feedback", async () => {
    const host = document.createElement("div");
    document.body.append(host);
    const app = createApp({
      render: () =>
        h(WorkerGroupBindingsPanel, {
          workerGroupId: "scenario-phone-number-workers",
          config
        })
    }).use(ElButton);
    app.mount(host);
    try {
      await nextTick();
      await nextTick();
      expect(host.querySelector("details")?.open).toBe(true);
      expect(host.textContent).toContain("Mock 演示");
      expect(host.textContent).toContain("phone-window-demo");
      expect(host.textContent).toContain("demoAssignmentCount");
      expect(host.textContent).toContain("Project 使用范围");
      expect(host.textContent).toContain("反馈观察点");
      expect(host.textContent).toContain("不证明 Worker 在线");
    } finally {
      app.unmount();
    }
  });
});
