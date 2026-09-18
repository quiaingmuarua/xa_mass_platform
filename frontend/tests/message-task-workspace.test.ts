import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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
  ElSelect
} from "element-plus";
import App from "../src/App.vue";
import { consoleRoutes } from "../src/router";
import { MockMessageTaskSource } from "../src/message-campaigns/mock-task-source";
import {
  MessageTaskCreationUnconfirmed,
  type MessageTask
} from "../src/message-campaigns/task-source";

let mounted: VueApp | undefined;
const fetcher = vi.fn();
let xhr: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubEnv("VITE_RUNTIME_DATA_SOURCE", "mock");
  fetcher.mockReset();
  vi.stubGlobal("fetch", fetcher);
  xhr = vi.spyOn(XMLHttpRequest.prototype, "open");
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
});
afterEach(() => {
  mounted?.unmount();
  mounted = undefined;
  expect(fetcher).not.toHaveBeenCalled();
  expect(xhr).not.toHaveBeenCalled();
  document.body.replaceChildren();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
  await nextTick();
}
async function mount(path = "/messages") {
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
    ElSelect
  ].forEach((component) => mounted!.use(component));
  mounted.mount(host);
  await settle();
  return { host, router };
}
function button(label: string, root: ParentNode = document) {
  const element = [...root.querySelectorAll<HTMLButtonElement>("button")].find(
    (b) => b.textContent?.trim() === label
  );
  expect(element, `button ${label}`).toBeDefined();
  return element!;
}
function field(label: string) {
  return document.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `input[aria-label="${label}"],textarea[aria-label="${label}"]`
  )!;
}
function input(label: string, value: string) {
  const element = field(label);
  element.value = value;
  element.dispatchEvent(new Event("input", { bubbles: true }));
}
async function select(label: string, option: string) {
  const control = field(label);
  control.click();
  await settle();
  const dropdown = document.getElementById(control.getAttribute("aria-controls")!)!;
  [...dropdown.querySelectorAll<HTMLElement>('[role="option"]')]
    .find((el) => el.textContent?.trim() === option)!
    .click();
  await settle();
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

describe("Messages Task workspace in explicit Mock mode", () => {
  it("offers creation for an empty list without inventing task progress", async () => {
    vi.spyOn(MockMessageTaskSource.prototype, "listTasks").mockResolvedValue({
      tasks: [],
      truncated: false
    });
    const { host } = await mount();
    expect(host.textContent).toContain("还没有消息任务");
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(0);
    button("创建消息任务").click();
    await settle();
    expect(field("任务名称").value).toMatch(/^msg-ANY-CN-0-\d{8}-\d{6}$/);
    expect(field("任务名称").readOnly).toBe(true);
  });

  it("filters the loaded list, opens a task and restores filters, scroll and row focus", async () => {
    const { host, router } = await mount();
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(9);
    expect(host.textContent).toContain("Mock 数据");
    expect(host.textContent).toContain("发送总数");
    expect(host.textContent).toContain("发送成功数");
    expect(host.textContent).not.toContain("待批准");
    input("搜索任务", "秋季");
    await select("筛选任务状态", "调度已结束");
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(1);
    vi.spyOn(window, "scrollY", "get").mockReturnValue(320);
    button("秋季活动 · 国内收件人").click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/messages/tasks/msg-autumn-cn");
    expect(document.activeElement?.tagName).toBe("H1");
    host.querySelector<HTMLAnchorElement>("a.task-back")!.click();
    await settle();
    expect(field("搜索任务").value).toBe("秋季");
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(1);
    expect(document.activeElement?.id).toBe("message-task-msg-autumn-cn");
    expect(window.scrollTo).toHaveBeenLastCalledWith({
      top: 320,
      left: 0,
      behavior: "instant"
    });
  });

  it("keeps a canceled draft, submits only once, then clears it and opens the new task", async () => {
    const original = MockMessageTaskSource.prototype.createTask;
    const creating = vi.spyOn(MockMessageTaskSource.prototype, "createTask");
    let complete!: () => void;
    creating.mockImplementationOnce(function (this: MockMessageTaskSource, input) {
      return new Promise((resolve) => {
        complete = () => resolve(original.call(this, input));
      });
    });
    const { host, router } = await mount();
    await draft();
    await select("发送范围", "US");
    button("取消").click();
    await settle();
    button("创建消息任务").click();
    await settle();
    expect(field("任务名称").value).toMatch(/^msg-US-CN-2-\d{8}-\d{6}$/);
    submit();
    submit();
    await settle();
    expect(creating).toHaveBeenCalledTimes(1);
    expect(creating.mock.calls[0][0]).toMatchObject({
      name: expect.stringMatching(/^msg-US-CN-2-\d{8}-\d{6}$/),
      recipientCountry: "CN",
      senderCountry: "US",
      body: "{}",
      recipientIds: ["+8613800000001", "+8613800000002"]
    });
    expect(button("创建并开始发送").disabled).toBe(true);
    expect(button("取消").disabled).toBe(true);
    complete();
    await settle();
    expect(router.currentRoute.value.path).toBe("/messages/tasks/mock-message-task-1");
    expect(host.textContent).toContain("尚未观察到 Result");
    expect(host.querySelector('[data-testid="send-total"]')?.textContent).toBe("2");
    expect(host.querySelector('[data-testid="delivered-count"]')?.textContent).toBe(
      "0"
    );
    await router.push("/messages");
    await settle();
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(10);
    button("创建消息任务").click();
    await settle();
    expect(field("任务名称").value).toMatch(/^msg-ANY-CN-0-\d{8}-\d{6}$/);
    expect(field("收件号码").value).toBe("");
    expect((await new MockMessageTaskSource().listTasks()).tasks).toHaveLength(9);
  });

  it("preserves file drafts on decode failure and blocks invalid numbers and JSON before creation", async () => {
    const creating = vi.spyOn(MockMessageTaskSource.prototype, "createTask");
    await mount();
    await draft();
    const chooser = field("号码文件");
    const choose = async (bytes: Uint8Array) => {
      const file = new File([], "numbers.txt");
      Object.defineProperty(file, "arrayBuffer", { value: async () => bytes.buffer });
      Object.defineProperty(chooser, "files", { configurable: true, value: [file] });
      chooser.dispatchEvent(new Event("change", { bubbles: true }));
      await settle();
    };
    await choose(new TextEncoder().encode("\uFEFF+8613800000003\r\n\r+8613800000004"));
    const before = field("收件号码").value;
    await choose(new Uint8Array([0xff]));
    expect(field("收件号码").value).toBe(before);
    expect(document.body.textContent).toContain("UTF-8");
    input("收件号码", "\n+8613800000003\n+8613800000003");
    input("JSON 正文", "invalid");
    await settle();
    expect(document.body.textContent).toContain("第 3 行：号码重复");
    expect(document.body.textContent).toContain("正文不是合法 JSON");
    submit();
    await settle();
    expect(creating).not.toHaveBeenCalled();
    expect(button("创建并开始发送").disabled).toBe(true);
  });

  it("keeps input after a rejected create and never retries an unconfirmed submission", async () => {
    const creating = vi
      .spyOn(MockMessageTaskSource.prototype, "createTask")
      .mockRejectedValueOnce(new Error("Mock 创建被拒绝"))
      .mockRejectedValueOnce(new MessageTaskCreationUnconfirmed("提交结果未确认"));
    await mount();
    await draft();
    submit();
    await settle();
    expect(document.body.textContent).toContain("Mock 创建被拒绝");
    expect(field("任务名称").value).toMatch(/^msg-ANY-CN-2-\d{8}-\d{6}$/);
    submit();
    await settle();
    expect(document.body.textContent).toContain("提交结果未确认");
    submit();
    button("取消").click();
    await settle();
    button("刷新").click();
    button("创建消息任务").click();
    await vi.advanceTimersByTimeAsync(5000);
    expect(creating).toHaveBeenCalledTimes(2);
    expect(button("创建并开始发送").disabled).toBe(true);
    expect(field("任务名称").value).toMatch(/^msg-ANY-CN-2-\d{8}-\d{6}$/);
  });

  it("renders only 100 produced Results, including failures, without paging, export or a task completion percentage", async () => {
    const { host } = await mount("/messages/tasks/msg-autumn-cn");
    expect(host.querySelectorAll('[data-testid="message-result-row"]')).toHaveLength(
      100
    );
    expect(host.textContent).toContain("失败");
    // Whole-task totals cannot be computed from this 100-row preview.
    expect(host.querySelector('[data-testid="send-total"]')?.textContent).toBe("280");
    expect(host.querySelector('[data-testid="delivered-count"]')?.textContent).toBe(
      "254"
    );
    expect(host.textContent).toContain("还有结果未展示");
    expect(host.textContent).toContain("不代表整任务完成率");
    expect(host.querySelector('.el-pagination,[role="progressbar"]')).toBeNull();
    expect(host.textContent).not.toMatch(/导出|加载更多|下一页|\d+%/);
    button("详情", host).click();
    await settle();
    expect(host.textContent).toContain("Worker ID");
  });

  it("keeps Task scheduling terminal while a manually refreshed receipt advances", async () => {
    const { host } = await mount("/messages/tasks/msg-follow-up");
    expect(host.textContent).toContain("调度已结束，后续回执仍可更新");
    expect(host.textContent).toContain("尚未观察");
    expect(host.querySelector(".result-send")?.textContent).toBe("已发送");
    expect(host.querySelector('[data-testid="delivered-count"]')?.textContent).toBe(
      "0"
    );
    button("刷新").click();
    await settle();
    expect(host.textContent).toContain("已读");
    expect(host.querySelector(".result-send")?.textContent).toBe("发送成功");
    expect(host.querySelector('[data-testid="delivered-count"]')?.textContent).toBe(
      "1"
    );
    button("刷新").click();
    await settle();
    expect(host.textContent).toContain("已回复");
    expect(host.querySelector('[data-testid="delivered-count"]')?.textContent).toBe(
      "1"
    );
    expect(host.textContent).toContain("已确认，明天会到");
    expect(host.querySelector(".task-actions .task-state")?.textContent).toBe(
      "调度已结束"
    );
  });

  it("retains known Results after a read failure, and recovers on manual refresh", async () => {
    const { host } = await mount("/messages/tasks/msg-read-error");
    const before = host.querySelector(".result-table")!.textContent;
    button("刷新").click();
    await settle();
    expect(host.textContent).toContain("Mock 结果读取失败");
    expect(host.querySelector(".result-table")!.textContent).toBe(before);
    button("刷新").click();
    await settle();
    expect(host.textContent).not.toContain("Mock 结果读取失败");
  });

  it("retains the task list on read failure and shows loaded-only empty filters", async () => {
    const { host } = await mount();
    vi.spyOn(MockMessageTaskSource.prototype, "listTasks").mockRejectedValueOnce(
      new Error("Mock 列表读取失败")
    );
    button("刷新").click();
    await settle();
    expect(host.textContent).toContain("Mock 列表读取失败");
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(9);
    input("搜索任务", "not-in-window");
    await settle();
    expect(host.textContent).toContain("没有符合筛选条件的任务");
    expect(host.textContent).toContain("仅作用于当前已加载");
  });

  it("retains missing metadata, marks managed tasks, and distinguishes empty Results from failure", async () => {
    const { host, router } = await mount();
    expect(host.textContent).toContain("msg-restored-task");
    expect(host.textContent).toContain("managed Task");
    await router.push("/messages/tasks/msg-restored-task");
    await settle();
    expect(host.textContent).toContain("业务配置未提供");
    expect(host.querySelector("h1")?.textContent).toBe("msg-restored-task");
    expect(host.querySelector('[data-testid="delivered-count"]')?.textContent).toBe(
      "—"
    );
    await router.push("/messages/tasks/msg-any-waiting");
    await settle();
    expect(host.textContent).toContain("尚未观察到 Result");
    expect(host.textContent).toContain("空结果不表示失败");
    await router.push("/messages/tasks/no-such-task");
    await settle();
    expect(host.textContent).toContain("没有找到这个 Mock 任务");
    expect(host.querySelector(".task-overview")).toBeNull();
  });

  it("bounds and sorts a large task list without hiding incomplete tasks", async () => {
    const records = Array.from({ length: 101 }, (_, i) => ({
      task: {
        taskId: `task-${i}`,
        createdAtMillis: i + 1,
        workerGroupId: "test",
        managed: false,
        state: "terminal"
      } as MessageTask,
      results: [],
      resultsTruncated: false
    }));
    const page = await new MockMessageTaskSource(records).listTasks();
    expect(page.tasks).toHaveLength(100);
    expect(page.tasks[0].taskId).toBe("task-100");
    expect(page.tasks[99].taskId).toBe("task-1");
    vi.spyOn(MockMessageTaskSource.prototype, "listTasks").mockResolvedValue(page);
    const { host } = await mount();
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(100);
    expect(host.textContent).toContain("列表已截断");
  });
});
