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
import type { Project, ProjectSource, ProjectTask } from "@/task-management/projects";
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
import { rpcTaskId } from "@/task-workbench/rpc-task";
import { ProjectRequestError } from "@/task-management/projects";

const disposals: (() => void)[] = [];
afterEach(() => {
  disposals.splice(0).forEach((fn) => fn());
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
function row(
  id: string,
  group = "g1",
  managed = false,
  projectId = "default"
): ProjectTask {
  return {
    taskId: id,
    createdAtMillis: 123,
    scoreBand: managed ? "running_visible" : "pre_review",
    task: {
      taskId: id,
      workerGroupId: group,
      projectId,
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
  options: {
    projects?: Project[];
    missing?: boolean;
    mode?: "api" | "mock";
    unregistered?: boolean;
    identityOnly?: boolean;
  } = {}
) {
  const source: ProjectSource = {
    list: vi
      .fn()
      .mockResolvedValue(
        options.projects ?? [{ projectId: "default", workerGroupIds: ["g1", "g2"] }]
      ),
    groups: vi.fn().mockResolvedValue(
      ["g1", "g2"].map((workerGroupId) => ({
        workerGroupId,
        workerGroup: options.unregistered
          ? null
          : {
              workerGroupId,
              attributes: {},
              eventCodes: ["extension.worker.string.md5"]
            },
        matching: options.identityOnly
          ? { pools: [], functions: ["workerId"] }
          : { pools: ["country"], functions: ["worker.country", "workerId"] }
      }))
    ),
    tasks: vi.fn(async (projectId) => ({
      projectId,
      truncated: true,
      tasks: [
        row("finite", "g1", false, projectId),
        { ...row("closed", "g1", false, projectId), scoreBand: "terminal" as const },
        { ...row("broken", "g1", false, projectId), task: null },
        row("rpc-1", "g1", true, projectId)
      ]
    })),
    task: vi.fn(async (projectId, id): Promise<ProjectTask> => {
      if (options.missing) throw new ProjectRequestError(400, 12002);
      return {
        ...row(id, id === "rpc-2" ? "g2" : "g1", id.startsWith("rpc-"), projectId),
        scoreBand:
          id === "closed"
            ? "terminal"
            : id.startsWith("rpc-")
              ? "running_visible"
              : "pre_review"
      };
    })
  };
  const preparationClient = { prepareTask: vi.fn().mockResolvedValue(undefined) };
  const session = createTaskSession(source, preparationClient, options.mode ?? "api");
  const pinia = createPinia();
  setActivePinia(pinia);
  const client = {
    createTask: vi.fn(),
    appendItems: vi.fn(),
    approveTask: vi.fn(),
    closeTask: vi.fn(),
    ensureDirectory: vi.fn(async () => {}),
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
  return {
    host,
    router,
    source,
    session,
    client,
    ops,
    calls,
    rpcClient,
    preparationClient
  };
}
function button(text: string) {
  return [...document.querySelectorAll<HTMLButtonElement>("button")].find(
    (b) => b.textContent?.trim() === text
  )!;
}

it("caller selection reads only; explicit preparation enables the original Task without listing Tasks", async () => {
  const f = await mount("/task-workbench?tab=rpc&group=g1", { missing: true });
  await vi.waitFor(() => expect(button("创建／复用 RPC Task")).toBeDefined());
  expect(f.source.task).not.toHaveBeenCalled();
  expect(button("创建／复用 RPC Task").disabled).toBe(true);
  const input = f.host.querySelector<HTMLInputElement>(
    'input[aria-label="RPC 调用方标识"]'
  )!;
  input.focus();
  input.value = " alice ";
  input.dispatchEvent(new Event("input"));
  await nextTick();
  expect(input.value).toBe(" alice ");
  expect(button("创建／复用 RPC Task").disabled).toBe(false);
  input.blur();
  const id = rpcTaskId("default", "alice", "g1");
  await vi.waitFor(() => expect(f.session.rpc.detail.value.missing).toBe(true));
  expect(f.router.currentRoute.value.query.user).toBe("alice");
  expect(f.source.task).toHaveBeenCalledWith("default", id, expect.any(AbortSignal));
  expect(f.preparationClient.prepareTask).not.toHaveBeenCalled();
  f.preparationClient.prepareTask.mockImplementationOnce(async () => {
    vi.mocked(f.source.task).mockResolvedValue(row(id, "g1", true));
  });
  button("创建／复用 RPC Task").click();
  await vi.waitFor(() => expect(button("提交调用")?.disabled).toBe(false));
  expect(f.preparationClient.prepareTask).toHaveBeenCalledExactlyOnceWith(id, {
    projectId: "default",
    workerGroupId: "g1",
    metadata: { userId: "alice" }
  });
  button("刷新").click();
  await vi.waitFor(() => expect(f.session.rpc.detail.value.loading).toBe(false));
  expect(f.preparationClient.prepareTask).toHaveBeenCalledTimes(1);
  expect(f.source.tasks).not.toHaveBeenCalled();
});

it("late preparation stays with its caller and PRE_REVIEW never enables calls", async () => {
  const alice = rpcTaskId("default", "alice", "g1");
  const bob = rpcTaskId("default", "bobby", "g1");
  const f = await mount("/task-workbench?tab=rpc&group=g1&user=alice", {
    missing: true
  });
  await vi.waitFor(() => expect(f.session.rpc.detail.value.missing).toBe(true));
  let finish!: () => void;
  f.preparationClient.prepareTask.mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      })
  );
  button("创建／复用 RPC Task").click();
  await vi.waitFor(() =>
    expect(f.preparationClient.prepareTask).toHaveBeenCalledTimes(1)
  );
  vi.mocked(f.source.task).mockResolvedValue({
    ...row(bob, "g1", true),
    scoreBand: "pre_review"
  });
  await f.router.push("/task-workbench?tab=rpc&group=g1&user=bobby");
  await vi.waitFor(() => expect(f.session.rpc.detail.value.value?.taskId).toBe(bob));
  finish();
  await vi.waitFor(() =>
    expect(f.session.rpcPreparations[alice]?.outcome).toBe("ready")
  );
  expect(f.session.rpc.detail.value.value?.taskId).toBe(bob);
  expect(button("提交调用").disabled).toBe(true);
  expect(f.preparationClient.prepareTask).toHaveBeenCalledTimes(1);
});

it("one create click commits an edited caller even when its point read is still pending", async () => {
  const f = await mount("/task-workbench?tab=rpc&group=g1", { missing: true });
  await vi.waitFor(() => expect(button("创建／复用 RPC Task")).toBeDefined());
  const input = f.host.querySelector<HTMLInputElement>(
    'input[aria-label="RPC 调用方标识"]'
  )!;
  input.value = "newcaller";
  input.dispatchEvent(new Event("input"));
  await nextTick();
  expect(button("创建／复用 RPC Task").disabled).toBe(false);
  let release!: (value: ProjectTask) => void;
  vi.mocked(f.source.task).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        release = resolve;
      })
  );
  button("创建／复用 RPC Task").click();
  const id = rpcTaskId("default", "newcaller", "g1");
  await vi.waitFor(() =>
    expect(f.preparationClient.prepareTask).toHaveBeenCalledExactlyOnceWith(id, {
      projectId: "default",
      workerGroupId: "g1",
      metadata: { userId: "newcaller" }
    })
  );
  release(row(id, "g1", true));
  await nextTick();
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
});

it("Runtime remains a viewer including readonly export, while finite tasks exclude PARK descriptors", async () => {
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

it("RPC point-loads explicit identities, has no Any default, and retains per-Group drafts and late call ownership", async () => {
  const f = await mount("/task-workbench?tab=rpc&taskId=rpc-1");
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
  await f.router.push("/task-workbench?tab=rpc&taskId=rpc-2");
  await vi.waitFor(() =>
    expect(f.session.rpc.detail.value.value?.taskId).toBe("rpc-2")
  );
  expect(f.calls.draft("rpc-2").executorName).toBe("");
  finish({ status: "succeeded", opaqueResultPayload: "first-result" });
  await vi.waitFor(() => expect(f.calls.history("rpc-1")[0]?.state).toBe("succeeded"));
  expect(f.host.textContent).not.toContain("first-result");
  await f.router.push("/runtime/tasks?project=default");
  await f.router.push("/task-workbench?tab=rpc&taskId=rpc-1");
  await vi.waitFor(() => expect(f.host.textContent).toContain("first-result"));
  expect(f.calls.draft("rpc-1").payloadText).toContain("first");
});

it("finite create drafts survive tabs and Runtime without resetting file or supplying them to another task", async () => {
  const f = await mount("/task-workbench?tab=finite&view=create");
  await vi.waitFor(() =>
    expect(document.querySelector('textarea[aria-label="Item 查询"]')).not.toBeNull()
  );
  const draft = f.ops.draft("default", "create");
  draft.content = new TextEncoder().encode("kept").buffer;
  draft.file = new File(["kept"], "kept.txt");
  draft.payloadKey = "custom";
  await f.router.push("/task-workbench?tab=rpc&taskId=rpc-1");
  await f.router.push("/runtime/tasks?project=default");
  await f.router.push("/task-workbench?tab=finite&view=create");
  await vi.waitFor(() => expect(document.body.textContent).toContain("kept.txt"));
  expect(f.ops.draft("default", "create").payloadKey).toBe("custom");
  await f.router.push("/task-workbench?tab=finite&taskId=finite&view=append");
  await vi.waitFor(() =>
    expect(f.session.finite.detail.value.value?.taskId).toBe("finite")
  );
  expect(f.ops.draft("default", "finite").content).toBeUndefined();
});

it("uses the sole declared non-default Project and leaves an empty directory unavailable", async () => {
  const f = await mount("/task-workbench?tab=rpc", {
    projects: [{ projectId: "agentforge", workerGroupIds: ["g1"] }],
    identityOnly: true
  });
  await vi.waitFor(() =>
    expect(f.router.currentRoute.value.query.project).toBe("agentforge")
  );
  expect(f.host.querySelector('[aria-label="工作台 Project"]')).toBeNull();
  expect(f.host.textContent).toContain("Project · agentforge");
  await vi.waitFor(() =>
    expect(f.source.groups).toHaveBeenCalledWith("agentforge", expect.any(AbortSignal))
  );
  expect(f.preparationClient.prepareTask).not.toHaveBeenCalled();
  const empty = await mount("/task-workbench", { projects: [] });
  await vi.waitFor(() => expect(empty.host.textContent).toContain("未配置 Project"));
  expect(empty.source.groups).not.toHaveBeenCalled();
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
  await vi.waitFor(() =>
    expect(f.session.project("default").finiteFilter.scroll).toBe(640)
  );
  await f.router.push("/task-workbench?tab=rpc&taskId=rpc-1");
  await f.router.push("/task-workbench?tab=finite");
  await nextTick();
  const restored = f.host.querySelector<HTMLElement>(".el-table .el-scrollbar__wrap")!;
  restored.scrollTop = 0;
  restored.dispatchEvent(new Event("scroll"));
  await vi.waitFor(() => expect(restored.scrollTop).toBe(640));
  expect(f.session.project("default").finiteFilter.scroll).toBe(640);
});

it("Mock keeps the RPC form read-only", async () => {
  const f = await mount("/task-workbench?tab=rpc&taskId=rpc-1", { mode: "mock" });
  await vi.waitFor(() => expect(button("提交调用")?.disabled).toBe(true));
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
});

it("failed RPC refresh keeps its old snapshot and time but blocks submission until recovery", async () => {
  const f = await mount("/task-workbench?tab=rpc&taskId=rpc-1");
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
  f.ops.project("default").submission = {
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
  const client = {
    ensureDirectory: vi.fn(async () => {}),
    exportResults: vi.fn().mockResolvedValue({ ready: false })
  };
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

const twoProjects: Project[] = [
  { projectId: "agentforge", workerGroupIds: ["g1", "g2"] },
  { projectId: "messages", workerGroupIds: ["g1", "g2"] }
];
it("never silently replaces an explicit unknown Project and restores a valid session selection", async () => {
  const f = await mount("/task-workbench?project=unknown&tab=finite", {
    projects: twoProjects
  });
  await vi.waitFor(() => expect(f.host.textContent).toContain("不在当前目录"));
  expect(f.source.tasks).not.toHaveBeenCalled();
  expect(f.source.groups).not.toHaveBeenCalled();
  await f.router.push("/task-workbench?project=messages&tab=finite");
  await vi.waitFor(() =>
    expect(f.source.tasks).toHaveBeenCalledWith("messages", expect.any(AbortSignal))
  );
  await f.router.push("/runtime/tasks?view=global");
  await f.router.push("/task-workbench?tab=finite");
  await vi.waitFor(() =>
    expect(f.router.currentRoute.value.query.project).toBe("messages")
  );
});
it("prepares the non-default caller identity using workerId-only capabilities and refreshes missing Groups", async () => {
  const f = await mount(
    "/task-workbench?project=agentforge&tab=rpc&group=g1&user=1001",
    {
      projects: twoProjects,
      identityOnly: true,
      unregistered: true,
      missing: true
    }
  );
  await vi.waitFor(() => expect(f.host.textContent).toContain("尚未注册"));
  expect(button("创建／复用 RPC Task").disabled).toBe(true);
  vi.mocked(f.source.groups).mockResolvedValue([
    {
      workerGroupId: "g1",
      workerGroup: { workerGroupId: "g1", attributes: {}, eventCodes: ["event"] },
      matching: { pools: [], functions: ["workerId"] }
    }
  ]);
  button("刷新").click();
  await vi.waitFor(() => expect(button("创建／复用 RPC Task").disabled).toBe(false));
  const id = rpcTaskId("agentforge", "1001", "g1");
  f.preparationClient.prepareTask.mockImplementationOnce(async () => {
    vi.mocked(f.source.task).mockResolvedValue(row(id, "g1", true, "agentforge"));
  });
  button("创建／复用 RPC Task").click();
  await vi.waitFor(() => expect(button("提交调用")?.disabled).toBe(false));
  expect(f.preparationClient.prepareTask).toHaveBeenCalledExactlyOnceWith(id, {
    projectId: "agentforge",
    workerGroupId: "g1",
    metadata: { userId: "1001" }
  });
  Object.assign(f.calls.draft(id), {
    eventName: "event",
    payloadText: "{}",
    executorName: "workerId",
    inputText: '"worker-1"'
  });
  await nextTick();
  expect(f.host.textContent).toContain("JSON 字符串");
  button("提交调用").click();
  await vi.waitFor(() => expect(f.rpcClient.callTask).toHaveBeenCalledTimes(1));
  expect(f.rpcClient.callTask).toHaveBeenCalledWith(
    expect.objectContaining({
      taskId: id,
      workerSelector: { executorName: "workerId", input: "worker-1" }
    })
  );
});
it("Project switches isolate RPC identities and late preparation without moving the current page", async () => {
  const f = await mount(
    "/task-workbench?project=agentforge&tab=rpc&group=g1&user=1001",
    { projects: twoProjects, missing: true }
  );
  await vi.waitFor(() => expect(f.session.rpc.detail.value.missing).toBe(true));
  let release!: () => void;
  f.preparationClient.prepareTask.mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        release = resolve;
      })
  );
  button("创建／复用 RPC Task").click();
  await vi.waitFor(() => expect(release).toBeTypeOf("function"));
  await f.router.push("/task-workbench?project=messages&tab=rpc&group=g1&user=1001");
  const a = rpcTaskId("agentforge", "1001", "g1"),
    b = rpcTaskId("messages", "1001", "g1");
  await vi.waitFor(() => expect(f.session.rpc.detailKey.value).toBe("messages/" + b));
  release();
  await vi.waitFor(() => expect(f.session.rpcPreparations[a]?.outcome).toBe("ready"));
  expect(f.session.rpcPreparations[b]).toBeUndefined();
  expect(f.router.currentRoute.value.query.project).toBe("messages");
  expect(f.session.rpc.detailKey.value).toBe("messages/" + b);
  expect(f.preparationClient.prepareTask).toHaveBeenCalledTimes(1);
});
it("keeps finite drafts, filters and receipts in their owning Project and ignores wrong-Project detail", async () => {
  const f = await mount("/task-workbench?project=agentforge&tab=finite&view=create", {
    projects: twoProjects
  });
  await vi.waitFor(() =>
    expect(document.body.textContent).toContain("创建有限任务 · agentforge")
  );
  f.ops.draft("agentforge", "create").payloadKey = "original";
  f.session.project("agentforge").finiteFilter.search = "finite";
  f.ops.project("agentforge").submission = {
    taskId: "original",
    fileName: "a.txt",
    total: 3,
    applied: 1,
    rejected: 0,
    unconfirmed: 2,
    notSubmitted: 0,
    complete: false
  };
  await f.router.push("/task-workbench?project=messages&tab=finite&view=create");
  await vi.waitFor(() =>
    expect(document.body.textContent).toContain("创建有限任务 · messages")
  );
  expect(f.ops.draft("messages", "create").payloadKey).not.toBe("original");
  expect(f.ops.project("messages").submission).toBeUndefined();
  expect(f.session.project("messages").finiteFilter.search).toBe("");
  vi.mocked(f.source.task).mockResolvedValue(row("foreign", "g1", false, "agentforge"));
  await f.router.push(
    "/task-workbench?project=messages&tab=finite&taskId=foreign&view=append"
  );
  await vi.waitFor(() =>
    expect(f.session.finite.detail.value.value?.taskId).toBe("foreign")
  );
  expect(button("追加 Items")?.disabled).toBe(true);
  expect(f.client.approveTask).not.toHaveBeenCalled();
  await f.router.push("/task-workbench?project=agentforge&tab=finite&view=create");
  await vi.waitFor(() => expect(f.host.textContent).toContain("本会话提交"));
  expect(f.ops.draft("agentforge", "create").payloadKey).toBe("original");
  expect(f.session.project("agentforge").finiteFilter.search).toBe("finite");
});
it("Runtime links preserve non-default Project and actual Task identity even with an empty list", async () => {
  const f = await mount("/runtime/tasks?project=agentforge&taskId=rpc-1", {
    projects: twoProjects
  });
  await vi.waitFor(() => expect(document.body.textContent).toContain("PARK_WHEN_IDLE"));
  expect(
    [...document.querySelectorAll<HTMLAnchorElement>("a")].some(
      (a) =>
        a.getAttribute("href")?.includes("project=agentforge") &&
        a.getAttribute("href")?.includes("taskId=rpc-1")
    )
  ).toBe(true);
  vi.mocked(f.source.tasks).mockResolvedValue({
    projectId: "messages",
    tasks: [],
    truncated: false
  });
  await f.router.push("/runtime/tasks?project=messages");
  await vi.waitFor(() =>
    expect(f.host.querySelector('a[href*="project=messages"]')).not.toBeNull()
  );
  expect(button("创建任务")).toBeUndefined();
});
