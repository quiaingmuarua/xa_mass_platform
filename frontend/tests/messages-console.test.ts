import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, nextTick, type App as VueApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import {
  ElAlert,
  ElBreadcrumb,
  ElBreadcrumbItem,
  ElButton,
  ElConfigProvider,
  ElDrawer,
  ElIcon,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag
} from "element-plus";
import App from "../src/App.vue";
import { consoleRoutes } from "../src/router";

let mounted: VueApp | undefined;
afterEach(() => {
  mounted?.unmount();
  mounted = undefined;
  document.body.replaceChildren();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});
const catalog = {
  projectId: "messages",
  version: "0.3.0-preview",
  applications: [{ id: "demo", label: "Demo", workerGroupId: "demo-sim" }],
  countries: ["CN", "US", "GB"].map((id) => ({ id })),
  limits: { recipientsPerImport: 100000, importFileBytes: 10485760 }
};
const task = {
  taskId: "finite-task",
  name: "Saved task",
  createdAtMillis: 1780000000000,
  workerGroupId: "demo-sim",
  managed: false,
  state: "terminal",
  recipientCountry: "CN",
  senderCountry: "US",
  body: "{}",
  sendTotal: 1000,
  deliveredCount: 900
};
const result = {
  messageId: "m",
  recipientId: "+8613800000001",
  resultStatus: "succeeded",
  status: "REPLIED",
  phone: "+12025550123",
  reply: "latest reply"
};
function installApi() {
  let created: Record<string, unknown> | undefined;
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/v1/sms/")) return new Response("{}", { status: 503 });
    let value: unknown;
    if (path.endsWith("/catalog")) value = catalog;
    else if (init?.method === "POST" && path.endsWith("/tasks")) {
      created = {
        ...task,
        ...JSON.parse(String(init.body)),
        taskId: "new-task",
        inputVersion: "3",
        state: "pre_review",
        sendTotal: 0,
        deliveredCount: 0
      };
      value = { taskId: "new-task" };
    } else if (path.endsWith("/recipients:import")) {
      created!.sendTotal = 2;
      value = {
        taskId: "new-task",
        inputCount: 2,
        emptyCount: 0,
        duplicateCount: 0,
        uniqueCount: 2,
        confirmedAddedCount: 2,
        existingCount: 0
      };
    } else if (path.endsWith("/approve") || path.endsWith("/close")) {
      created!.state = path.endsWith("/approve") ? "running_visible" : "terminal";
      value = { status: "applied" };
    } else if (path.endsWith("/tasks/new-task"))
      value = { task: created, results: [], resultsTruncated: false };
    else if (path.endsWith("/tasks/finite-task"))
      value = { task, results: [result], resultsTruncated: true };
    else value = { tasks: created ? [created, task] : [task], truncated: false };
    return new Response(JSON.stringify(value), {
      status: init?.method === "POST" && path.endsWith("/tasks") ? 201 : 200
    });
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
async function settle() {
  await vi.advanceTimersByTimeAsync(250);
  await nextTick();
}
async function mount(path = "/messages", mode = "api") {
  vi.useFakeTimers();
  vi.stubEnv("VITE_RUNTIME_DATA_SOURCE", mode);
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  const router = createRouter({
    history: createMemoryHistory(),
    routes: consoleRoutes
  });
  await router.push(path);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  mounted = createApp(App).use(createPinia()).use(router);
  [
    ElAlert,
    ElBreadcrumb,
    ElBreadcrumbItem,
    ElButton,
    ElConfigProvider,
    ElDrawer,
    ElIcon,
    ElInput,
    ElOption,
    ElSelect,
    ElTable,
    ElTableColumn,
    ElTabPane,
    ElTabs,
    ElTag
  ].forEach((component) => mounted!.use(component));
  mounted.mount(host);
  await settle();
  return { host, router };
}
function button(label: string) {
  return [...document.querySelectorAll<HTMLButtonElement>("button")].find(
    (b) => b.textContent?.trim() === label
  )!;
}
function input(label: string, value: string) {
  const element = document.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `input[aria-label="${label}"],textarea[aria-label="${label}"]`
  )!;
  element.value = value;
  element.dispatchEvent(new Event("input", { bubbles: true }));
}
async function draft() {
  button("创建任务").click();
  await settle();
  input("收件号码", "+8613800000001\n+8613800000002");
  input("模板内容", "{}");
  await settle();
}
function submit() {
  document
    .getElementById("message-task-form")!
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
}
describe("Messages Task API workspace", () => {
  it("lists real tasks, creates then imports without approval and keeps Score counts separate from bounded Results", async () => {
    const fetcher = installApi();
    const { host, router } = await mount();
    expect(host.textContent).toContain("Saved task");
    expect(host.textContent).not.toContain("Mock 数据");
    await draft();
    submit();
    await settle();
    const posted = fetcher.mock.calls.find(([, init]) => init?.method === "POST")!;
    expect(posted[0]).toBe("/api/v1/messages/tasks");
    expect(JSON.parse(String(posted[1]?.body))).toMatchObject({
      appId: "demo",
      name: expect.stringMatching(/^msg-demo-ANY-CN-/),
      recipientCountry: "CN",
      senderCountry: null,
      body: "{}"
    });
    for (const field of ["recipientIds", "workerGroupId", "senderPhone"])
      expect(JSON.parse(String(posted[1]?.body))).not.toHaveProperty(field);
    expect(router.currentRoute.value.path).toBe("/messages");
    expect(host.textContent).toContain("待审核");
    expect(host.textContent).toContain("任务已创建");
    const imports = fetcher.mock.calls.filter(([url]) =>
      String(url).endsWith("/recipients:import")
    );
    expect(imports).toHaveLength(1);
    expect(imports[0][1]?.body).toBe("+8613800000001\n+8613800000002");
    expect(fetcher.mock.calls.some(([url]) => String(url).endsWith("/approve"))).toBe(
      false
    );
    expect(host.textContent).not.toContain("导出成功结果");
    const before = fetcher.mock.calls.filter(([url]) =>
      String(url).includes("/messages/tasks")
    ).length;
    await vi.advanceTimersByTimeAsync(10000);
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).includes("/messages/tasks"))
    ).toHaveLength(before);
    button("预览新任务").click();
    await settle();
    document
      .querySelector<HTMLButtonElement>(".message-preview .el-drawer__close-btn")!
      .click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/messages");
  });
  it("directly opens retained task state after a new frontend session and preserves data when refresh fails", async () => {
    const fetcher = installApi();
    const { host } = await mount("/messages/tasks/finite-task");
    expect(document.body.textContent).toContain("latest reply");
    fetcher.mockImplementation(
      async () =>
        new Response(JSON.stringify({ message: "Owner unavailable" }), { status: 503 })
    );
    button("刷新预览").click();
    await settle();
    expect(document.body.textContent).toContain("Owner unavailable");
    expect(document.body.textContent).toContain("latest reply");
    expect(host.textContent).not.toContain("Mock 数据");
  });
  it("keeps the draft and known Task link when submission is unconfirmed without retrying", async () => {
    const fetcher = installApi();
    await mount();
    await draft();
    fetcher.mockImplementation(
      async () =>
        new Response(JSON.stringify({ message: "Unconfirmed", taskId: "known-task" }), {
          status: 503
        })
    );
    submit();
    await settle();
    expect(document.body.textContent).toContain("创建结果未确认");
    expect(button("查看已知任务 known-task")).not.toBeNull();
    expect(
      document.querySelector<HTMLTextAreaElement>('textarea[aria-label="收件号码"]')!
        .value
    ).toContain("+8613800000001");
    await vi.advanceTimersByTimeAsync(10000);
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "POST")
    ).toHaveLength(1);
  });
  it("keeps unavailable business routes gated instead of substituting Mock", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("{}", { status: 404 }))
    );
    const { host } = await mount();
    expect(host.querySelector('[data-testid="message-task-workspace"]')).toBeNull();
  });
});
