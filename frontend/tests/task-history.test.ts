import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import {
  ElButton,
  ElTable,
  ElTableColumn,
  ElDrawer,
  ElTabs,
  ElTabPane
} from "element-plus";
import { captureTaskCaller, consoleIdentity } from "@/console-identity";
import {
  ApiTaskHistorySource,
  MockTaskHistorySource,
  type HistoryEntry,
  type TaskHistorySource
} from "@/task-history/source";
import { MockUserDirectorySource } from "@/user-directory-source";
import { historyLocation, matchesRuntime } from "@/task-history/navigation";
import { routeAllowed } from "@/router/access";
import { consoleRoutes } from "@/router";
import { ProjectRequestError } from "@/task-management/projects";
import { ADMIN, OPERATOR_A } from "./identity-fixture";
import Workspace from "@/task-history/TaskHistoryWorkspace.vue";
import { ApiAppCheckTaskSource } from "@/app-checks/task-source";
import { ApiMessageTaskSource } from "@/message-campaigns/api-task-source";
import { devKey } from "./identity-fixture";

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status });
const entry = (id = "one"): HistoryEntry => ({
  historyId: id,
  taskId: `task-${id}`,
  createdAtMillis: 1,
  task: {
    taskId: `task-${id}`,
    projectId: "app-checks",
    workerGroupId: "retired-group",
    idleDisposition: "CLOSE_WHEN_IDLE",
    name: `历史 ${id}`,
    config: {},
    refill: [],
    metadata: { userId: "1000", "server.inputOwner": "app-checks" }
  },
  scoreBand: "terminal",
  terminatedAtMillis: 1000,
  frozenAtMillis: 2000,
  publishedAtMillis: 3000,
  expiresAtMillis: 9999999999999,
  counts: { total: 2, countsByTag: { "1": 1, "6": 1 } },
  itemRows: 2,
  resultRows: 1
});
const fetcher =
  vi.fn<(url: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
let app: App | undefined;
async function settle() {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
beforeEach(async () => {
  await consoleIdentity.login(devKey("1000"));
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  fetcher.mockReset().mockImplementation(async (url) => {
    const path = String(url);
    if (path.startsWith("/api/v1/storage/tasks?"))
      return json({ entries: [entry(), entry("two")], nextCursor: null });
    if (path.includes("/items?"))
      return json({
        entries: [
          {
            messageId: "pending",
            item: { payload: { value: "原始输入" } },
            state: { band: "active" },
            result: null
          }
        ],
        nextCursor: null
      });
    if (path.endsWith("/results"))
      return json({
        taskId: "task-one",
        results: [
          {
            messageId: "item-one",
            seed: "a",
            matched: true,
            resultStatus: "succeeded",
            details: { md5: "0cc175b9c0f1b6a831c399e269772661" }
          }
        ],
        truncated: false
      });
    if (path === "/api/v1/storage/tasks/one") return json(entry());
    if (path === "/api/v1/storage/tasks/two") return json(entry("two"));
    throw new Error(`Unexpected Runtime access: ${path}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  document.body.innerHTML = "";
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
async function mount(
  path = "/app-checks?view=history&historyId=one",
  source: TaskHistorySource = new ApiTaskHistorySource()
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/app-checks",
        component: Workspace,
        props: {
          projectId: "app-checks",
          runtimePath: "/app-checks",
          source
        }
      },
      { path: "/storage/tasks", component: Workspace, props: { source } },
      { path: "/runtime/tasks", component: { template: "<p>Runtime target</p>" } }
    ]
  });
  await router.push(path);
  await router.isReady();
  const element = document.createElement("div");
  document.body.append(element);
  app = createApp({ render: () => h(RouterView) }).use(router);
  [ElButton, ElTable, ElTableColumn, ElDrawer, ElTabs, ElTabPane].forEach((component) =>
    app!.use(component)
  );
  app.mount(element);
  await settle();
  return router;
}
function button(label: string) {
  return [...document.querySelectorAll<HTMLButtonElement>("button")].find(
    (value) => value.textContent?.trim() === label
  )!;
}

it("lists all current-scope Projects without reading Runtime and submits only supported filters", async () => {
  const router = await mount("/storage/tasks");
  const first = new URL(String(fetcher.mock.calls[0][0]), "https://test.invalid");
  expect(first.searchParams.has("projectId")).toBe(false);
  expect(first.searchParams.get("limit")).toBe("20");
  for (const [name, value] of [
    ["历史 Project ID", "retired-project"],
    ["历史创建者 ID", "old-user"],
    ["历史 Task ID", "past-task"]
  ]) {
    const field = document.querySelector<HTMLInputElement>(
      'input[aria-label="' + name + '"]'
    )!;
    field.value = value;
    field.dispatchEvent(new Event("input", { bubbles: true }));
  }
  button("查询").click();
  await settle();
  expect(router.currentRoute.value.query).toMatchObject({
    project: "retired-project",
    userId: "old-user",
    taskId: "past-task"
  });
  await vi.waitFor(() =>
    expect(
      Object.fromEntries(
        new URL(String(fetcher.mock.calls.at(-1)![0]), "https://test.invalid")
          .searchParams
      )
    ).toEqual({
      projectId: "retired-project",
      userId: "old-user",
      taskId: "past-task",
      limit: "20"
    })
  );
  expect(fetcher.mock.calls.every(([url]) => String(url).includes("/storage/"))).toBe(
    true
  );
});

it("keeps each filter page separate and lets browser Back restore its URL", async () => {
  const original = fetcher.getMockImplementation()!;
  fetcher.mockImplementation((url, init) => {
    if (String(url).startsWith("/api/v1/storage/tasks?")) {
      const next = String(url).includes("cursor=page-two");
      return Promise.resolve(
        json({
          entries: [entry(next ? "two" : "one")],
          nextCursor: next ? null : "page-two"
        })
      );
    }
    return original(url, init);
  });
  const router = await mount("/storage/tasks?project=retired-project");
  button("下一页").click();
  await settle();
  expect(router.currentRoute.value.query.cursor).toBe("page-two");
  await vi.waitFor(() =>
    expect(document.querySelector(".history-workspace")!.textContent).toContain(
      "历史 two"
    )
  );
  router.back();
  await vi.waitFor(() =>
    expect(router.currentRoute.value.query.cursor).toBeUndefined()
  );
  await settle();
  expect(document.querySelector(".history-workspace")!.textContent).toContain(
    "历史 one"
  );
});

it("opening a drawer preserves an unsubmitted filter draft", async () => {
  await mount("/storage/tasks");
  const input = document.querySelector<HTMLInputElement>(
    '[aria-label="历史 Task ID"]'
  )!;
  input.value = "not-submitted";
  input.dispatchEvent(new Event("input", { bubbles: true }));
  button("历史 one").click();
  await vi.waitFor(() =>
    expect(document.querySelector(".history-detail")!.textContent).toContain("历史 one")
  );
  expect(input.value).toBe("not-submitted");
});

it("keeps Scenario scope locked even if an operator edits URL filters", async () => {
  await consoleIdentity.login(devKey("1001"));
  await mount("/app-checks?view=history&project=default&userId=1000");
  const query = new URL(String(fetcher.mock.calls[0][0]), "https://test.invalid")
    .searchParams;
  expect(query.get("projectId")).toBe("app-checks");
  expect(query.has("userId")).toBe(false);
  expect(document.querySelector('[aria-label="历史 Project ID"]')).toBeNull();
  expect(document.querySelector('[aria-label="历史创建者 ID"]')).toBeNull();
});

it("routes administrators to Storage and keeps operators in Scenario history", async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: consoleRoutes
  });
  const route = router.resolve("/storage/tasks");
  const admin = consoleIdentity.requireCurrent();
  expect(route.meta.section).toBe("Storage");
  expect(routeAllowed(admin, route)).toBe(true);
  expect(historyLocation(admin, "app-checks", "saved")).toEqual({
    path: "/storage/tasks",
    query: { project: "app-checks", historyId: "saved" }
  });
  await consoleIdentity.login(devKey("1001"));
  const operator = consoleIdentity.requireCurrent();
  expect(routeAllowed(operator, route)).toBe(false);
  expect(historyLocation(operator, "app-checks", "saved")).toEqual({
    path: "/app-checks",
    query: { view: "history", historyId: "saved" }
  });
});

it("does not read Runtime until asked and only navigates to matching creation identity", async () => {
  const source = new ApiTaskHistorySource();
  const saved = entry();
  const current = {
    taskId: saved.taskId,
    createdAtMillis: saved.createdAtMillis,
    task: saved.task,
    retention: null,
    scoreBand: "terminal" as const
  };
  const runtime = vi.spyOn(source, "runtimeTask").mockResolvedValue(current);
  const router = await mount("/storage/tasks?historyId=one", source);
  expect(runtime).not.toHaveBeenCalled();
  button("查看 Runtime 记录").click();
  await settle();
  expect(runtime).toHaveBeenCalledWith(
    expect.objectContaining({ userId: "1000" }),
    "app-checks",
    "task-one"
  );
  await vi.waitFor(() =>
    expect(router.currentRoute.value.fullPath).toBe(
      "/runtime/tasks?project=app-checks&tab=finite&taskId=task-one"
    )
  );
  expect(matchesRuntime({ ...saved, createdAtMillis: null }, current)).toBe(false);
  expect(matchesRuntime(saved, { ...current, createdAtMillis: 2 })).toBe(false);
  expect(
    matchesRuntime(
      { ...saved, task: { ...saved.task, metadata: { "server.creationKey": "old" } } },
      current
    )
  ).toBe(false);
});

it.each(["missing", "mismatch", "unavailable"])(
  "keeps history when Runtime is %s",
  async (state) => {
    const source = new ApiTaskHistorySource();
    const saved = entry();
    vi.spyOn(source, "runtimeTask").mockImplementation(async () => {
      if (state === "missing") throw new ProjectRequestError(400, 12002);
      if (state === "unavailable") throw new ProjectRequestError(503);
      return {
        taskId: saved.taskId,
        task: saved.task,
        createdAtMillis: 2,
        retention: null,
        scoreBand: "terminal"
      };
    });
    const router = await mount("/storage/tasks?historyId=one", source);
    button("查看 Runtime 记录").click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/storage/tasks");
    const text = document.querySelector(".history-detail")!.textContent;
    expect(text).toContain(
      state === "missing"
        ? "Runtime 记录已不存在"
        : state === "mismatch"
          ? "创建身份与历史不同"
          : "不能判断记录已删除"
    );
    expect(text).toContain("历史 one");
  }
);

it("ignores a late Runtime navigation after selecting a different history", async () => {
  const source = new ApiTaskHistorySource();
  let release!: (value: Awaited<ReturnType<typeof source.runtimeTask>>) => void;
  vi.spyOn(source, "runtimeTask").mockImplementation(
    () =>
      new Promise((resolve) => {
        release = resolve;
      })
  );
  const router = await mount("/storage/tasks?historyId=one", source);
  button("查看 Runtime 记录").click();
  await settle();
  await router.push("/storage/tasks?historyId=two");
  await settle();
  const saved = entry();
  release({
    taskId: saved.taskId,
    task: saved.task,
    createdAtMillis: 1,
    retention: null,
    scoreBand: "terminal"
  });
  await settle();
  expect(router.currentRoute.value.fullPath).toBe("/storage/tasks?historyId=two");
});

it("exports through the saved business Owner without using the current page or Runtime", async () => {
  const original = fetcher.getMockImplementation()!;
  fetcher.mockImplementation((url, init) =>
    String(url).includes("results:export")
      ? Promise.resolve(new Response("完整 CSV"))
      : original(url, init)
  );
  const OriginalURL = URL;
  vi.stubGlobal(
    "URL",
    class extends OriginalURL {
      static createObjectURL = vi.fn(() => "blob:test-history");
      static revokeObjectURL = vi.fn();
    }
  );
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  await mount("/storage/tasks?project=removed&cursor=page-two&historyId=one");
  button("导出全部有效结果 CSV").click();
  await settle();
  expect(fetcher.mock.calls.at(-1)![0]).toBe(
    "/api/v1/storage/app-checks/tasks/one/results:export?filter=all"
  );
  expect(fetcher.mock.calls.at(-1)![1]?.method).toBe("POST");
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:test-history");
});

it("Mock supports a global paged directory including retired Projects and preserves access boundaries", async () => {
  const source = new MockTaskHistorySource(new MockUserDirectorySource());
  source.seedExamples();
  const all = await source.list(ADMIN, {});
  expect(all.entries.some((row) => row.task.projectId === "retired-project")).toBe(
    true
  );
  expect((await source.list(ADMIN, { userId: "1000" })).entries).toHaveLength(0);
  await expect(source.list(OPERATOR_A, {})).rejects.toMatchObject({ status: 403 });
  await expect(
    source.list(OPERATOR_A, { projectId: "app-checks", userId: "1000" })
  ).rejects.toMatchObject({ status: 403 });
  const own = await source.list(OPERATOR_A, { projectId: "app-checks" });
  expect(own.entries).toHaveLength(1);
  expect(own.entries[0].task.metadata.userId).toBe("1001");
  expect(fetcher).not.toHaveBeenCalled();
});

it("uses only Storage APIs with the captured Bearer and displays saved state without a Runtime Task", async () => {
  await mount();
  expect(document.querySelector(".history-detail")?.textContent).toContain(
    "retired-group"
  );
  expect(document.body.textContent).toContain("未观察到结果");
  expect(document.body.textContent).toContain("0cc175b9c0f1b6a831c399e269772661");
  for (const [path, init] of fetcher.mock.calls) {
    expect(String(path)).toContain("/api/v1/storage/");
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      `Bearer ${devKey("1000")}`
    );
  }
});

it("does not publish a late historical response into a new login session", async () => {
  let release!: (value: Response) => void;
  const original = fetcher.getMockImplementation()!;
  fetcher.mockImplementation((url, init) =>
    String(url) === "/api/v1/storage/tasks/one"
      ? new Promise((resolve) => {
          release = resolve;
        })
      : original(url, init)
  );
  await mount("/storage/tasks?historyId=one");
  await consoleIdentity.login(devKey("1001"));
  release(json(entry()));
  await settle();
  expect(document.querySelector(".history-detail")!.textContent).not.toContain(
    "历史 one"
  );
});

it("Mock keyset pagination still advances after a prior-page row expires", async () => {
  let now = 2000000000000;
  const source = new MockTaskHistorySource(new MockUserDirectorySource(), () => now);
  for (let index = 0; index < 25; index++)
    source.capture(
      "retired-project",
      {
        taskId: "saved-" + index,
        workerGroupId: "gone",
        createdAtMillis: 1,
        userId: "1001",
        retention: {
          phase: "RELEASED",
          terminatedAtMillis: now - index - 100,
          frozenAtMillis: now - 10,
          expiresAtMillis: now - 5,
          historyId: null,
          historyExpiresAtMillis: now + (index < 20 ? 1 : 1000)
        }
      },
      []
    );
  const first = await source.list(ADMIN, {});
  expect(first.entries).toHaveLength(20);
  now += 2;
  const next = await source.list(ADMIN, {}, first.nextCursor!);
  expect(next.entries).toHaveLength(5);
  expect(next.nextCursor).toBeNull();
  expect(
    next.entries.some((row) =>
      first.entries.some((old) => old.historyId === row.historyId)
    )
  ).toBe(false);
  await expect(source.get(ADMIN, first.entries[0].historyId)).rejects.toMatchObject({
    status: 400
  });
});
it.each([400, 403, 503])(
  "keeps the old snapshot and time on refresh %s and disables dependent export",
  async (status) => {
    await mount();
    const before = document.querySelector(".history-detail")?.textContent;
    expect(before).toContain("历史 one");
    fetcher.mockImplementation(async () => json({}, status));
    button("重新读取详情").click();
    await settle();
    expect(document.querySelector(".history-detail")?.textContent).toContain(
      "历史 one"
    );
    expect(document.querySelector(".history-detail")?.textContent).toContain(
      "详情过期"
    );
    expect(button("导出全部有效结果 CSV").disabled).toBe(true);
  }
);
it("does not let an old detail response replace the newly selected history", async () => {
  let resolve!: (value: Response) => void;
  const original = fetcher.getMockImplementation()!;
  fetcher.mockImplementation((url, init) =>
    String(url) === "/api/v1/storage/tasks/one"
      ? new Promise((done) => {
          resolve = done;
        })
      : original(url, init)
  );
  const router = await mount();
  await router.push("/app-checks?view=history&historyId=two");
  await settle();
  expect(document.querySelector(".history-detail")?.textContent).toContain("历史 two");
  resolve(json(entry()));
  await settle();
  expect(document.querySelector(".history-detail")?.textContent).not.toContain(
    "历史 one"
  );
  await router.back();
  await settle();
  expect(router.currentRoute.value.query.historyId).toBe("one");
});
it("preserves raw history when the saved Scenario contract cannot be interpreted", async () => {
  const original = fetcher.getMockImplementation()!;
  fetcher.mockImplementation((url, init) =>
    String(url).includes("/storage/app-checks/")
      ? Promise.resolve(json({}, 400))
      : original(url, init)
  );
  await mount();
  expect(document.querySelector(".history-detail")?.textContent).toContain("原始输入");
  expect(document.body.textContent).toContain("业务预览读取失败");
  expect(button("下载原始成功 JSONL").disabled).toBe(false);
  expect(button("导出全部有效结果 CSV").disabled).toBe(true);
});
it("leaves released business details readable but never falls back to a hidden historical preview", async () => {
  const runtime = {
    taskId: "task-one",
    createdAtMillis: 1,
    scoreBand: "terminal",
    task: entry().task,
    retention: {
      phase: "RELEASED",
      terminatedAtMillis: 1000,
      frozenAtMillis: 2000,
      expiresAtMillis: 7000,
      historyId: "one",
      historyExpiresAtMillis: 30000
    }
  };
  fetcher.mockImplementation(async (url) => {
    if (String(url) === "/api/v1/projects/app-checks/tasks/task-one")
      return json(runtime);
    if (String(url) === "/api/v1/projects/messages/tasks/task-one")
      return json({
        ...runtime,
        task: {
          ...runtime.task,
          projectId: "messages",
          metadata: { ...runtime.task.metadata, "server.inputOwner": "messages" }
        }
      });
    if (String(url).endsWith("/tasks:stats"))
      return json({ "task-one": { total: 2, countsByTag: { "6": 2 } } });
    throw new Error("Released Runtime must not request a Result");
  });
  const caller = captureTaskCaller();
  expect(
    (await new ApiAppCheckTaskSource().loadTask(caller, "task-one")).results
  ).toEqual([]);
  expect(
    (await new ApiMessageTaskSource(() => undefined).loadTask(caller, "task-one"))
      .results
  ).toEqual([]);
  expect(fetcher).toHaveBeenCalledTimes(4);
});
