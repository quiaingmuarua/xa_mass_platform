import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { ElButton, ElTable, ElTableColumn } from "element-plus";
import { captureTaskCaller, consoleIdentity } from "@/console-identity";
import { ApiTaskHistorySource, type HistoryEntry } from "@/task-history/source";
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
async function mount(path = "/app-checks?view=history&historyId=one") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/app-checks",
        component: Workspace,
        props: {
          projectId: "app-checks",
          kind: "app-checks",
          runtimePath: "/app-checks",
          source: new ApiTaskHistorySource()
        }
      }
    ]
  });
  await router.push(path);
  await router.isReady();
  const element = document.createElement("div");
  document.body.append(element);
  app = createApp({ render: () => h(RouterView) }).use(router);
  [ElButton, ElTable, ElTableColumn].forEach((component) => app!.use(component));
  app.mount(element);
  await settle();
  return router;
}
function button(label: string) {
  return [...document.querySelectorAll<HTMLButtonElement>("button")].find(
    (value) => value.textContent?.trim() === label
  )!;
}

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
it("keeps the old snapshot and time on refresh failure and disables dependent export", async () => {
  await mount();
  const before = document.querySelector(".history-detail")?.textContent;
  expect(before).toContain("历史 one");
  fetcher.mockImplementation(async () => json({}, 503));
  button("重新读取详情").click();
  await settle();
  expect(document.querySelector(".history-detail")?.textContent).toContain("历史 one");
  expect(document.querySelector(".history-detail")?.textContent).toContain("详情过期");
  expect(button("导出全部有效结果 CSV").disabled).toBe(true);
});
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
