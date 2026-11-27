import { MockUserDirectorySource } from "@/user-directory-source";
import { consoleIdentity } from "@/console-identity";
import { ADMIN as CALLER, devKey } from "./identity-fixture";
import { createApp, nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import {
  ElDropdown,
  ElDropdownMenu,
  ElDropdownItem,
  ElMessageBox,
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
import { beforeEach, afterEach, expect, it, vi } from "vitest";
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
import { rpcChannelSlotKey } from "@/task-workbench/rpc-task";
import { ProjectRequestError } from "@/task-management/projects";

const disposals: (() => void)[] = [];
beforeEach(() => consoleIdentity.login(devKey("1000")));
afterEach(() => {
  disposals.splice(0).forEach((fn) => fn());
  consoleIdentity.logout();
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
    stats: { total: 1, countsByTag: { "1": 1 } },
    scoreBand: managed ? "running_visible" : "pre_review",
    retention: null,
    task: {
      taskId: id,
      workerGroupId: group,
      projectId,
      idleDisposition: managed ? "PARK_WHEN_IDLE" : "CLOSE_WHEN_IDLE",
      refill: [],
      name: null,
      metadata: { "server.inputOwner": "raw" },
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
    results: vi.fn().mockResolvedValue({ results: [], truncated: false }),
    stats: vi.fn().mockResolvedValue({}),
    list: vi
      .fn()
      .mockResolvedValue(
        options.projects ?? [
          { projectId: "default", workerGroupSource: "registered", workerGroupIds: [] }
        ]
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
          ? { pools: [], functions: [{ name: "workerId", poolName: null }] }
          : {
              pools: ["country"],
              functions: [
                { name: "worker.country", poolName: "country" },
                { name: "workerId", poolName: null }
              ]
            }
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
  const preparationClient = {
    prepareTask: vi.fn().mockResolvedValue("rpc-minted-00000000000000000000000000000001")
  };
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
    callTask: vi.fn().mockResolvedValue({ sample: { status: "not_observed" } }),
    takeResults: vi
      .fn()
      .mockResolvedValue({ sample: { status: "succeeded", opaqueResultPayload: "ok" } })
  };
  const mode = options.mode ?? "api";
  const ops = createTaskManagementStore(mode, client);
  const calls = createTaskCallDebugStore(rpcClient, mode);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/runtime/tasks", component: TaskRuntimeView }]
  });
  await router.push(path);
  const app = createApp({ template: "<router-view />" });
  app.use(pinia);
  app.use(router);
  [
    ElDropdown,
    ElDropdownMenu,
    ElDropdownItem,
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
      createRuntimeViewerDataSource(
        { ...config, mode: "mock" },
        CALLER,
        new MockUserDirectorySource()
      )
    )
  );
  app.provide(taskSessionKey, session);
  app.provide(taskManagementStoreKey, ops);
  app.provide(taskCallDebugStoreKey, calls);
  const host = document.createElement("div");
  document.body.append(host);
  app.mount(host);
  let disposed = false;
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    app.unmount();
    session.dispose();
    host.remove();
  };
  disposals.push(dispose);
  await vi.waitFor(() => expect(session.directory.value).toBeDefined());
  return {
    dispose,
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

function requestText(value = "first") {
  return JSON.stringify({
    items: [
      {
        messageId: "sample",
        eventCode: "custom",
        payload: { value },
        workerSelector: { executorName: "workerId", input: "known-worker" }
      }
    ],
    waitTimeoutMillis: 3000
  });
}
async function selectCreateGroup(
  f: Awaited<ReturnType<typeof mount>>,
  project = "default"
) {
  await vi.waitFor(() => {
    expect(f.session.rpc.groups[project]?.value).toBeDefined();
    expect(button("创建并启用")).toBeDefined();
  });
  f.session.project(project).rpcCreate.groupId = "g1";
  await nextTick();
}

it("lists RPC tasks without creating and prepares only from explicit creation with captured supply", async () => {
  const f = await mount("/runtime/tasks?tab=rpc", { missing: true });
  await vi.waitFor(() => expect(f.host.textContent).toContain("rpc-1"));
  expect(f.host.textContent).toContain("broken");
  expect(f.preparationClient.prepareTask).not.toHaveBeenCalled();
  expect(button("提交调用")).toBeUndefined();
  await f.router.push("/runtime/tasks?project=default&tab=rpc&view=create");
  await selectCreateGroup(f);
  expect(f.host.querySelector('[aria-label="RPC 调用方标识"]')).toBeNull();
  const supply = [
    { poolName: "country", targetText: '{"worker.country":["CN"]}', count: 100 }
  ];
  f.session.project("default").rpcCreate.supply.supplyRows = supply;
  await nextTick();
  button("创建并启用").click();
  await vi.waitFor(() =>
    expect(f.preparationClient.prepareTask).toHaveBeenCalledTimes(1)
  );
  expect(f.preparationClient.prepareTask).toHaveBeenCalledWith(CALLER, {
    projectId: "default",
    workerGroupId: "g1",
    metadata: {},
    refill: [{ poolName: "country", target: { "worker.country": ["CN"] }, count: 100 }]
  });
  await vi.waitFor(() =>
    expect(f.router.currentRoute.value.query.view).toBeUndefined()
  );
  expect(f.session.project("default").rpcCreatedId).toBe(
    "rpc-minted-00000000000000000000000000000001"
  );
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
});
it("late preparation stays with the disposed login and PRE_REVIEW does not enable calls", async () => {
  const original = rpcChannelSlotKey("default", "1000", "g1");
  const f = await mount("/runtime/tasks?tab=rpc&view=create", { missing: true });
  await selectCreateGroup(f);
  let finish!: (taskId: string) => void;
  f.preparationClient.prepareTask.mockImplementationOnce(
    () =>
      new Promise<string>((resolve) => {
        finish = resolve;
      })
  );
  button("创建并启用").click();
  await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
  f.dispose();
  consoleIdentity.logout();
  await consoleIdentity.login(devKey("1000"));
  const next = await mount("/runtime/tasks?tab=rpc&view=debug&taskId=rpc-pending");
  vi.mocked(next.source.task).mockResolvedValue({
    ...row("rpc-pending", "g1", true),
    scoreBand: "pre_review"
  });
  await next.session.rpc.loadDetail("default", "rpc-pending");
  finish("rpc-late-id");
  await vi.waitFor(() =>
    expect(f.session.rpcPreparations[original]?.outcome).toBe("ready")
  );
  expect(next.session.rpcPreparations[original]).toBeUndefined();
  expect(button("提交调用").disabled).toBe(true);
});
it("a route user parameter cannot choose another RPC creator", async () => {
  const f = await mount("/runtime/tasks?tab=rpc&view=create&user=someone", {
    missing: true
  });
  await selectCreateGroup(f);
  button("创建并启用").click();
  await vi.waitFor(() =>
    expect(f.preparationClient.prepareTask).toHaveBeenCalledExactlyOnceWith(CALLER, {
      projectId: "default",
      workerGroupId: "g1",
      metadata: {},
      refill: []
    })
  );
});

it("Global tab remains a readonly observation view including export, while finite tab excludes PARK descriptors", async () => {
  const f = await mount("/runtime/tasks?tab=global&project=default&taskId=closed");
  await vi.waitFor(() => expect(button("导出原始成功结果 JSONL")).toBeDefined());
  expect(button("创建原始任务")).toBeUndefined();
  expect(button("创建任务")).toBeUndefined();
  expect(document.body.textContent).not.toContain("Task Call Debug");
  expect(document.body.textContent).not.toContain("核对并批准");
  expect(document.body.textContent).toContain("在 Project 视图中管理");
  await f.router.push("/runtime/tasks?tab=finite");
  await vi.waitFor(() =>
    expect(f.host.querySelector('[data-testid="finite-task-page"]')).not.toBeNull()
  );
  await vi.waitFor(() => expect(f.host.textContent).toContain("broken"));
  expect(f.host.textContent).not.toContain("rpc-1");
  expect(f.source.tasks).toHaveBeenCalledTimes(1);
  f.host.querySelector(".el-scrollbar__wrap")?.dispatchEvent(new Event("scroll"));
  await nextTick();
  expect(f.source.tasks).toHaveBeenCalledTimes(1);
  expect(f.client.createTask).not.toHaveBeenCalled();
});

it("RPC debug point-loads explicit identities and retains JSON drafts and late batch ownership", async () => {
  const f = await mount("/runtime/tasks?tab=rpc&view=debug&taskId=rpc-1");
  await vi.waitFor(() =>
    expect(f.session.rpc.detail.value.value?.taskId).toBe("rpc-1")
  );
  expect(f.source.tasks).not.toHaveBeenCalled();
  expect(JSON.parse(f.calls.draft("rpc-1").requestText).items).toEqual([]);
  let finish!: (value: unknown) => void;
  f.rpcClient.callTask.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      })
  );
  f.calls.draft("rpc-1").requestText = requestText();
  await vi.waitFor(() =>
    expect(button("提交调用").disabled, f.host.textContent ?? "").toBe(false)
  );
  button("提交调用").click();
  await vi.waitFor(() => expect(f.rpcClient.callTask).toHaveBeenCalledTimes(1));
  expect(f.rpcClient.callTask.mock.calls[0]![0]).toMatchObject({
    taskId: "rpc-1",
    request: {
      items: [{ workerSelector: { executorName: "workerId", input: "known-worker" } }]
    }
  });
  await f.router.push("/runtime/tasks?tab=rpc&view=debug&taskId=rpc-2");
  await vi.waitFor(() =>
    expect(f.session.rpc.detail.value.value?.taskId).toBe("rpc-2")
  );
  finish({ sample: { status: "succeeded", opaqueResultPayload: "first-result" } });
  await vi.waitFor(() => expect(f.calls.history("rpc-1")[0]?.state).toBe("observed"));
  expect(f.host.textContent).not.toContain("first-result");
  await f.router.push("/runtime/tasks?tab=global");
  await f.router.push("/runtime/tasks?tab=rpc&view=debug&taskId=rpc-1");
  await vi.waitFor(() => expect(f.host.textContent).toContain("first-result"));
  expect(f.calls.draft("rpc-1").requestText).toContain("first");
});

it("finite create drafts survive tabs and global view without resetting file or supplying them to another task", async () => {
  const f = await mount("/runtime/tasks?tab=finite&view=create");
  await vi.waitFor(() =>
    expect(
      document.querySelector('textarea[aria-label="Item 查询参数"]')
    ).not.toBeNull()
  );
  const draft = f.ops.draft("default", "create");
  draft.inputMode = "file";
  draft.format = "txt";
  draft.content = new TextEncoder().encode("kept").buffer;
  draft.file = new File(["kept"], "kept.txt");
  draft.payloadKey = "custom";
  await f.router.push("/runtime/tasks?tab=rpc&taskId=rpc-1");
  await f.router.push("/runtime/tasks?tab=global");
  await f.router.push("/runtime/tasks?tab=finite&view=create");
  await vi.waitFor(() => expect(document.body.textContent).toContain("kept.txt"));
  expect(f.ops.draft("default", "create").payloadKey).toBe("custom");
  await f.router.push("/runtime/tasks?tab=finite&taskId=finite&view=append");
  await vi.waitFor(() =>
    expect(f.session.finite.detail.value.value?.taskId).toBe("finite")
  );
  expect(f.ops.draft("default", "finite").content).toBeUndefined();
});

it("uses the sole declared non-default Project and keeps global observation available when directory is empty", async () => {
  const f = await mount("/runtime/tasks?tab=rpc", {
    projects: [
      { projectId: "agentforge", workerGroupSource: "declared", workerGroupIds: ["g1"] }
    ],
    identityOnly: true
  });
  await vi.waitFor(() =>
    expect(f.router.currentRoute.value.query.project).toBe("agentforge")
  );
  expect(f.host.querySelector('[aria-label="Runtime Tasks Project"]')).toBeNull();
  expect(f.host.textContent).toContain("Project · agentforge");
  await vi.waitFor(() =>
    expect(f.source.groups).toHaveBeenCalledWith("agentforge", expect.any(AbortSignal))
  );
  expect(f.preparationClient.prepareTask).not.toHaveBeenCalled();
  const empty = await mount("/runtime/tasks", { projects: [] });
  await vi.waitFor(() => expect(empty.host.textContent).toContain("未配置 Project"));
  expect(empty.source.groups).not.toHaveBeenCalled();
  await empty.router.push("/runtime/tasks?tab=global");
  await vi.waitFor(() =>
    expect(
      empty.host.querySelector('[data-testid="global-runtime-tasks"]')
    ).not.toBeNull()
  );
});

it("initial table scroll events cannot erase the saved finite-list position when a tab remounts", async () => {
  const f = await mount("/runtime/tasks?tab=finite");
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
  await f.router.push("/runtime/tasks?tab=rpc&taskId=rpc-1");
  await f.router.push("/runtime/tasks?tab=finite");
  await nextTick();
  const restored = f.host.querySelector<HTMLElement>(".el-table .el-scrollbar__wrap")!;
  restored.scrollTop = 0;
  restored.dispatchEvent(new Event("scroll"));
  await vi.waitFor(() => expect(restored.scrollTop).toBe(640));
  expect(f.session.project("default").finiteFilter.scroll).toBe(640);
});

it("Mock keeps RPC writes and batch reads disabled", async () => {
  const f = await mount("/runtime/tasks?tab=rpc&view=debug&taskId=rpc-1", {
    mode: "mock"
  });
  await vi.waitFor(() => expect(button("提交调用")?.disabled).toBe(true));
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
  expect(f.rpcClient.takeResults).not.toHaveBeenCalled();
});

it("failed RPC refresh keeps its old snapshot and time but blocks submission until recovery", async () => {
  const f = await mount("/runtime/tasks?tab=rpc&view=debug&taskId=rpc-1");
  f.calls.draft("rpc-1").requestText = requestText();
  await vi.waitFor(() =>
    expect(button("提交调用")?.disabled, f.host.textContent ?? "").toBe(false)
  );
  const old = f.session.rpc.detail.value.value,
    readAt = f.session.rpc.detail.value.readAt;
  vi.mocked(f.source.task).mockRejectedValueOnce(new Error("down"));
  await f.session.rpc.loadDetail("default", "rpc-1");
  await nextTick();
  expect(f.session.rpc.detail.value.value).toEqual(old);
  expect(f.session.rpc.detail.value.readAt).toBe(readAt);
  expect(button("提交调用").disabled).toBe(true);
  await f.session.rpc.loadDetail("default", "rpc-1");
  await vi.waitFor(() =>
    expect(button("提交调用").disabled, f.host.textContent ?? "").toBe(false)
  );
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
});

it("completed import receipts do not claim the task is still awaiting approval", async () => {
  const f = await mount("/runtime/tasks?tab=finite");
  f.ops.project("default").submission = {
    caller: CALLER,
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

it("request uncertainty reads the original batch without another submission", async () => {
  setActivePinia(createPinia());
  const client = {
    callTask: vi
      .fn()
      .mockRejectedValue(new TaskCallDebugError({ kind: "network", message: "lost" })),
    takeResults: vi.fn().mockResolvedValue({
      sample: { status: "succeeded", opaqueResultPayload: "verified" }
    })
  };
  const calls = createTaskCallDebugStore(client, "api");
  const result = await calls.send("rpc", requestText());
  expect(result.state).toBe("unconfirmed");
  await calls.takeResults("rpc", result.messageIds, result.localId);
  expect(client.takeResults).toHaveBeenCalledWith(CALLER, "rpc", ["sample"]);
  expect(result.results?.sample?.status).toBe("succeeded");
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
  expect(client.exportResults).toHaveBeenCalledWith(CALLER, "ended");
  expect(exporter.error.value).toContain("尚未就绪");
});

const twoProjects: Project[] = [
  {
    projectId: "agentforge",
    workerGroupSource: "declared",
    workerGroupIds: ["g1", "g2"]
  },
  { projectId: "messages", workerGroupSource: "declared", workerGroupIds: ["g1", "g2"] }
];
it("never silently replaces an explicit unknown Project, normalizes legacy view=global, and restores a valid session selection", async () => {
  const f = await mount("/runtime/tasks?project=unknown&tab=finite", {
    projects: twoProjects
  });
  await vi.waitFor(() => expect(f.host.textContent).toContain("不在当前目录"));
  expect(f.source.tasks).not.toHaveBeenCalled();
  expect(f.source.groups).not.toHaveBeenCalled();
  await f.router.push("/runtime/tasks?project=messages&tab=finite");
  await vi.waitFor(() =>
    expect(f.source.tasks).toHaveBeenCalledWith("messages", expect.any(AbortSignal))
  );
  await f.router.push("/runtime/tasks?view=global");
  await vi.waitFor(() => {
    expect(f.router.currentRoute.value.query.tab).toBe("global");
    expect(f.router.currentRoute.value.query.view).toBeUndefined();
  });
  await f.router.push("/runtime/tasks?tab=finite");
  await vi.waitFor(() =>
    expect(f.router.currentRoute.value.query.project).toBe("messages")
  );
});
it("prepares non-default identity-only supply after a missing Group is registered", async () => {
  const f = await mount("/runtime/tasks?project=agentforge&tab=rpc&view=create", {
    projects: twoProjects,
    identityOnly: true,
    unregistered: true,
    missing: true
  });
  await selectCreateGroup(f, "agentforge");
  expect(button("创建并启用").disabled).toBe(true);
  vi.mocked(f.source.groups).mockResolvedValue([
    {
      workerGroupId: "g1",
      workerGroup: { workerGroupId: "g1", attributes: {}, eventCodes: ["event"] },
      matching: { pools: [], functions: [{ name: "workerId", poolName: null }] }
    }
  ]);
  await f.session.rpc.loadGroups("agentforge");
  await vi.waitFor(() => expect(button("创建并启用").disabled).toBe(false));
  button("创建并启用").click();
  await vi.waitFor(() =>
    expect(f.preparationClient.prepareTask).toHaveBeenCalledExactlyOnceWith(CALLER, {
      projectId: "agentforge",
      workerGroupId: "g1",
      metadata: {},
      refill: []
    })
  );
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
});
it("Project switches isolate RPC identities and late preparation without moving the current page", async () => {
  const f = await mount("/runtime/tasks?project=agentforge&tab=rpc&view=create", {
    projects: twoProjects,
    missing: true
  });
  await selectCreateGroup(f, "agentforge");
  let release!: (taskId: string) => void;
  f.preparationClient.prepareTask.mockImplementationOnce(
    () =>
      new Promise<string>((resolve) => {
        release = resolve;
      })
  );
  button("创建并启用").click();
  await vi.waitFor(() => expect(release).toBeTypeOf("function"));
  await f.router.push("/runtime/tasks?project=messages&tab=rpc&view=create");
  await selectCreateGroup(f, "messages");
  const a = rpcChannelSlotKey("agentforge", "1000", "g1"),
    b = rpcChannelSlotKey("messages", "1000", "g1");
  release("rpc-agentforge-1");
  await vi.waitFor(() => expect(f.session.rpcPreparations[a]?.outcome).toBe("ready"));
  expect(f.session.rpcPreparations[b]).toBeUndefined();
  expect(f.router.currentRoute.value.query.project).toBe("messages");
});
it("keeps finite drafts, filters and receipts in their owning Project and ignores wrong-Project detail", async () => {
  const f = await mount("/runtime/tasks?project=agentforge&tab=finite&view=create", {
    projects: twoProjects
  });
  await vi.waitFor(() =>
    expect(document.body.textContent).toContain("Project · agentforge")
  );
  f.ops.draft("agentforge", "create").payloadKey = "original";
  f.session.project("agentforge").finiteFilter.search = "finite";
  f.ops.project("agentforge").submission = {
    caller: CALLER,
    taskId: "original",
    fileName: "a.txt",
    total: 3,
    applied: 1,
    rejected: 0,
    unconfirmed: 2,
    notSubmitted: 0,
    complete: false
  };
  await f.router.push("/runtime/tasks?project=messages&tab=finite&view=create");
  await vi.waitFor(() =>
    expect(document.body.textContent).toContain("Project · messages")
  );
  expect(f.ops.draft("messages", "create").payloadKey).not.toBe("original");
  expect(f.ops.project("messages").submission).toBeUndefined();
  expect(f.session.project("messages").finiteFilter.search).toBe("");
  vi.mocked(f.source.task).mockResolvedValue(row("foreign", "g1", false, "agentforge"));
  await f.router.push(
    "/runtime/tasks?project=messages&tab=finite&taskId=foreign&view=append"
  );
  await vi.waitFor(() =>
    expect(f.session.finite.detail.value.value?.taskId).toBe("foreign")
  );
  expect(button("追加 Items，待审核")?.disabled).toBe(true);
  expect(f.client.approveTask).not.toHaveBeenCalled();
  await f.router.push("/runtime/tasks?project=agentforge&tab=finite&view=create");
  await vi.waitFor(() => expect(f.host.textContent).toContain("本会话提交"));
  expect(f.ops.draft("agentforge", "create").payloadKey).toBe("original");
  expect(f.session.project("agentforge").finiteFilter.search).toBe("finite");
});
it("approving from a list row opens that Task and confirms its fresh snapshot", async () => {
  const confirm = vi
    .spyOn(ElMessageBox, "confirm")
    .mockRejectedValueOnce("cancel")
    .mockResolvedValueOnce("confirm" as never);
  const f = await mount("/runtime/tasks?project=default&tab=finite");
  async function approveFromList() {
    await vi.waitFor(() => expect(f.host.textContent).toContain("broken"));
    const menu = [...f.host.querySelectorAll("tr")]
      .find((tr) => tr.textContent?.includes("finite"))
      ?.querySelector<HTMLButtonElement>("button:not([disabled])[aria-haspopup]");
    expect(menu).toBeDefined();
    menu!.click();
    let item: HTMLElement | undefined;
    await vi.waitFor(() => {
      item = [
        ...document.querySelectorAll<HTMLElement>(".el-dropdown-menu__item")
      ].find((li) => li.textContent?.trim() === "核对并批准");
      expect(item?.getAttribute("aria-disabled")).not.toBe("true");
    });
    item!.click();
  }
  await approveFromList();
  await vi.waitFor(() => expect(confirm).toHaveBeenCalledTimes(1));
  expect(f.router.currentRoute.value.query.taskId).toBe("finite");
  expect(f.client.approveTask).not.toHaveBeenCalled();
  await f.router.push("/runtime/tasks?project=default&tab=finite");
  await approveFromList();
  await vi.waitFor(() =>
    expect(f.client.approveTask).toHaveBeenCalledExactlyOnceWith(CALLER, "finite", 1)
  );
  expect(confirm).toHaveBeenCalledTimes(2);
});
it("Global observation drawer links into the same route's Project view with actual Task identity", async () => {
  const f = await mount("/runtime/tasks?tab=global&project=agentforge&taskId=rpc-1", {
    projects: twoProjects
  });
  await vi.waitFor(() => expect(document.body.textContent).toContain("PARK_WHEN_IDLE"));
  const link = [...document.querySelectorAll<HTMLAnchorElement>("a")].find(
    (a) =>
      a.getAttribute("href")?.includes("/runtime/tasks") &&
      a.getAttribute("href")?.includes("project=agentforge") &&
      a.getAttribute("href")?.includes("tab=rpc") &&
      a.getAttribute("href")?.includes("taskId=rpc-1")
  );
  expect(link).toBeDefined();
  link!.click();
  await vi.waitFor(() => {
    expect(f.router.currentRoute.value.query.project).toBe("agentforge");
    expect(f.router.currentRoute.value.query.tab).toBe("rpc");
    expect(f.router.currentRoute.value.query.taskId).toBe("rpc-1");
  });
});

it("known Task debug rejects finite, foreign-Project and scenario-owned Tasks without side effects", async () => {
  const f = await mount("/runtime/tasks?tab=rpc&view=debug&taskId=rpc-1");
  f.calls.draft("rpc-1").requestText = requestText();
  await vi.waitFor(() =>
    expect(button("提交调用").disabled, f.host.textContent ?? "").toBe(false)
  );
  for (const bad of [
    row("rpc-1", "g1", false),
    row("rpc-1", "g1", true, "foreign"),
    {
      ...row("rpc-1", "g1", true),
      task: {
        ...row("rpc-1", "g1", true).task!,
        metadata: { "server.inputOwner": "sms" }
      }
    }
  ]) {
    vi.mocked(f.source.task).mockResolvedValueOnce(bad);
    await f.session.rpc.loadDetail("default", "rpc-1");
    await nextTick();
    expect(button("提交调用")).toBeUndefined();
  }
  expect(f.rpcClient.callTask).not.toHaveBeenCalled();
  expect(f.preparationClient.prepareTask).not.toHaveBeenCalled();
});

it("an uncertain preparation keeps its exact supply across group switches", async () => {
  const f = await mount("/runtime/tasks?project=default&tab=rpc&view=create", {
    missing: true
  });
  await selectCreateGroup(f);
  f.preparationClient.prepareTask.mockRejectedValueOnce(new Error("lost"));
  f.session.project("default").rpcCreate.name = "original frozen name";
  f.session.project("default").rpcCreate.supply.supplyRows = [
    { poolName: "country", targetText: "{}", count: 100 }
  ];
  button("创建并启用").click();
  const slot = rpcChannelSlotKey("default", "1000", "g1");
  await vi.waitFor(() =>
    expect(f.session.rpcPreparations[slot]?.outcome).toBe("unconfirmed")
  );
  expect(f.session.rpcPreparations[slot]?.request.refill).toEqual([
    { poolName: "country", target: {}, count: 100 }
  ]);
  f.session.project("default").rpcCreate.groupId = "g2";
  await nextTick();
  f.session.project("default").rpcCreate.name = "other draft";
  f.session.project("default").rpcCreate.groupId = "g1";
  await nextTick();
  expect(
    f.host.querySelector<HTMLInputElement>('[aria-label="RPC 任务名称"]')?.value
  ).toBe("original frozen name");
  expect(button("继续准备原 Task").disabled).toBe(false);
  expect(f.preparationClient.prepareTask).toHaveBeenCalledTimes(1);
});

it("separates running finite tasks into a progress board and shows atomic columns in the list", async () => {
  const f = await mount("/runtime/tasks?project=default&tab=finite");
  vi.mocked(f.source.tasks).mockResolvedValueOnce({
    projectId: "default",
    truncated: false,
    tasks: [
      {
        ...row("running-now", "g1", false, "default"),
        createdAtMillis: 1_700_000_080_000,
        scoreBand: "running_visible",
        stats: { total: 100, countsByTag: { "1": 40, "6": 60 } },
        retention: null
      },
      {
        ...row("done-partial", "g1", false, "default"),
        createdAtMillis: 1_700_000_000_000,
        scoreBand: "terminal",
        stats: { total: 20, countsByTag: { "5": 3, "6": 17 } },
        retention: {
          phase: "OPEN",
          terminatedAtMillis: 1_700_000_125_000,
          frozenAtMillis: null,
          expiresAtMillis: 1_700_604_925_000,
          historyId: null,
          historyExpiresAtMillis: 1_702_592_125_000
        }
      },
      {
        ...row("awaiting", "g1", false, "default"),
        createdAtMillis: 1_700_000_050_000,
        scoreBand: "pre_review",
        stats: { total: 12, countsByTag: { "1": 12 } },
        retention: null
      }
    ]
  });
  await f.session.finite.loadTasks("default");
  await nextTick();
  const board = f.host.querySelector('[data-testid="finite-running-board"]');
  expect(board?.textContent).toContain("running-now");
  expect(board?.textContent).toContain("运行中");
  expect(board?.textContent).toContain("60%");
  expect(f.host.textContent).toContain("部分失败");
  expect(f.host.textContent).toContain("17 成功");
  expect(f.host.textContent).toContain("3 失败");
  expect(f.host.textContent).toContain("2m 5s");
  expect(f.host.textContent).toContain("待审核");
  expect(f.host.textContent).toContain("12 待执行");

  await f.router.push(
    "/runtime/tasks?project=default&tab=finite&taskId=awaiting&view=append"
  );
  await vi.waitFor(() =>
    expect(f.session.finite.detail.value.value?.taskId).toBe("awaiting")
  );
  await nextTick();
  expect(document.body.textContent).toContain("概览与配置");
  expect(document.body.textContent).toContain("追加 Items");
  expect(document.body.textContent).toContain("追加 Items，待审核");
});

it("shows RPC debug batch summary and correlates request event/selector with results", async () => {
  const f = await mount("/runtime/tasks?tab=rpc&view=debug&taskId=rpc-1");
  await vi.waitFor(() =>
    expect(f.session.rpc.detail.value.value?.taskId).toBe("rpc-1")
  );
  f.calls.draft("rpc-1").requestText = requestText("correlate");
  f.rpcClient.callTask.mockResolvedValueOnce({
    sample: { status: "succeeded", opaqueResultPayload: '{"ok":true}' }
  });
  await vi.waitFor(() => expect(button("提交调用").disabled).toBe(false));
  button("提交调用").click();
  await vi.waitFor(() => expect(f.host.textContent).toContain('{"ok":true}'));
  expect(f.host.textContent).toContain("执行成功");
  expect(f.host.textContent).toContain("custom");
  expect(f.host.textContent).toContain("workerId");
  expect(f.host.querySelector('[aria-label="本批结果汇总"]')?.textContent).toContain(
    "成功"
  );
});

it("links finite drawer to tab=global and disables generic approval for credit-confirmed app-checks tasks", async () => {
  const f = await mount("/runtime/tasks?project=default&tab=finite&taskId=ac-1");
  const appCheckRow: ProjectTask = {
    ...row("ac-1", "g1", false, "default"),
    scoreBand: "pre_review",
    task: {
      ...row("ac-1", "g1", false, "default").task!,
      metadata: { "server.inputOwner": "app-checks" }
    }
  };
  vi.mocked(f.source.task).mockResolvedValueOnce(appCheckRow);
  await f.session.finite.loadDetail("default", "ac-1");
  await nextTick();
  const links = [...document.querySelectorAll<HTMLAnchorElement>("a")].map((a) => ({
    href: a.getAttribute("href") ?? "",
    text: a.textContent?.trim() ?? ""
  }));
  expect(
    links.some(
      (link) =>
        link.text.includes("在全局调度中查看") &&
        link.href.includes("/runtime/tasks") &&
        link.href.includes("tab=global") &&
        link.href.includes("taskId=ac-1")
    )
  ).toBe(true);
  expect(
    links.some(
      (link) =>
        link.text.includes("前往批量检查页确认费用并启动") &&
        link.href.includes("/app-checks/tasks/ac-1")
    )
  ).toBe(true);
  const drawerMenu = [
    ...document.querySelectorAll<HTMLButtonElement>(
      ".el-drawer button:not([disabled])[aria-haspopup]"
    )
  ][0];
  expect(drawerMenu).toBeDefined();
  drawerMenu!.click();
  await vi.waitFor(() => {
    const approveItem = [
      ...document.querySelectorAll<HTMLElement>(".el-dropdown-menu__item")
    ].find((li) => li.textContent?.includes("批量检查页确认费用"));
    expect(approveItem?.getAttribute("aria-disabled")).toBe("true");
  });
  expect(f.client.approveTask).not.toHaveBeenCalled();
});

it("displays active RPC channels in a top board with window stats and separates pending/deprecated RPC tasks", async () => {
  const f = await mount("/runtime/tasks?project=default&tab=rpc");
  vi.mocked(f.source.tasks).mockResolvedValueOnce({
    projectId: "default",
    truncated: false,
    tasks: [
      {
        ...row("rpc-live", "g1", true, "default"),
        scoreBand: "running_visible",
        stats: { total: 425, countsByTag: { "1": 5, "5": 2, "6": 418 } }
      },
      {
        ...row("rpc-pending", "g1", true, "default"),
        scoreBand: "pre_review",
        stats: { total: 0, countsByTag: {} }
      },
      {
        ...row("rpc-old", "g2", true, "default"),
        scoreBand: "terminal",
        stats: { total: 100, countsByTag: { "5": 4, "6": 96 } }
      }
    ]
  });
  await f.session.rpc.loadTasks("default");
  await nextTick();

  const board = f.host.querySelector('[data-testid="rpc-active-board"]');
  expect(board?.textContent).toContain("rpc-live");
  expect(board?.textContent).toContain("服务中");
  expect(board?.textContent).toContain("在途");
  expect(board?.textContent).toContain("418");
  expect(board?.textContent).toContain("复制 ID");
  expect(board?.textContent).not.toContain("rpc-pending");
  expect(board?.textContent).not.toContain("rpc-old");

  const tableText = f.host.querySelector(".el-table")?.textContent ?? "";
  expect(tableText).toContain("rpc-pending");
  expect(tableText).toContain("rpc-old");
  expect(tableText).toContain("待启用");
  expect(tableText).toContain("已停用");
  expect(tableText).toContain("96 成功");
  expect(tableText).toContain("4 失败");

  vi.mocked(f.source.task).mockResolvedValueOnce({
    ...row("rpc-live", "g1", true, "default"),
    scoreBand: "running_visible",
    stats: { total: 425, countsByTag: { "1": 5, "5": 2, "6": 418 } }
  });
  await f.router.push("/runtime/tasks?project=default&tab=rpc&taskId=rpc-live");
  await vi.waitFor(() =>
    expect(f.session.rpc.detail.value.value?.taskId).toBe("rpc-live")
  );
  await nextTick();
  expect(document.body.textContent).toContain("/api/v1/tasks/rpc-live/items:call");
  expect(document.body.textContent).toContain("当前 Redis 保留窗口统计");
});
