import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App } from "vue";
import { createRouter, createMemoryHistory, RouterView } from "vue-router";
import { ElAlert, ElButton, ElInput, ElSelect, ElOption } from "element-plus";
import Workspace from "../src/message-campaigns/MessageTaskWorkspace.vue";
import { MockMessageTaskSource } from "../src/message-campaigns/mock-task-source";
import {
  MessageTaskCreationUnconfirmed,
  messageTaskSourceKey,
  type MessageTask,
  type MessageTaskDetail
} from "../src/message-campaigns/task-source";
import {
  createMessageSession,
  messageSessionKey
} from "../src/message-campaigns/workbench";
import {
  createMessageAvailability,
  messageAvailabilityKey
} from "../src/message-campaigns/availability";
import { MessageApiError } from "../src/message-campaigns/api";
let app: App | undefined;
let source: MockMessageTaskSource;
const fetcher = vi.fn();
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("fetch", fetcher.mockReset());
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  source = new MockMessageTaskSource();
});
afterEach(() => {
  app?.unmount();
  app = undefined;
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
async function mount(path = "/messages") {
  const session = createMessageSession();
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      ...["/messages", "/messages/tasks/:taskId"].map((path) => ({
        path,
        component: Workspace
      })),
      { path: "/other", component: { template: "<p>Other</p>" } }
    ]
  });
  await router.push(path);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp({ render: () => h(RouterView) }).use(router);
  app
    .provide(messageTaskSourceKey, source)
    .provide(messageSessionKey, session)
    .provide(messageAvailabilityKey, createMessageAvailability(true));
  [ElAlert, ElButton, ElInput, ElSelect, ElOption].forEach((component) =>
    app!.use(component)
  );
  app.mount(host);
  await settle();
  return { host, router, session };
}
function button(label: string, root: ParentNode = document) {
  const element = [...root.querySelectorAll<HTMLButtonElement>("button")].find(
    (b) => b.textContent?.trim() === label
  );
  expect(element, label).toBeDefined();
  return element!;
}
function field(label: string, root: ParentNode = document) {
  const element = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `input[aria-label="${label}"],textarea[aria-label="${label}"]`
  );
  expect(element, label).not.toBeNull();
  return element!;
}
function input(label: string, value: string, root: ParentNode = document) {
  const element = field(label, root);
  element.value = value;
  element.dispatchEvent(new Event("input", { bubbles: true }));
}
async function select(label: string, option: string, root: ParentNode = document) {
  const control = field(label, root);
  control.click();
  await settle();
  const dropdown = document.getElementById(control.getAttribute("aria-controls")!)!;
  [...dropdown.querySelectorAll<HTMLElement>('[role="option"]')]
    .find((el) => el.textContent?.trim() === option)!
    .click();
  await settle();
}
function drawer() {
  return document.querySelector<HTMLElement>(".message-preview")!;
}
function menuItem(label: string) {
  const item = [...document.querySelectorAll<HTMLElement>('[role="menuitem"]')]
    .filter((item) => !item.closest('[aria-hidden="true"]'))
    .find((item) => item.textContent?.trim().startsWith(label));
  expect(item, label).toBeDefined();
  return item!;
}
async function openMenu(id?: string) {
  const root = id
    ? document.getElementById("message-task-" + id)!.closest("tr")!
    : drawer();
  const trigger = root.querySelector<HTMLButtonElement>(
    'button[aria-label^="任务操作："]'
  )!;
  expect(trigger).not.toBeNull();
  trigger.click();
  await settle();
  return trigger;
}
async function command(label: string, id?: string) {
  await openMenu(id);
  menuItem(label).click();
  await settle();
}
async function expand(label: string, root: ParentNode = drawer()) {
  const details = [...root.querySelectorAll("summary")].find(
    (node) => node.textContent?.trim() === label
  )!.parentElement as HTMLDetailsElement;
  details.open = true;
  details.dispatchEvent(new Event("toggle"));
  await settle();
  return details;
}
async function createDraft(text = "86123\n86124") {
  button("创建任务").click();
  await settle();
  input("收件号码", text);
  await settle();
}
function submit() {
  document
    .getElementById("message-task-form")!
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
}
function closeDrawer() {
  drawer().querySelector<HTMLButtonElement>(".el-drawer__close-btn")!.click();
}
function count(label: string) {
  return [...drawer().querySelectorAll(".message-counts > div")]
    .find((node) => node.querySelector("dt")?.textContent === label)
    ?.querySelector("dd")?.textContent;
}
const baseInput = {
  appId: "demo",
  requestId: "bulk",
  name: "bulk",
  recipientCountry: "CN" as const,
  senderCountry: null,
  body: "{}"
};

describe("Messages desktop workbench", () => {
  it("opens a single creation page with template content and supports an empty task", async () => {
    const create = vi.spyOn(source, "createTask");
    const { router } = await mount();
    await createDraft("");
    expect(router.currentRoute.value.query.view).toBe("create");
    expect(document.querySelector(".message-create-page .el-drawer")).toBeNull();
    expect(field("任务名称").value).toMatch(/^msg-demo-ANY-CN-/);
    expect(field("任务名称").readOnly).toBe(true);
    expect(document.querySelector('[aria-label="指定发送号码"]')).toBeNull();
    expect(document.querySelector(".message-create-form details")).toBeNull();
    expect(field("模板内容").value).toBe("{}");
    expect(document.getElementById("message-content-hint")?.textContent).toContain(
      "JSON 对象文本"
    );
    expect(button("创建空任务").disabled).toBe(false);
    submit();
    await settle();
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0][0]).toMatchObject({ appId: "demo", body: "{}" });
    expect(router.currentRoute.value.fullPath).toBe("/messages");
    const noticeTrigger = button("预览新任务");
    noticeTrigger.focus();
    noticeTrigger.click();
    await settle();
    closeDrawer();
    await settle();
    expect(document.activeElement).toBe(noticeTrigger);
    expect((await source.loadTask("mock-message-task-1")).task).toMatchObject({
      sendTotal: 0,
      state: "pre_review"
    });
  });
  it("selects and freezes an application, retains drafts across routes and keeps creation single-flight", async () => {
    const original = source.createTask.bind(source);
    let finish!: () => void;
    const create = vi.spyOn(source, "createTask").mockImplementationOnce(
      (input) =>
        new Promise((resolve) => {
          finish = () => {
            void original(input).then(resolve);
          };
        })
    );
    const { router } = await mount();
    await createDraft();
    await select("应用", "App B");
    button("返回任务列表").click();
    await settle();
    button("创建任务").click();
    await settle();
    expect(field("收件号码").value).toBe("86123\n86124");
    await router.push("/other");
    await router.push("/messages?view=create");
    await settle();
    expect(field("收件号码").value).toBe("86123\n86124");
    submit();
    submit();
    await settle();
    expect(create).toHaveBeenCalledTimes(1);
    expect(button("返回任务列表").disabled).toBe(true);
    finish();
    await settle();
    expect((await source.loadTask("mock-message-task-1")).task).toMatchObject({
      workerGroupId: "app-b-sim",
      sendTotal: 2,
      state: "pre_review"
    });
    expect(router.currentRoute.value.fullPath).toBe("/messages");
    button("创建任务").click();
    await settle();
    expect(field("收件号码").value).toBe("");
  });
  it("keeps list filters and table position mounted through preview history without extra list reads", async () => {
    const read = vi.spyOn(source, "listTasks");
    const { router, session } = await mount();
    input("搜索任务", "秋季");
    await settle();
    const viewport = document.querySelector<HTMLElement>(
      ".message-surface .el-scrollbar__wrap"
    )!;
    viewport.scrollTop = 120;
    viewport.dispatchEvent(new Event("scroll"));
    await settle();
    const opener = button("秋季活动 · 国内收件人");
    opener.focus();
    opener.click();
    await settle();
    expect(field("搜索任务")).not.toBeNull();
    expect(drawer().textContent).toContain("当前预览 100 条");
    closeDrawer();
    await settle();
    expect(router.currentRoute.value.path).toBe("/messages");
    expect(field("搜索任务").value).toBe("秋季");
    expect(document.activeElement).toBe(opener);
    expect(session.scrollTop).toBe(120);
    expect(read).toHaveBeenCalledTimes(1);
    router.forward();
    await settle();
    expect(router.currentRoute.value.params.taskId).toBe("msg-autumn-cn");
  });
  it("filters only the loaded window and excludes managed tasks while retaining incomplete tasks", async () => {
    const { host } = await mount();
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(8);
    expect(host.textContent).toContain("msg-restored-task");
    expect(host.textContent).not.toContain("Project managed Task");
    await select("筛选应用", "App A");
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(3);
    button("重置").click();
    await settle();
    [...host.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((el) => el.textContent?.startsWith("发送中"))!
      .click();
    await settle();
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(3);
    input("搜索任务", "not-loaded");
    await settle();
    expect(host.textContent).toContain("当前载入任务中没有匹配项");
  });
  it("bounds and sorts 101 tasks without pagination or scroll reads", async () => {
    source = new MockMessageTaskSource(
      Array.from({ length: 101 }, (_, i) => ({
        task: {
          taskId: `task-${i}`,
          createdAtMillis: i + 1,
          workerGroupId: null,
          managed: false,
          state: null
        } as MessageTask,
        results: [],
        resultsTruncated: false
      }))
    );
    const read = vi.spyOn(source, "listTasks");
    const { host } = await mount();
    const rows = host.querySelectorAll('[data-testid="message-task-row"]');
    expect(rows).toHaveLength(100);
    expect(rows[0].textContent).toBe("task-100");
    expect(host.textContent).toContain("返回数据已截断");
    document.querySelector(".el-scrollbar__wrap")!.dispatchEvent(new Event("scroll"));
    await settle();
    expect(read).toHaveBeenCalledTimes(1);
    expect(host.querySelector(".el-pagination")).toBeNull();
  }, 15000);
  it("only searches 100 preview rows and never changes whole-task counts", async () => {
    const load = vi.spyOn(source, "loadTask");
    await mount("/messages/tasks/msg-autumn-cn");
    expect(drawer().querySelectorAll(".el-table__body tbody tr")).toHaveLength(100);
    expect(count("收件人")).toBe("280");
    expect(count("已送达")).toBe("254");
    input("搜索收件号码", "+8613800000200", drawer());
    await settle();
    expect(drawer().textContent).toContain("当前预览中没有匹配结果");
    expect(count("收件人")).toBe("280");
    expect(load).toHaveBeenCalledTimes(1);
    expect(drawer().textContent).not.toMatch(/下一页|导出|加载更多|100%/);
  });
  it("retains file validation and checks content bounds without interpreting or rewriting text", async () => {
    const create = vi.spyOn(source, "createTask");
    await mount();
    await createDraft("86123\n86123\n44123");
    input("模板内容", " ");
    await settle();
    expect(document.body.textContent).toContain("请填写模板内容");
    submit();
    await settle();
    expect(create).not.toHaveBeenCalled();
    input("模板内容", "x".repeat(4097));
    await settle();
    expect(document.body.textContent).toContain("模板内容最多 4096 字符");
    submit();
    await settle();
    expect(create).not.toHaveBeenCalled();
    const chooser = field("号码文件"),
      file = new File([], "bad.txt");
    Object.defineProperty(file, "arrayBuffer", {
      value: async () => new Uint8Array([0xff]).buffer
    });
    Object.defineProperty(chooser, "files", { configurable: true, value: [file] });
    chooser.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();
    expect(document.body.textContent).toContain("UTF-8");
    input("模板内容", "{}");
    submit();
    await settle();
    expect(create).not.toHaveBeenCalled();
    const text = "  Hello {{name}}\nThis is not JSON.  ";
    input("收件号码", "86123");
    input("模板内容", text);
    await settle();
    submit();
    await settle();
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0][0].body).toBe(text);
  });
  it("keeps the file and unused request identity editable after a definitive creation rejection", async () => {
    const create = vi
      .spyOn(source, "createTask")
      .mockRejectedValueOnce(new MessageApiError(400, "body must be a JSON object"));
    const importing = vi.spyOn(source, "importRecipients");
    const { session, router } = await mount("/messages?view=create");
    const file = new File([], "recipients.txt");
    Object.defineProperty(file, "arrayBuffer", {
      value: async () => new TextEncoder().encode("86123\n86124").buffer
    });
    const chooser = field("号码文件");
    Object.defineProperty(chooser, "files", { configurable: true, value: [file] });
    chooser.dispatchEvent(new Event("change", { bubbles: true }));
    input("模板内容", "Hello");
    await settle();
    submit();
    await settle();
    const rejected = structuredClone(create.mock.calls[0][0]);
    expect(field("模板内容").disabled).toBe(false);
    expect(field("号码文件").disabled).toBe(false);
    expect(session.draft.editor.source).toBe(file);
    expect(session.draft.editor.fileName).toBe("recipients.txt");
    expect(session.draft.editor.summary?.validCount).toBe(2);
    expect(field("任务名称").value).toBe(rejected.name);
    expect(document.body.textContent).toContain("输入已保留，可修改后重新提交");
    expect(importing).not.toHaveBeenCalled();
    input("模板内容", '{"text":"Hello"}');
    await settle();
    submit();
    await settle();
    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[1][0]).toEqual({ ...rejected, body: '{"text":"Hello"}' });
    expect(importing).toHaveBeenCalledTimes(1);
    expect(importing).toHaveBeenCalledWith("mock-message-task-1", "86123\n86124");
    expect(router.currentRoute.value.fullPath).toBe("/messages");
    expect((await source.loadTask("mock-message-task-1")).task.sendTotal).toBe(2);
  });
  it("does not unlock an earlier unknown submission when reconciliation is rejected", async () => {
    const original = source.createTask.bind(source);
    const create = vi
      .spyOn(source, "createTask")
      .mockImplementationOnce(async (input) => {
        const result = await original(input);
        throw new MessageTaskCreationUnconfirmed("unknown", result.taskId);
      })
      .mockRejectedValueOnce(new MessageApiError(400, "reconciliation rejected"));
    const importing = vi.spyOn(source, "importRecipients");
    await mount();
    await createDraft();
    submit();
    await settle();
    const frozen = structuredClone(create.mock.calls[0][0]);
    button("使用原身份核对创建").click();
    await settle();
    expect(field("模板内容").disabled).toBe(true);
    expect(field("收件号码").disabled).toBe(true);
    expect(document.body.textContent).toContain("此前提交仍未确认");
    expect(button("查看已知任务 mock-message-task-1")).toBeDefined();
    submit();
    await settle();
    expect(create).toHaveBeenCalledTimes(2);
    button("使用原身份核对创建").click();
    await settle();
    expect(create.mock.calls[1][0]).toEqual(frozen);
    expect(create.mock.calls[2][0]).toEqual(frozen);
    expect(importing).not.toHaveBeenCalled();
  });
  it("keeps an identity conflict frozen for explicit resolution", async () => {
    const create = vi
      .spyOn(source, "createTask")
      .mockRejectedValueOnce(
        new MessageApiError(409, "request identity conflict", "existing-task")
      );
    const importing = vi.spyOn(source, "importRecipients");
    await mount();
    await createDraft();
    submit();
    await settle();
    expect(field("模板内容").disabled).toBe(true);
    expect(field("收件号码").value).toBe("86123\n86124");
    expect(button("查看已知任务 existing-task")).toBeDefined();
    expect(document.body.textContent).toContain("请求身份发生冲突");
    submit();
    await settle();
    expect(create).toHaveBeenCalledTimes(1);
    expect(importing).not.toHaveBeenCalled();
    button("结束本次提交").click();
    await settle();
    expect(field("收件号码").value).toBe("");
    expect(field("模板内容").disabled).toBe(false);
  });
  it("preserves a frozen unknown submission underneath its known-task drawer and reconciles without importing", async () => {
    const original = source.createTask.bind(source);
    const create = vi
      .spyOn(source, "createTask")
      .mockImplementationOnce(async (input) => {
        const result = await original(input);
        throw new MessageTaskCreationUnconfirmed("unknown", result.taskId);
      });
    const importing = vi.spyOn(source, "importRecipients");
    const { router } = await mount();
    await createDraft();
    submit();
    await settle();
    const frozen = structuredClone(create.mock.calls[0][0]);
    button("查看已知任务 mock-message-task-1").click();
    await settle();
    expect(router.currentRoute.value.query.view).toBe("create");
    closeDrawer();
    await settle();
    expect(router.currentRoute.value.fullPath).toBe("/messages?view=create");
    expect(field("收件号码").value).toBe("86123\n86124");
    await vi.advanceTimersByTimeAsync(60000);
    submit();
    await settle();
    expect(create).toHaveBeenCalledTimes(1);
    button("使用原身份核对创建").click();
    await settle();
    expect(create.mock.calls[1][0]).toEqual(frozen);
    expect(importing).not.toHaveBeenCalled();
    button("结束本次提交").click();
    await settle();
    expect(field("收件号码").value).toBe("");
    submit();
    await settle();
    expect(create.mock.calls[2][0].requestId).not.toBe(frozen.requestId);
  });
  it("retains a created task after import uncertainty and only reimports explicitly", async () => {
    const create = vi.spyOn(source, "createTask"),
      original = source.importRecipients.bind(source);
    const importing = vi
      .spyOn(source, "importRecipients")
      .mockImplementationOnce(async (id, text) => {
        await original(id, text);
        throw new MessageApiError(503, "导入未确认", id, 0, 0);
      });
    await mount();
    await createDraft();
    submit();
    await settle();
    expect(document.body.textContent).toContain("任务已创建，导入未完成");
    button("刷新实际数量").click();
    await settle();
    expect(document.body.textContent).toContain("当前实际收件人数：2");
    button("重新导入原号码").click();
    await settle();
    expect(create).toHaveBeenCalledTimes(1);
    expect(importing).toHaveBeenCalledTimes(2);
    expect((await source.loadTask("mock-message-task-1")).task.sendTotal).toBe(2);
  });
  it("imports via the unified menu and uses a fresh count in the approval confirmation", async () => {
    await source.createTask(baseInput);
    const approve = vi.spyOn(source, "approveTask");
    await mount();
    await command("导入收件人", "mock-message-task-1");
    const dialog = document.querySelector<HTMLElement>(".el-dialog")!;
    input("收件号码", "86123\n+86123\n86124", dialog);
    await settle();
    button("确认导入", dialog).click();
    await settle();
    await source.importRecipients("mock-message-task-1", "86125");
    await command("核对并启动", "mock-message-task-1");
    expect(document.body.textContent).toContain("实际收件人数 3");
    button("确认").click();
    await settle();
    expect(approve).toHaveBeenCalledWith("mock-message-task-1", 3);
    await command("中止任务", "mock-message-task-1");
    button("确认").click();
    await settle();
    expect((await source.loadTask("mock-message-task-1")).task).toMatchObject({
      state: "terminal",
      sendTotal: 3,
      sentCount: 0
    });
  });
  it("shows unchanged template content in pending review and approval without empty execution metrics", async () => {
    const body = "发送给 {{name}}\n<b>原样文本</b>";
    await source.createTask({ ...baseInput, body });
    await source.importRecipients("mock-message-task-1", "86123");
    await mount("/messages/tasks/mock-message-task-1");
    const preview = drawer().querySelector('[aria-label="模板内容预览"]')!;
    expect(preview.textContent).toBe(body);
    expect(preview.querySelector("b")).toBeNull();
    expect(preview.closest("details")).toBeNull();
    expect(drawer().textContent).toContain("实际收件人数：1");
    expect(drawer().querySelector(".message-counts")).toBeNull();
    await source.importRecipients("mock-message-task-1", "86124");
    await command("核对并启动");
    const dialog = document.querySelector(".el-dialog")!;
    expect(dialog.textContent).toContain("实际收件人数 2");
    expect(dialog.querySelector('[aria-label="模板内容预览"]')!.textContent).toBe(body);
    button("确认", dialog).click();
    await settle();
    expect(drawer().querySelector(".message-counts")).not.toBeNull();
    expect(count("已发送")).toBe("0");
  });
  it("does not approve changed counts and requires a fresh read after a rejected operation", async () => {
    await source.createTask(baseInput);
    await source.importRecipients("mock-message-task-1", "86123");
    await mount();
    await command("核对并启动", "mock-message-task-1");
    await source.importRecipients("mock-message-task-1", "86124");
    button("确认").click();
    await settle();
    expect(document.body.textContent).toContain("收件人数为空或已变化");
    await openMenu("mock-message-task-1");
    expect(menuItem("核对并启动").getAttribute("aria-disabled")).toBe("true");
    button("刷新").click();
    await settle();
    expect((await source.loadTask("mock-message-task-1")).task.state).toBe(
      "pre_review"
    );
  });
  it("shows the legacy sender restriction in review and approval without exposing it in creation", async () => {
    const legacy = {
      task: {
        taskId: "legacy",
        name: "Legacy",
        workerGroupId: "demo-sim",
        managed: false,
        state: "pre_review",
        inputVersion: "2",
        createdAtMillis: 1,
        body: "{}",
        recipientCountry: "CN",
        senderCountry: "US",
        senderPhone: "12025550123",
        sendTotal: 1
      } as MessageTask,
      results: [],
      resultsTruncated: false
    };
    source = new MockMessageTaskSource([legacy]);
    await mount("/messages/tasks/legacy");
    expect(drawer().textContent).toContain("12025550123");
    await command("核对并启动");
    expect(document.querySelector(".el-dialog")!.textContent).toContain("12025550123");
  });
  it("keeps terminal tasks terminal while explicit delivery, read and repeated replies advance", async () => {
    await mount("/messages/tasks/msg-follow-up");
    await expand("技术信息与演示控制");
    expect(count("已送达")).toBe("0");
    button("演示送达", drawer()).click();
    await settle();
    expect(count("已送达")).toBe("1");
    button("演示已读", drawer()).click();
    await settle();
    expect(count("已读")).toBe("1");
    button("新增回复", drawer()).click();
    await settle();
    button("新增回复", drawer()).click();
    await settle();
    expect(drawer().textContent).toContain("演示回复 2");
    expect(count("已回复")).toBe("1");
    expect(drawer().textContent).toContain("调度已结束，后续回执仍可更新");
  });
  it("preserves a failed refresh snapshot and its timestamp, then manually recovers", async () => {
    await mount("/messages/tasks/msg-uk-running");
    const before = drawer().querySelector(".el-table__body")!.textContent;
    await expand("技术信息与演示控制");
    button("演示读取失败", drawer()).click();
    await settle();
    expect(drawer().textContent).toContain("快照已过期");
    expect(drawer().querySelector(".el-table__body")!.textContent).toBe(before);
    await openMenu();
    expect(menuItem("中止任务").getAttribute("aria-disabled")).toBe("true");
    button("刷新预览", drawer()).click();
    await settle();
    expect(drawer().textContent).not.toContain("快照已过期");
  });
  it("keeps old list data on refresh failure and provides retry for an initial missing task", async () => {
    const { host, router } = await mount();
    vi.spyOn(source, "listTasks").mockRejectedValueOnce(new Error("list unavailable"));
    button("刷新").click();
    await settle();
    expect(host.textContent).toContain("快照已过期");
    expect(host.querySelectorAll('[data-testid="message-task-row"]')).toHaveLength(8);
    await router.push("/messages/tasks/not-found");
    await settle();
    expect(drawer().textContent).toContain("没有找到这个 Mock 任务");
    expect(drawer().querySelector(".message-counts")).toBeNull();
  });
  it("discards late task reads without replacing the new drawer or stealing focus", async () => {
    const original = source.loadTask.bind(source);
    let finish!: (value: MessageTaskDetail) => void;
    vi.spyOn(source, "loadTask").mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        })
    );
    const { router } = await mount("/messages/tasks/msg-autumn-cn");
    await router.push("/messages/tasks/msg-follow-up");
    await settle();
    const focus = button("刷新预览", drawer());
    focus.focus();
    finish(await original("msg-autumn-cn"));
    await settle();
    expect(drawer().querySelector("h2")!.textContent).toContain("持续回执");
    expect(document.activeElement).toBe(focus);
  });
  it("supports 100000 local inputs, app-sensitive identity, partial closure and later receipts", async () => {
    const created = await source.createTask(baseInput);
    await expect(source.createTask({ ...baseInput, appId: "app-a" })).rejects.toThrow(
      "不同"
    );
    const text = Array.from({ length: 100000 }, (_, i) => `86138${10000000 + i}`).join(
      "\n"
    );
    expect(
      (await source.importRecipients(created.taskId, text)).confirmedAddedCount
    ).toBe(100000);
    expect(
      (await source.importRecipients(created.taskId, "8613810000000\n+8613810000000"))
        .existingCount
    ).toBe(1);
    await source.approveTask(created.taskId, 100000);
    await source.demonstrate(created.taskId, "advance");
    await source.closeTask(created.taskId);
    await source.demonstrate(created.taskId, "reply");
    const snapshot = await source.loadTask(created.taskId);
    expect(snapshot.task).toMatchObject({
      state: "terminal",
      sendTotal: 100000,
      sentCount: 50,
      repliedCount: 50
    });
    await expect(source.importRecipients(created.taskId, "86123")).rejects.toThrow(
      "待审核"
    );
    await expect(source.approveTask(created.taskId, 100000)).rejects.toThrow("待审核");
    await expect(source.demonstrate(created.taskId, "complete")).rejects.toThrow(
      "调度已结束"
    );
  });
});
