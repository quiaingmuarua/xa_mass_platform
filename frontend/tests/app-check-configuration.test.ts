import { webcrypto } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App } from "vue";
import { createRouter, createMemoryHistory, RouterView } from "vue-router";
import { ElButton, ElInput, ElSelect, ElOption } from "element-plus";
import AppCheckPage from "../src/app-checks/AppCheckPage.vue";
import { appCheckContextKey, createAppCheckContext } from "../src/app-checks/context";
import {
  applicationConfiguration,
  savedTaskConfiguration,
  scenarioConfiguration,
  simulationSection
} from "../src/app-checks/configuration";
import {
  catalogSchema,
  detailSchema,
  rangeExamples,
  type Catalog,
  type CheckDetail
} from "../src/app-checks/model";
import {
  MockAppCheckTaskSource,
  mockCatalog
} from "../src/app-checks/mock-task-source";
import { AppCheckCreationUnconfirmed } from "../src/app-checks/task-source";
import { inspectImport } from "../src/app-checks/import-model";

let app: App | undefined;
let context: ReturnType<typeof createAppCheckContext>;
let source: MockAppCheckTaskSource;
const fetcher = vi.fn();
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("crypto", webcrypto);
  vi.stubGlobal("fetch", fetcher.mockReset());
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  source = new MockAppCheckTaskSource();
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  context?.dispose();
  document.body.replaceChildren();
  expect(fetcher).not.toHaveBeenCalled();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
async function settle() {
  await vi.advanceTimersByTimeAsync(250);
  await nextTick();
}
async function mount(
  path = "/app-checks?view=configuration",
  mode: "api" | "mock" = "mock"
) {
  if (mode === "api") Object.defineProperty(source, "mode", { value: "api" });
  context = createAppCheckContext(source);
  await context.load();
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ["/app-checks", "/app-checks/tasks/:taskId"].map((path) => ({
      path,
      component: AppCheckPage
    }))
  });
  await router.push(path);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp({ render: () => h(RouterView) })
    .use(router)
    .provide(appCheckContextKey, context);
  [ElButton, ElInput, ElSelect, ElOption].forEach((component) => app!.use(component));
  app.mount(host);
  await settle();
  return { host, router, session: context.session };
}
function button(text: string, scope: ParentNode = document) {
  const node = [...scope.querySelectorAll<HTMLButtonElement>("button")].find(
    (node) => node.textContent?.trim() === text
  );
  expect(node, text).toBeDefined();
  return node!;
}
function selectApp(appId: string) {
  const select = document.querySelector<HTMLSelectElement>('[aria-label="配置应用"]')!;
  select.focus();
  select.value = appId;
  select.dispatchEvent(new Event("change", { bubbles: true }));
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

describe("App Checks read-only configuration", () => {
  it("maps API values without accepting Mock policy or inventing missing task settings", async () => {
    const apiCatalog = catalogSchema.parse({
      ...mockCatalog,
      limits: { numbersPerImport: 321, importFileBytes: 2 * 1024 * 1024 }
    });
    expect(apiCatalog).not.toHaveProperty("mockPolicies");
    const value = applicationConfiguration(apiCatalog, "app-a", "api")!;
    expect(
      scenarioConfiguration(apiCatalog, "api")
        .rows.slice(2)
        .map((row) => row.value)
    ).toEqual(["321 个", "2 MiB"]);
    expect(value.binding.source).toBe("catalog");
    expect(value).not.toHaveProperty("policy");
    expect(value).not.toHaveProperty("imports");
    expect(value.pool.rows).toEqual([]);
    expect(value.pool.unavailable).toContain("当前接口未提供");
    expect(applicationConfiguration(mockCatalog, "app-a", "api")!.pool.rows).toEqual(
      []
    );
    const detail = detailSchema.parse(await source.loadTask("check-unregistered"));
    expect(detail.task).not.toHaveProperty("mockPolicy");
    expect(savedTaskConfiguration(detail.task, "api").policy.unavailable).toContain(
      "当前接口未提供"
    );
    expect(simulationSection(detail.task.simulation, "task").rows[1].value).toContain(
      "100%"
    );
    expect(simulationSection({ bad: true }, "task").unavailable).toContain("无法解析");
  });
  it("keeps task policy and simulation snapshots independent from returned Catalog and request objects", async () => {
    const simulation = structuredClone(mockCatalog.simulationExample);
    const { taskId } = await source.createTask({
      requestId: "config-snapshot",
      appId: "app-b",
      country: "GB",
      simulation
    });
    const catalog = await source.catalog();
    catalog.mockPolicies!["app-b"].pool.maxCount = 90;
    simulation.delayMs = [0, 0];
    const task = (await source.loadTask(taskId)).task;
    expect(task.mockPolicy?.priority).toBe(50);
    expect(task.simulation).toEqual(mockCatalog.simulationExample);
    expect(savedTaskConfiguration(task, "mock").binding.rows[1].value).toBe(
      "app-b-sim"
    );
  });
  it("opens configuration directly without loading tasks and handles invalid apps and history", async () => {
    const list = vi.spyOn(source, "listTasks");
    const { router, host } = await mount("/app-checks?view=configuration&app=missing");
    expect(host.textContent).toContain("不在当前 Catalog");
    expect(
      host.querySelector('[aria-label="App Checks 通用配置"]')?.textContent
    ).toContain("100,000 个");
    const common = host.querySelector(
      '[aria-label="App Checks 通用配置"]'
    )?.textContent;
    expect(list).not.toHaveBeenCalled();
    selectApp("app-b");
    await settle();
    expect(router.currentRoute.value.query.app).toBe("app-b");
    expect(document.activeElement).toBe(host.querySelector('[aria-label="配置应用"]'));
    expect(host.textContent).toContain("app-b-sim");
    expect(host.querySelector('[aria-label="App Checks 通用配置"]')?.textContent).toBe(
      common
    );
    expect(
      host.querySelector('[aria-label="App 运行配置"]')?.textContent
    ).not.toContain("单次号码上限");
    expect(host.textContent).not.toContain("任务默认策略");
    expect(host.textContent).toContain("Mock 演示数据");
    expect(host.querySelectorAll("input, textarea")).toHaveLength(0);
    router.back();
    await settle();
    expect(host.textContent).toContain("不在当前 Catalog");
    router.forward();
    await settle();
    expect(list).not.toHaveBeenCalled();
    expect(
      host.querySelector<HTMLDetailsElement>(".checks-configuration-simulation")?.open
    ).toBe(false);
  });
  it("retains the selected app when a slow refresh finishes and preserves old data on failure", async () => {
    const { host } = await mount();
    const before = context.configurationRead.value.readAt;
    const pending = deferred<Catalog>();
    vi.spyOn(source, "catalog").mockReturnValueOnce(pending.promise);
    button("刷新配置").click();
    await settle();
    selectApp("app-b");
    await settle();
    pending.reject(new Error("offline"));
    await settle();
    expect(host.textContent).toContain("已保留上次快照");
    expect(host.textContent).toContain("app-b-sim");
    expect(context.state.value.status).toBe("enabled");
    expect(context.configurationRead.value.readAt).toBe(before);
    button("刷新配置").click();
    await settle();
    expect(context.configurationRead.value.error).toBe("");
    expect(context.configurationRead.value.readAt).toBeGreaterThan(before);
    expect(context.session.configurationApp).toBe("app-b");
  });
  it("keeps the list instance, filters and scrolling across tabs without rereading", async () => {
    const list = vi.spyOn(source, "listTasks");
    const { router, host, session } = await mount("/app-checks");
    const table = host.querySelector(".checks-task-table");
    session.filters.app = "app-b";
    await settle();
    const viewport = host.querySelector<HTMLElement>(".el-scrollbar__wrap")!;
    viewport.scrollTop = 125;
    viewport.dispatchEvent(new Event("scroll"));
    await settle();
    host
      .querySelector<HTMLAnchorElement>(".checks-workspace-tabs a:nth-child(2)")!
      .click();
    await settle();
    selectApp("app-b");
    await settle();
    await router.push("/app-checks");
    await settle();
    expect(host.querySelector(".checks-task-table")).toBe(table);
    expect(session.filters.app).toBe("app-b");
    expect(session.listTableScrollTop).toBe(125);
    expect(viewport.scrollTop).toBe(125);
    expect(list).toHaveBeenCalledTimes(1);
    host
      .querySelector<HTMLAnchorElement>(".checks-workspace-tabs a:nth-child(2)")!
      .click();
    await settle();
    expect(router.currentRoute.value.query.app).toBe("app-b");
  });
  it("freezes the draft simulation and full request across Catalog changes and explicit creation checks", async () => {
    const create = vi
      .spyOn(source, "createTask")
      .mockRejectedValueOnce(new AppCheckCreationUnconfirmed());
    const imports = vi.spyOn(source, "importNumbers");
    const { router, session } = await mount("/app-checks?view=create");
    session.draft.text = "8613800000001";
    session.draft.report = inspectImport(session.draft.text, {
      format: "txt",
      country: "CN",
      header: false,
      column: 0,
      limit: 100000
    });
    await settle();
    const original = structuredClone(mockCatalog.simulationExample);
    const changed = structuredClone(mockCatalog);
    changed.simulationExample = { ranges: rangeExamples.全失败, delayMs: [10, 10] };
    vi.spyOn(source, "catalog").mockResolvedValue(changed);
    button("查看应用配置").click();
    await settle();
    button("刷新配置").click();
    await settle();
    button("返回创建草稿").click();
    await settle();
    expect(session.draft.simulation).toEqual(original);
    document
      .querySelector("#app-check-create")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    expect(session.draft.uncertain).toBe(true);
    expect(create.mock.calls[0][0].simulation).toEqual(original);
    const frozen = create.mock.calls[0][0];
    await router.push("/app-checks?view=configuration");
    await settle();
    await context.load(true);
    router.back();
    await settle();
    button("使用原请求核对创建").click();
    await settle();
    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[1][0]).toEqual(frozen);
    expect(session.draft.knownTaskId).toBe("mock-check-1");
    expect(imports).not.toHaveBeenCalled();
    expect((await source.loadTask("mock-check-1")).task.state).toBe("pre_review");
  });
  it("shows saved task values instead of current defaults and returns from config to its drawer", async () => {
    const { host, router } = await mount("/app-checks/tasks/check-unregistered");
    const section = document.querySelector<HTMLDetailsElement>(
      ".checks-task-configuration"
    )!;
    expect(section.open).toBe(false);
    section.open = true;
    expect(section.textContent).toContain("100%");
    expect(section.textContent).toContain("0–0 ms");
    expect(section.textContent).toContain("任务保存值");
    button("查看当前应用配置").click();
    await settle();
    expect(router.currentRoute.value.query).toMatchObject({
      view: "configuration",
      app: "app-a"
    });
    expect(document.activeElement).toBe(
      host.querySelector(".checks-configuration-page h1")
    );
    router.back();
    await settle();
    expect(router.currentRoute.value.params.taskId).toBe("check-unregistered");
    expect(document.activeElement?.closest(".checks-preview-drawer")).not.toBeNull();
    expect(document.querySelector(".checks-task-configuration")?.textContent).toContain(
      "0–0 ms"
    );
  });
  it("does not let a late preview response or drawer close steal configuration focus", async () => {
    const pending = deferred<CheckDetail>();
    vi.spyOn(source, "loadTask").mockReturnValueOnce(pending.promise);
    const { router, host } = await mount("/app-checks/tasks/check-live");
    await router.push("/app-checks?view=configuration&app=app-b");
    await settle();
    pending.resolve(await new MockAppCheckTaskSource().loadTask("check-live"));
    await settle();
    expect(document.activeElement).toBe(
      host.querySelector(".checks-configuration-page h1")
    );
    expect(host.textContent).toContain("app-b-sim");
  });
  it("API configuration gaps do not block task preview or expose local policy defaults", async () => {
    const { host, router } = await mount("/app-checks?view=configuration", "api");
    expect(host.textContent).toContain("当前接口未提供：Pool 身份");
    expect(host.textContent).not.toContain("assignment-window");
    expect(host.textContent).not.toContain("365 天");
    await router.push("/app-checks/tasks/check-mixed");
    await settle();
    expect(document.querySelectorAll(".checks-results-table tbody tr")).toHaveLength(4);
    const section = document.querySelector(".checks-task-configuration")!;
    expect(section.textContent).toContain("当前接口未提供");
    expect(section.textContent).toContain("实际 WorkerGroup");
    expect(section.textContent).not.toContain("365 天");
  });
});
