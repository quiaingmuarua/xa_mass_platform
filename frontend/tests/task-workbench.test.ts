import { createApp, nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import {
  ElAlert,
  ElButton,
  ElDrawer,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabs,
  ElTabPane,
  ElTag
} from "element-plus";
import { afterEach, expect, it, vi } from "vitest";
import TaskWorkbench from "@/task-workbench/TaskWorkbench.vue";
import TaskRuntimeView from "@/views/TaskRuntimeView.vue";
import { createTaskSession, taskSessionKey } from "@/task-workbench/session";
import type { ProjectSource, ProjectTask } from "@/task-management/projects";
import { createTaskManagementStore } from "@/stores/task-management";
import { createTaskCallDebugStore } from "@/stores/task-call-debug";
import { createRuntimeViewerStore } from "@/stores/runtime-viewer";
import { createRuntimeViewerDataSource } from "@/runtime-viewer/data-source";
import {
  runtimeViewerConfigKey,
  runtimeViewerStoreKey,
  taskManagementStoreKey,
  taskCallDebugStoreKey
} from "@/runtime-context";
import { createTaskExport } from "@/task-management/export";
import { TaskCallDebugError } from "@/task-call-debug/errors";

const disposals: (() => void)[] = [];
afterEach(() => {
  disposals.splice(0).forEach((fn) => fn());
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
function row(id: string, group = "g1", managed = false): ProjectTask {
  return {
    taskId: id,
    createdAtMillis: 123,
    scoreBand: managed ? "running_visible" : "pre_review",
    task: {
      taskId: id,
      workerGroupId: group,
      projectId: "default",
      idleDisposition: managed ? "PARK_WHEN_IDLE" : "CLOSE_WHEN_IDLE",
      refill: [],
      name: null,
      metadata: {},
      config: {}
    }
  };
}
async function mount(
  path: string,
  options: { absent?: boolean; mode?: "api" | "mock" } = {}
) {
  const source: ProjectSource = {
    list: vi
      .fn()
      .mockResolvedValue(
        options.absent
          ? [{ projectId: "business", managedTaskIds: { g1: "business-rpc" } }]
          : [{ projectId: "default", managedTaskIds: { g1: "rpc-1", g2: "rpc-2" } }]
      ),
    groups: vi.fn().mockResolvedValue(
      ["g1", "g2"].map((workerGroupId) => ({
        workerGroupId,
        workerGroup: {
          workerGroupId,
          attributes: {},
          eventCodes: ["extension.worker.string.md5"]
        },
        matching: { pools: ["country"], functions: ["worker.country", "workerId"] }
      }))
    ),
    tasks: vi.fn().mockResolvedValue({
      projectId: "default",
      truncated: true,
      tasks: [
        row("finite"),
        { ...row("closed"), scoreBand: "terminal" },
        { ...row("broken"), task: null },
        { ...row("rpc-1"), task: null }
      ]
    }),
    task: vi.fn(
      async (_project, id): Promise<ProjectTask> => ({
        ...row(id, id === "rpc-2" ? "g2" : "g1", id.startsWith("rpc-")),
        scoreBand:
          id === "closed"
            ? "terminal"
            : id.startsWith("rpc-")
              ? "running_visible"
              : "pre_review"
      })
    )
  };
  const session = createTaskSession(source);
  const pinia = createPinia();
  setActivePinia(pinia);
  const client = {
    createTask: vi.fn(),
    appendItems: vi.fn(),
    approveTask: vi.fn(),
    closeTask: vi.fn(),
    exportResults: vi.fn()
  };
  const rpcClient = {
    callTask: vi.fn().mockResolvedValue({ status: "not_observed" }),
    loadResult: vi
      .fn()
      .mockResolvedValue({ status: "succeeded", opaqueResultPayload: "ok" })
  };
  const mode = options.mode ?? "api";
  const ops = createTaskManagementStore(mode, client);
  const calls = createTaskCallDebugStore(rpcClient, mode);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/task-workbench", component: TaskWorkbench },
      { path: "/runtime/tasks", component: TaskRuntimeView }
    ]
  });
  await router.push(path);
  const app = createApp({ template: "<router-view />" });
  app.use(pinia);
  app.use(router);
  [
    ElAlert,
    ElButton,
    ElDrawer,
    ElInput,
    ElOption,
    ElSelect,
    ElTable,
    ElTableColumn,
    ElTabs,
    ElTabPane,
    ElTag
  ].forEach((c) => app.use(c));
  const config = { mode, apiBaseUrl: "/api" };
  app.provide(runtimeViewerConfigKey, config);
  app.provide(
    runtimeViewerStoreKey,
    createRuntimeViewerStore(
      config,
      createRuntimeViewerDataSource({ ...config, mode: "mock" })
    )
  );
  app.provide(taskSessionKey, session);
  app.provide(taskManagementStoreKey, ops);
  app.provide(taskCallDebugStoreKey, calls);
  const host = document.createElement("div");
  document.body.append(host);
  app.mount(host);
  disposals.push(() => {
    app.unmount();
    session.dispose();
    host.remove();
  });
  await vi.waitFor(() => expect(session.directory.value).toBeDefined());
  return { host, router, source, session, client, ops, calls, rpcClient };
}
function button(text: string) {
  return [...document.querySelectorAll<HTMLButtonElement>("button")].find(
    (b) => b.textContent?.trim() === text
  )!;
}

it("Runtime remains a viewer including readonly export, while finite tasks exclude directory-managed identities", async () => {
  const f = await mount("/runtime/tasks?project=default&taskId=closed&view=create");
  await vi.waitFor(() => expect(button("导出原始成功结果 JSONL")).toBeDefined());
  expect(button("创建原始任务")).toBeUndefined();
  expect(button("创建任务")).toBeUndefined();
  expect(document.body.textContent).not.toContain("Task Call Debug");
  expect(document.body.textContent).not.toContain("核对并批准");
  expect(document.body.textContent).toContain("进入任务工作台");
  await f.router.push("/task-workbench?tab=finite");
  await vi.waitFor(() =>
    expect(f.host.querySelector('[data-testid="finite-task-page"]')).not.toBeNull()
  );
  await vi.waitFor(() => expect(f.host.textContent).toContain("broken"));
  expect(f.host.textContent).not.toContain("rpc-1");
  expect(f.source.tasks).toHaveBeenCalledTimes(2);
  f.host.querySelector(".el-scrollbar__wrap")?.dispatchEvent(new Event("scroll"));
  await nextTick();
  expect(f.source.tasks).toHaveBeenCalledTimes(2);
  expect(f.client.createTask).not.toHaveBeenCalled();
});

it("RPC point-loads directory identities, has no Any default, and retains per-Group drafts and late call ownership", async () => {
  const f = await mount("/task-workbench?tab=rpc&group=g1");
  await vi.waitFor(() => expect(button("提交调用")?.disabled).toBe(false));
  expect(f.source.tasks).not.toHaveBeenCalled();
  expect(f.source.task).toHaveBeenCalledWith(
    "default",
    "rpc-1",
    expect.any(AbortSignal)
  );
  expect(f.calls.draft("rpc-1").executorName).toBe("");
  let finish!: (value: unknown) => void;
  f.rpcClient.callTask.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      })
  );
  Object.assign(f.calls.draft("rpc-1"), {
    eventName: "custom",
    payloadText: '{"value":"first"}',
    executorName: "workerId",
    inputText: '"known-worker"'
  });
  await nextTick();
  button("提交调用").click();
  await vi.waitFor(() => expect(f.rpcClient.callTask).toHaveBeenCalledTimes(1));
  expect(f.rpcClient.callTask.mock.calls[0]![0]).toMatchObject({
    taskId: "rpc-1",
    workerSelector: { executorName: "workerId", input: "known-worker" }
  });
  await f.router.push("/task-workbench?tab=rpc&group=g2");
  await vi.waitFor(() =>
    expect(f.session.rpc.detail.value.value?.taskId).toBe("rpc-2")
  );
  expect(f.calls.draft("rpc-2").executorName).toBe("");
  finish({ status: "succeeded", opaqueResultPayload: "first-result" });
  await vi.waitFor(() => expect(f.calls.history("rpc-1")[0]?.state).toBe("succeeded"));
  expect(f.host.textContent).not.toContain("first-result");
  await f.router.push("/runtime/tasks?project=default");
  await f.router.push("/task-workbench?tab=rpc&group=g1");
  await vi.waitFor(() => expect(f.host.textContent).toContain("first-result"));
  expect(f.calls.draft("rpc-1").payloadText).toContain("first");
});

it("finite create drafts survive tabs and Runtime without resetting file or supplying them to another task", async () => {
  const f = await mount("/task-workbench?tab=finite&view=create");
  await vi.waitFor(() =>
    expect(document.querySelector('textarea[aria-label="Item 查询"]')).not.toBeNull()
  );
  const draft = f.ops.draft("create");
  draft.content = new TextEncoder().encode("kept").buffer;
  draft.file = new File(["kept"], "kept.txt");
  draft.payloadKey = "custom";
  await f.router.push("/task-workbench?tab=rpc&group=g1");
  await f.router.push("/runtime/tasks?project=default");
  await f.router.push("/task-workbench?tab=finite&view=create");
  await vi.waitFor(() => expect(document.body.textContent).toContain("kept.txt"));
  expect(f.ops.draft("create").payloadKey).toBe("custom");
  await f.router.push("/task-workbench?tab=finite&taskId=finite&view=append");
  await vi.waitFor(() =>
    expect(f.session.finite.detail.value.value?.taskId).toBe("finite")
  );
  expect(f.ops.draft("finite").content).toBeUndefined();
});

it("missing default never falls back to a business Project", async () => {
  const f = await mount("/task-workbench?tab=rpc", { absent: true });
  await vi.waitFor(() => expect(f.host.textContent).toContain("未配置 default"));
  expect(f.source.task).not.toHaveBeenCalled();
  expect(f.source.groups).not.toHaveBeenCalled();
  expect(button("提交调用")).toBeUndefined();
});

it("initial table scroll events cannot erase the saved finite-list position when a tab remounts", async () => {
  const f = await mount("/task-workbench?tab=finite");
  await vi.waitFor(() => expect(f.host.textContent).toContain("broken"));
  await new Promise((resolve) =>
    requestAnimationFrame(() => requestAnimationFrame(resolve))
  );
  const wrap = f.host.querySelector<HTMLElement>(".el-table .el-scrollbar__wrap")!;
  wrap.scrollTop = 640;
  wrap.dispatchEvent(new Event("scroll"));
  await vi.waitFor(() => expect(f.session.finiteFilter.scroll).toBe(640));
  await f.router.push("/task-workbench?tab=rpc&group=g1");
  await f.router.push("/task-workbench?tab=finite");
  await nextTick();
  const restored = f.host.querySelector<HTMLElement>(".el-table .el-scrollbar__wrap")!;
  restored.scrollTop = 0;
  restored.dispatchEvent(new Event("scroll"));
  await vi.waitFor(() => expect(restored.scrollTop).toBe(640));
  expect(f.session.finiteFilter.scroll).toBe(640);
});

it("Mock keeps the RPC form read-only", async () => {
  const f = await mount("/task-workbench?tab=rpc&group=g1", { mode: "mock" });
  await vi.waitFor(() => expect(button("提交调用")?.disabled).toBe(true));
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
});

it("failed RPC refresh keeps its old snapshot and time but blocks submission until recovery", async () => {
  const f = await mount("/task-workbench?tab=rpc&group=g1");
  await vi.waitFor(() => expect(button("提交调用")?.disabled).toBe(false));
  const previous = f.session.rpc.detail.value.value;
  const readAt = f.session.rpc.detail.value.readAt;
  vi.mocked(f.source.task).mockRejectedValueOnce(new Error("read unavailable"));
  button("刷新").click();
  await vi.waitFor(() => expect(f.session.rpc.detail.value.stale).toBe(true));
  expect(f.session.rpc.detail.value.value).toEqual(previous);
  expect(f.session.rpc.detail.value.readAt).toBe(readAt);
  expect(button("提交调用").disabled).toBe(true);
  button("刷新").click();
  await vi.waitFor(() => expect(button("提交调用").disabled).toBe(false));
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
});

it("completed import receipts do not claim the task is still awaiting approval", async () => {
  const f = await mount("/task-workbench?tab=finite");
  f.ops.submission = {
    taskId: "closed",
    fileName: "old.txt",
    total: 1,
    applied: 1,
    rejected: 0,
    unconfirmed: 0,
    notSubmitted: 0,
    complete: true
  };
  await nextTick();
  expect(f.host.textContent).toContain("本次导入完成");
  expect(f.host.textContent).not.toContain("等待审核");
});

it("request uncertainty can be read with the original message ID without being recorded as failed execution", async () => {
  setActivePinia(createPinia());
  const client = {
    callTask: vi
      .fn()
      .mockRejectedValue(new TaskCallDebugError({ kind: "network", message: "lost" })),
    loadResult: vi
      .fn()
      .mockResolvedValue({ status: "succeeded", opaqueResultPayload: "verified" })
  };
  const calls = createTaskCallDebugStore(client, "api");
  const result = await calls.send({
    taskId: "rpc",
    workerGroupId: "g",
    eventName: "event",
    payloadText: "{}",
    workerSelectorText: '{"executorName":"workerId","input":"w"}',
    waitTimeoutMillis: 3000
  });
  expect(result.state).toBe("unconfirmed");
  await calls.loadResult("rpc", result.localId);
  expect(client.loadResult).toHaveBeenCalledWith("rpc", result.messageId);
  expect(result.state).toBe("succeeded");
  expect(client.callTask).toHaveBeenCalledTimes(1);
});

it("export uses the whole-task API independently, rejecting stale, running and Mock reads", async () => {
  const client = { exportResults: vi.fn().mockResolvedValue({ ready: false }) };
  const exporter = createTaskExport("api", client);
  const terminal = { ...row("ended"), scoreBand: "terminal" as const };
  await exporter.download(terminal, false);
  await exporter.download(row("running"), true);
  await createTaskExport("mock", client).download(terminal, true);
  expect(client.exportResults).not.toHaveBeenCalled();
  await exporter.download(terminal, true);
  expect(client.exportResults).toHaveBeenCalledWith("ended");
  expect(exporter.error.value).toContain("尚未就绪");
});
