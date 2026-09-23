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
  runId: "one",
  version: "0.1.0-preview",
  countries: ["CN", "US", "GB"].map((id) => ({ id, workerGroupId: "demo-sim" })),
  limits: { tasks: 50, items: 50000, recipientsPerTask: 1000 }
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
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/v1/sms/")) return new Response("{}", { status: 503 });
    const value = path.endsWith("/catalog")
      ? catalog
      : init?.method === "POST"
        ? { taskId: task.taskId }
        : path.endsWith("/tasks/finite-task")
          ? { task, results: [result], resultsTruncated: true }
          : { tasks: [task], truncated: false };
    return new Response(JSON.stringify(value), {
      status: init?.method === "POST" ? 201 : 200
    });
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
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
  button("创建消息任务").click();
  await settle();
  input("收件号码", "+8613800000001\n+8613800000002");
  input("JSON 正文", "{}");
  await settle();
}
function submit() {
  document
    .getElementById("message-task-form")!
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
}
describe("Messages Task API workspace", () => {
  it("lists real tasks, creates through the synchronous API and keeps Score counts separate from bounded Results", async () => {
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
      name: expect.stringMatching(/^msg-ANY-CN-2-/),
      recipientCountry: "CN",
      senderCountry: null,
      body: "{}"
    });
    expect(router.currentRoute.value.path).toBe("/messages/tasks/finite-task");
    expect(host.textContent).toContain("latest reply");
    expect(host.textContent).toContain("1,000");
    expect(host.querySelectorAll('[data-testid="message-result-row"]')).toHaveLength(1);
    expect(host.textContent).not.toContain("导出成功结果");
    const before = fetcher.mock.calls.filter(([url]) =>
      String(url).includes("/messages/tasks")
    ).length;
    await vi.advanceTimersByTimeAsync(10000);
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).includes("/messages/tasks"))
    ).toHaveLength(before);
    host.querySelector<HTMLAnchorElement>("a.task-back")!.click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/messages");
  });
  it("directly opens retained task state after a new frontend session and preserves data when refresh fails", async () => {
    const fetcher = installApi();
    const { host } = await mount("/messages/tasks/finite-task");
    expect(host.textContent).toContain("latest reply");
    fetcher.mockImplementation(
      async () =>
        new Response(JSON.stringify({ message: "Owner unavailable" }), { status: 503 })
    );
    button("刷新").click();
    await settle();
    expect(host.textContent).toContain("Owner unavailable");
    expect(host.textContent).toContain("latest reply");
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
    expect(document.body.textContent).toContain("提交结果未确认");
    expect(
      document.querySelector('a[href="/messages/tasks/known-task"]')
    ).not.toBeNull();
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
