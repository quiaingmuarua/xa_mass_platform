import { importedTask } from "./app-check-fixture";
import { webcrypto } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App } from "vue";
import { createRouter, createMemoryHistory, RouterView } from "vue-router";
import { ElButton, ElDrawer, ElInput, ElSelect, ElOption } from "element-plus";
import Workspace from "../src/app-checks/AppCheckWorkspace.vue";
import {
  MockAppCheckTaskSource,
  mockCatalog
} from "../src/app-checks/mock-task-source";
import {
  AppCheckCreationUnconfirmed,
  AppCheckImportUnconfirmed,
  type AppCheckTaskSource
} from "../src/app-checks/task-source";
import type { CheckDetail } from "../src/app-checks/model";
import * as verification from "../src/app-checks/verify";
import { createWorkbenchSession } from "../src/app-checks/workbench";
import { inspectImport, type ImportOptions } from "../src/app-checks/import-model";
import { decodeUtf8 } from "../src/files/text";
class ImportWorkerMock {
  onmessage?: (event: MessageEvent) => void;
  onerror?: () => void;
  terminated = false;
  postMessage(input: { content: string | ArrayBuffer; options: ImportOptions }) {
    queueMicrotask(() => {
      if (this.terminated) return;
      try {
        const text =
          typeof input.content === "string" ? input.content : decodeUtf8(input.content);
        this.onmessage?.({
          data: { text, report: inspectImport(text, input.options) }
        } as MessageEvent);
      } catch (error) {
        this.onmessage?.({ data: { error: (error as Error).message } } as MessageEvent);
      }
    });
  }
  terminate() {
    this.terminated = true;
  }
}

let app: App | undefined;
let source: MockAppCheckTaskSource;
const fetcher = vi.fn();
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("crypto", webcrypto);
  vi.stubGlobal("fetch", fetcher.mockReset());
  vi.stubGlobal("Worker", ImportWorkerMock);
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  source = new MockAppCheckTaskSource();
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
async function mount(
  path = "/app-checks",
  selected: AppCheckTaskSource = source,
  session = createWorkbenchSession()
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ["/app-checks", "/app-checks/tasks/:taskId"].map((path) => ({
      path,
      component: Workspace,
      props: () => ({ source: selected, catalog: mockCatalog, session })
    }))
  });
  await router.push(path);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp({ render: () => h(RouterView) }).use(router);
  [ElButton, ElDrawer, ElInput, ElSelect, ElOption].forEach((component) =>
    app!.use(component)
  );
  app.mount(host);
  await settle();
  return { host, router, session };
}
function button(text: string, scope: ParentNode = document) {
  const node = [...scope.querySelectorAll<HTMLButtonElement>("button")].find(
    (b) => b.textContent?.trim().replace(/\s*[⌄⌃]$/, "") === text
  );
  expect(node, text).toBeDefined();
  return node!;
}
function drawer() {
  return document.querySelector<HTMLElement>(".checks-preview-drawer")!;
}
function previewButton(text: string) {
  return button(text, drawer());
}
function menuItems() {
  return [...document.querySelectorAll<HTMLElement>('[role="menuitem"]')].filter(
    (item) => !item.closest('[aria-hidden="true"]')
  );
}
function menuItem(text: string) {
  const item = menuItems().find((item) => item.textContent?.trim().startsWith(text));
  expect(item, text).toBeDefined();
  return item!;
}
async function openMenu(taskId?: string) {
  const scope = taskId
    ? document.getElementById("check-task-" + taskId)!.closest("tr")!
    : drawer();
  const trigger = scope.querySelector<HTMLButtonElement>(
    ".checks-action-group button"
  )!;
  trigger.click();
  await settle();
  return trigger;
}
async function command(text: string, taskId?: string) {
  await openMenu(taskId);
  menuItem(text).click();
  await settle();
}
function disclosure(text: string) {
  return [...drawer().querySelectorAll("summary")].find(
    (item) => item.textContent?.trim() === text
  )!.parentElement as HTMLDetailsElement;
}
async function expand(text: string) {
  const details = disclosure(text);
  details.open = true;
  details.dispatchEvent(new Event("toggle"));
  await settle();
}
function tableViewport() {
  return document.querySelector<HTMLElement>(".checks-task-table .el-scrollbar__wrap")!;
}
function field(label: string) {
  return document.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `input[aria-label="${label}"],textarea[aria-label="${label}"]`
  )!;
}
function input(label: string, value: string) {
  const node = field(label);
  node.value = value;
  node.dispatchEvent(new Event("input", { bubbles: true }));
}
async function openCreate() {
  button("创建任务").click();
  await settle();
  button("粘贴号码").click();
  await settle();
}
async function submit() {
  document
    .querySelector("#app-check-create")!
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await settle();
}
async function closeDraft() {
  button("返回任务列表，保留草稿").click();
  await settle();
}
async function diagnostics() {
  button("技术信息").click();
  await settle();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("App Checks product workspace", () => {
  it("reimports an unconfirmed file into the original task without creating again", async () => {
    const create = vi.spyOn(source, "createTask");
    const imports = vi
      .spyOn(source, "importNumbers")
      .mockRejectedValueOnce(new AppCheckImportUnconfirmed("mock-check-1"));
    const { router, session } = await mount();
    await openCreate();
    input("号码列表", "8613800000001\n8613800000002");
    await settle();
    await submit();
    expect(session.draft).toMatchObject({
      knownTaskId: "mock-check-1",
      submissionPhase: "import",
      uncertain: true
    });
    expect(
      document.querySelector<HTMLSelectElement>('[aria-label="应用"]')!.disabled
    ).toBe(true);
    expect(create).toHaveBeenCalledTimes(1);
    button("重新导入当前号码").click();
    await settle();
    expect(create).toHaveBeenCalledTimes(1);
    expect(imports).toHaveBeenCalledTimes(2);
    expect((await source.loadTask("mock-check-1")).task.totalCount).toBe(2);
    expect(router.currentRoute.value.path).toBe("/app-checks");
  });
  it("adds a file through the pending task menu and confirms existing numbers separately", async () => {
    const { taskId } = await importedTask(source, {
      requestId: "append-ui",
      appId: "app-a",
      country: "CN",
      simulation: mockCatalog.simulationExample,
      numbers: ["+8613800000001"]
    });
    const create = vi.spyOn(source, "createTask");
    const approve = vi.spyOn(source, "approveTask");
    await mount(`/app-checks/tasks/${taskId}`);
    await command("导入号码");
    expect(document.activeElement?.closest(".el-dialog")).not.toBeNull();
    button("粘贴号码").click();
    await settle();
    input("号码列表", "8613800000001\n8613800000002");
    await settle();
    document
      .querySelector("#app-check-import")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    expect((await source.loadTask(taskId)).task).toMatchObject({
      state: "pre_review",
      totalCount: 2,
      activeCount: 2
    });
    expect((await source.loadImport(taskId))?.receipt).toMatchObject({
      addedCount: 1,
      existingCount: 1
    });
    expect(create).not.toHaveBeenCalled();
    expect(approve).not.toHaveBeenCalled();
  });
  it("keeps an unconfirmed append draft attached to its original task when another import is opened", async () => {
    const config = {
      appId: "app-a",
      country: "CN" as const,
      simulation: mockCatalog.simulationExample
    };
    const first = await source.createTask({ ...config, requestId: "first-import" });
    const second = await source.createTask({ ...config, requestId: "second-import" });
    const imports = vi
      .spyOn(source, "importNumbers")
      .mockRejectedValueOnce(new AppCheckImportUnconfirmed(first.taskId));
    await mount();
    await command("导入号码", first.taskId);
    button("粘贴号码").click();
    await settle();
    input("号码列表", "8613800000001");
    await settle();
    document
      .querySelector("#app-check-import")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    document.querySelector<HTMLButtonElement>(".el-dialog__headerbtn")!.click();
    await settle();
    await command("导入号码", second.taskId);
    expect(document.body.textContent).toContain("请先核对此任务的未确认导入");
    expect(field("号码列表").value).toBe("8613800000001");
    button("重新导入当前号码").click();
    await settle();
    expect(imports.mock.calls.map(([id]) => id)).toEqual([first.taskId, first.taskId]);
    expect((await source.loadTask(first.taskId)).task.totalCount).toBe(1);
    expect((await source.loadTask(second.taskId)).task.totalCount).toBe(0);
  });
  it("keeps unavailable state only in all tasks and offers independent preview", async () => {
    const task = (await source.loadTask("check-mixed")).task;
    vi.spyOn(source, "listTasks").mockResolvedValue({
      tasks: [{ ...task, state: null }],
      truncated: false
    });
    await mount();
    expect(document.querySelector("#tab-all")?.textContent).toContain("全部 1");
    expect(document.querySelector("#tab-terminal")?.textContent).toContain("已结束 0");
    expect(
      document.querySelector(".checks-task-table .checks-status")?.textContent
    ).toBe("状态不可用");
    const trigger = await openMenu("check-mixed");
    expect(menuItems().map((item) => item.textContent?.trim())).toEqual(["预览结果"]);
    trigger.click();
    await settle();
    document.querySelector<HTMLElement>("#tab-terminal")!.click();
    await settle();
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(0);
  });
  it("keeps the list mounted through drawer history and restores the menu trigger", async () => {
    const read = vi.spyOn(source, "listTasks");
    const { router } = await mount();
    const background = document.querySelector(".checks-list-page");
    const trigger = await openMenu("check-live");
    menuItem("预览结果").click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks/tasks/check-live");
    router.back();
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks");
    expect(document.activeElement).toBe(trigger);
    router.forward();
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks/tasks/check-live");
    expect(document.querySelector(".checks-list-page")).toBe(background);
    expect(read).toHaveBeenCalledTimes(1);
    drawer().dispatchEvent(
      new KeyboardEvent("keydown", { key: "Escape", bubbles: true })
    );
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks");
    expect(document.activeElement).toBe(trigger);
    expect(read).toHaveBeenCalledTimes(1);
  });
  it("previews a known task outside the loaded collection without adding it to tabs", async () => {
    const read = vi
      .spyOn(source, "listTasks")
      .mockResolvedValue({ tasks: [], truncated: true });
    const { router } = await mount("/app-checks/tasks/check-preview");
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(0);
    expect(document.querySelector("#tab-all")?.textContent).toContain("全部 0");
    expect(drawer().textContent).toContain("当前预览 100 条");
    drawer().querySelector<HTMLButtonElement>(".el-drawer__close-btn")!.click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks");
    expect(read).toHaveBeenCalledTimes(1);
  });
  it("keeps a frozen creation form mounted behind known-task previews and browser history", async () => {
    const create = vi
      .spyOn(source, "createTask")
      .mockRejectedValue(new AppCheckCreationUnconfirmed("check-empty"));
    const list = vi.spyOn(source, "listTasks");
    const { router, session } = await mount("/app-checks?view=create");
    button("粘贴号码").click();
    await settle();
    input("号码列表", "8613800000001");
    await settle();
    await submit();
    const form = document.querySelector("#app-check-create");
    const request = session.draft.requestId;
    const link = document.querySelector<HTMLAnchorElement>(
      'a[href="/app-checks/tasks/check-empty?view=create"]'
    )!;
    link.focus();
    link.click();
    await settle();
    expect(router.currentRoute.value.fullPath).toBe(
      "/app-checks/tasks/check-empty?view=create"
    );
    expect(document.querySelector("#app-check-create")).toBe(form);
    drawer().querySelector<HTMLButtonElement>(".el-drawer__close-btn")!.click();
    await settle();
    expect(router.currentRoute.value.fullPath).toBe("/app-checks?view=create");
    expect(document.activeElement).toBe(link);
    router.forward();
    await settle();
    expect(drawer().textContent).toContain("新导入号码查询");
    router.back();
    await settle();
    expect(document.querySelector("#app-check-create")).toBe(form);
    expect(session.draft).toMatchObject({
      requestId: request,
      uncertain: true,
      text: "8613800000001"
    });
    expect(create).toHaveBeenCalledTimes(1);
    expect(list).not.toHaveBeenCalled();
  });
  it("retains filters after creation and links a new task excluded by them", async () => {
    const create = vi.spyOn(source, "createTask");
    const { router } = await mount();
    input("搜索查询任务", "华东");
    await settle();
    await openCreate();
    input("号码列表", "8613800000001");
    await settle();
    await submit();
    const { taskId } = await create.mock.results[0].value;
    expect(router.currentRoute.value.path).toBe("/app-checks");
    expect(field("搜索查询任务").value).toBe("华东");
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(1);
    expect(document.getElementById("check-task-" + taskId)).toBeNull();
    button("预览新任务").click();
    await settle();
    expect(router.currentRoute.value.params.taskId).toBe(taskId);
    expect(drawer().querySelector(".checks-status")?.textContent).toBe("待审核");
  });
  it("confirms stopping once and preserves the original snapshot when management fails", async () => {
    const pending = deferred<void>();
    const close = vi
      .spyOn(source, "closeTask")
      .mockRejectedValueOnce(new Error("stop unavailable"))
      .mockReturnValueOnce(pending.promise);
    await mount();
    await command("中止任务", "check-live");
    expect(close).not.toHaveBeenCalled();
    expect(document.activeElement?.closest(".el-dialog")).not.toBeNull();
    expect(
      document.querySelector(".checks-dialog-task")?.parentElement?.textContent
    ).toContain("48,000 个号码");
    button("确认中止任务").click();
    await settle();
    expect(document.body.textContent).toContain("stop unavailable");
    expect(
      document.getElementById("check-task-check-live")?.closest("tr")?.textContent
    ).toContain("32,000 / 48,000");
    button("确认中止任务").click();
    await settle();
    button("确认中止任务").click();
    await settle();
    expect(close).toHaveBeenCalledTimes(2);
    pending.resolve();
    await settle();
  });
  it("scrolls the loaded task set without reads and restores filters, position and focus", async () => {
    for (let i = 0; i < 25; i++)
      await importedTask(source, {
        requestId: "scroll-" + i,
        appId: "app-a",
        country: "CN",
        numbers: ["+8613800000001"],
        simulation: mockCatalog.simulationExample
      });
    const read = vi.spyOn(source, "listTasks");
    const { router } = await mount();
    input("搜索查询任务", "mock-check-");
    await settle();
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(25);
    expect(document.body.textContent).not.toMatch(/每页|上一页|下一页/);
    const viewport = tableViewport();
    viewport.scrollTop = 400;
    viewport.scrollLeft = 120;
    viewport.dispatchEvent(new Event("scroll"));
    await settle();
    expect(read).toHaveBeenCalledTimes(1);
    const taskButton =
      document.querySelectorAll<HTMLButtonElement>(".checks-task-link")[15];
    const id = taskButton.id;
    taskButton.click();
    await settle();
    await router.push("/app-checks");
    await settle();
    expect(field("搜索查询任务").value).toBe("mock-check-");
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(25);
    expect(document.activeElement?.id).toBe(id);
    const restored = tableViewport();
    expect(restored).toBe(viewport);
    expect(restored.scrollTop).toBe(400);
    expect(restored.scrollLeft).toBe(120);
    expect(read).toHaveBeenCalledTimes(1);
    button("高级筛选").click();
    await settle();
    input("创建开始日期", "2099-01-01");
    input("创建结束日期", "2098-01-01");
    await settle();
    expect(document.body.textContent).toContain("开始日期不能晚于结束日期");
  }, 15000);
  it("caps the loaded task set at 100 and replaces it on refresh without continuation", async () => {
    vi.setSystemTime(new Date("2026-10-09T12:00:00Z"));
    const createdIds: string[] = [];
    for (let i = 0; i < 101; i++) {
      const created = await source.createTask({
        requestId: "bounded-" + i,
        appId: "app-a",
        country: "CN",
        simulation: mockCatalog.simulationExample
      });
      createdIds.push(created.taskId);
      vi.setSystemTime(Date.now() + 1000);
    }
    const read = vi.spyOn(source, "listTasks");
    await mount();
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(100);
    expect(document.body.textContent).toContain("列表未完整加载");
    expect(document.body.textContent).toContain("当前载入 100 个");
    input("搜索查询任务", (await source.loadTask(createdIds[0])).task.name!);
    await settle();
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(0);
    input("搜索查询任务", "");
    await settle();
    tableViewport().dispatchEvent(new Event("scroll"));
    await settle();
    expect(read).toHaveBeenCalledTimes(1);
    read.mockResolvedValueOnce({
      tasks: [(await source.loadTask(createdIds[100])).task],
      truncated: false
    });
    button("刷新").click();
    await settle();
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(1);
    expect(document.body.textContent).not.toContain("列表未完整加载");
  }, 15000);
  it("retains the last successful list read time on failure and updates it on recovery", async () => {
    const read = vi.spyOn(source, "listTasks");
    await mount();
    const originalTime = document
      .querySelector(".checks-list-read-at time")!
      .getAttribute("datetime");
    const originalRows = document.querySelectorAll(
      ".checks-task-table tbody tr"
    ).length;
    await vi.advanceTimersByTimeAsync(2000);
    read.mockRejectedValueOnce(new Error("list offline"));
    button("刷新").click();
    await settle();
    expect(
      document.querySelector(".checks-list-read-at time")!.getAttribute("datetime")
    ).toBe(originalTime);
    expect(document.querySelector(".checks-list-read-at")?.textContent).toContain(
      "快照已过期"
    );
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(
      originalRows
    );
    await vi.advanceTimersByTimeAsync(2000);
    button("刷新").click();
    await settle();
    expect(
      document.querySelector(".checks-list-read-at time")!.getAttribute("datetime")
    ).not.toBe(originalTime);
    expect(document.querySelector(".checks-list-read-at")?.textContent).not.toContain(
      "快照已过期"
    );
  }, 15000);
  it("labels the actual number source when switching methods and submitting a replacement file", async () => {
    const create = vi.spyOn(source, "createTask");
    await mount("/app-checks?view=create");
    button("粘贴号码").click();
    await settle();
    input("号码列表", "8613800000001\n8613800000002");
    await settle();
    button("上传文件").click();
    await settle();
    expect(document.querySelector(".checks-input-source")?.textContent).toContain(
      "粘贴号码"
    );
    expect(document.querySelector(".checks-dropzone strong")?.textContent).toContain(
      "替换当前粘贴内容"
    );
    const fileInput = document.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(fileInput, "files", {
      configurable: true,
      value: [
        {
          name: "replacement.txt",
          size: 28,
          arrayBuffer: async () =>
            new TextEncoder().encode("8613800000003\n8613800000004").buffer
        }
      ]
    });
    fileInput.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();
    expect(document.querySelector(".checks-input-source")?.textContent).toContain(
      "replacement.txt"
    );
    expect(document.querySelector(".checks-input-source")?.textContent).not.toContain(
      "当前仍为已粘贴"
    );
    await submit();
    expect(create.mock.calls[0][0]).not.toHaveProperty("numbers");
    const { taskId } = await create.mock.results[0].value;
    expect((await source.loadImport(taskId))?.sourceFile).toBe("replacement.txt");
  });
  it("shows preview scope beside the filters without deriving overall quantities from it", async () => {
    await mount("/app-checks/tasks/check-delivered");
    expect(
      document.querySelector(".checks-result-toolbar .checks-preview-count")
        ?.textContent
    ).toContain("当前预览 100 条");
    input("搜索查询号码", "not-in-preview");
    await settle();
    expect(document.querySelector(".checks-preview-count")?.textContent).toContain(
      "预览内匹配 0 条"
    );
    expect(drawer().querySelector(".checks-preview-meta")?.textContent).toContain(
      "12,800"
    );
  });
  it("offers one row menu with actions for the observed task state", async () => {
    const { router } = await mount();
    for (const [id, labels] of [
      ["check-review", ["导入号码", "核对并启动", "取消任务"]],
      ["check-live", ["预览结果", "中止任务"]],
      ["check-delivered", ["预览结果", "导出结果"]]
    ] as const) {
      const trigger = await openMenu(id);
      expect(menuItems().map((item) => item.textContent?.trim())).toEqual(labels);
      expect(trigger.closest("td")!.querySelectorAll("button")).toHaveLength(1);
      trigger.click();
      await settle();
    }
    await command("预览结果", "check-live");
    expect(router.currentRoute.value.path).toBe("/app-checks/tasks/check-live");
    await openMenu();
    expect(menuItems().map((item) => item.textContent?.trim())).toEqual(["中止任务"]);
  });
  it("disables state changes on stale snapshots and recovers after refresh", async () => {
    await mount("/app-checks/tasks/check-running");
    const read = vi
      .spyOn(source, "loadTask")
      .mockRejectedValueOnce(new Error("offline"));
    previewButton("刷新").click();
    await settle();
    await openMenu();
    expect(menuItem("中止任务").getAttribute("aria-disabled")).toBe("true");
    await openMenu();
    expect(document.body.textContent).toContain("上次快照");
    read.mockRestore();
    previewButton("刷新").click();
    await settle();
    await openMenu();
    expect(menuItem("中止任务").getAttribute("aria-disabled")).not.toBe("true");
  });
  it("keeps API results preview-local and never labels API terminal state as naturally completed", async () => {
    const selected: AppCheckTaskSource = {
      mode: "api",
      catalog: source.catalog.bind(source),
      listTasks: source.listTasks.bind(source),
      loadTask: source.loadTask.bind(source),
      createTask: source.createTask.bind(source)
    };
    await mount("/app-checks/tasks/check-preview", selected);
    expect(document.querySelectorAll(".checks-results-table tbody tr")).toHaveLength(
      100
    );
    expect(document.body.textContent).toContain("当前预览 100 条");
    expect(document.body.textContent).toContain("本次未完整展示");
    expect(drawer().querySelector(".checks-status")?.textContent).toBe("已结束");
    await openMenu();
    expect(menuItem("导出结果").getAttribute("aria-disabled")).toBe("true");
    await openMenu();
    await expand("导入摘要");
    expect(document.body.textContent).toContain("未提供导入摘要");
    await expand("任务记录");
    expect(document.body.textContent).toContain("未提供完整操作历史");
  });
  it("keeps list filters and focus when returning from a business Task", async () => {
    const { router } = await mount();
    input("搜索查询任务", "华东");
    await settle();
    button("华东存量号码复查").click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks/tasks/check-mixed");
    expect(document.body.textContent).toContain("无注册答案");
    expect(document.body.textContent).toContain("内容异常");
    await router.push("/app-checks");
    await settle();
    expect(field("搜索查询任务").value).toBe("华东");
    expect(document.activeElement?.textContent).toBe("华东存量号码复查");
  });
  it("caps result previews, uses owner quantities and keeps diagnostics collapsed", async () => {
    await mount("/app-checks/tasks/check-preview");
    expect(document.querySelectorAll(".checks-results-table tbody tr")).toHaveLength(
      100
    );
    expect(document.body.textContent).toContain("当前预览 100 条");
    expect(document.body.textContent).toContain("120");
    expect(document.body.textContent).not.toMatch(/已注册数|salt|实际 Worker|模拟延迟/);
    await openMenu();
    expect(menuItem("导出结果").getAttribute("aria-disabled")).not.toBe("true");
  });
  it("filters only the snapshot while preserving owner counts and complete export", async () => {
    const numbers = Array.from(
      { length: 1001 },
      (_, i) => "+86138" + String(i).padStart(8, "0")
    );
    const { taskId } = await importedTask(source, {
      requestId: "preview-export",
      appId: "app-a",
      country: "CN",
      numbers,
      simulation: mockCatalog.simulationExample
    });
    await source.approveTask(taskId, numbers.length);
    await source.completeTask(taskId);
    const read = vi.spyOn(source, "loadTask");
    const exported = vi.spyOn(source, "exportTask");
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL = vi.fn(() => "blob:complete-export");
        static revokeObjectURL = vi.fn();
      }
    );
    await mount("/app-checks/tasks/" + taskId);
    const counts = drawer().querySelector(".checks-preview-meta")!.textContent;
    expect(counts).toContain("1,001");
    input("搜索查询号码", numbers[1000]);
    await settle();
    expect(document.querySelectorAll(".checks-results-table tbody tr")).toHaveLength(0);
    expect(document.body.textContent).toContain("当前预览中没有匹配结果");
    expect(document.body.textContent).toContain("当前预览 100 条 · 预览内匹配 0 条");
    input("搜索查询号码", "");
    const filter = document.querySelector<HTMLSelectElement>(
      '[aria-label="筛选查询结果"]'
    )!;
    filter.value = "failed";
    filter.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();
    expect(document.querySelectorAll(".checks-results-table tbody tr")).toHaveLength(0);
    expect(drawer().querySelector(".checks-preview-meta")!.textContent).toBe(counts);
    document
      .querySelector('[aria-label="结果预览，可滚动"]')!
      .dispatchEvent(new Event("scroll"));
    await settle();
    expect(read).toHaveBeenCalledTimes(1);
    await command("导出结果");
    expect(document.querySelector(".checks-export-count")!.textContent).toContain(
      "997 条"
    );
    button("生成 CSV 文件").click();
    await settle();
    expect(exported).toHaveBeenCalledExactlyOnceWith(taskId, "all");
    expect(document.querySelector("a[download]")?.getAttribute("href")).toBe(
      "blob:complete-export"
    );
    expect(document.querySelector(".checks-download-ready")?.textContent).toContain(
      "997 条"
    );
  });
  it("shows only an available import summary and discards late summaries across tasks", async () => {
    const pending = deferred<Awaited<ReturnType<typeof source.loadImport>>>();
    const read = vi.spyOn(source, "loadImport");
    const { router } = await mount("/app-checks/tasks/check-delivered");
    expect(read).not.toHaveBeenCalled();
    await expand("导入摘要");
    expect(read).toHaveBeenCalledExactlyOnceWith("check-delivered");
    expect(document.body.textContent).toContain("october_existing_numbers.txt");
    expect(document.body.textContent).toContain("最终提交12,800");
    expect(disclosure("导入摘要").querySelector("table")).toBeNull();
    read.mockReturnValueOnce(pending.promise);
    previewButton("刷新").click();
    await settle();
    await router.push("/app-checks/tasks/check-empty");
    await settle();
    await expand("导入摘要");
    pending.resolve({
      sourceFile: "stale-source.txt",
      summary: {
        inputCount: 1,
        emptyCount: 0,
        validCount: 1,
        duplicateCount: 0,
        invalidCount: 0,
        blocked: false,
        overLimit: false
      }
    });
    await settle();
    expect(document.body.textContent).toContain("未提供导入摘要");
    expect(document.body.textContent).not.toContain("stale-source.txt");
    expect(disclosure("导入摘要").querySelector("table")).toBeNull();
  });
  it("preserves known data on read errors and permits an explicit refresh", async () => {
    await mount("/app-checks/tasks/check-read-error");
    previewButton("刷新").click();
    await settle();
    expect(document.body.textContent).toContain("Mock 读取失败");
    expect(document.body.textContent).toContain("已注册");
    previewButton("刷新").click();
    await settle();
    expect(document.body.textContent).not.toContain("Mock 读取失败");
  });
  it("hides internal Tasks in the product list and retains an honest empty result state", async () => {
    const { router } = await mount();
    expect(document.body.textContent).not.toContain("check-missing");
    expect(document.body.textContent).not.toContain("Managed Task");
    await router.push("/app-checks/tasks/check-empty");
    await settle();
    expect(document.body.textContent).toContain("当前暂无可预览结果");
  });
  it("creates a preserved draft in one step with an automatic name and deduplicated numbers", async () => {
    const pending = deferred<{ taskId: string }>();
    const create = vi.spyOn(source, "createTask").mockReturnValue(pending.promise);
    vi.spyOn(source, "importNumbers").mockResolvedValue({
      taskId: "check-review",
      inputCount: 1,
      emptyCount: 0,
      duplicateCount: 0,
      uniqueCount: 1,
      addedCount: 1,
      existingCount: 0
    });
    const { router } = await mount();
    await openCreate();
    input("号码列表", " 8613800000001 \n+8613800000001");
    await settle();
    expect(field("模拟 JSON")).toBeNull();
    expect(document.body.textContent).not.toContain("03 · 模拟描述");
    await closeDraft();
    button("创建任务").click();
    await settle();
    expect(field("号码列表").value).toBe(" 8613800000001 \n+8613800000001");
    await submit();
    expect(field("任务名称")).toBeNull();
    expect(document.querySelector("#app-check-create table")).toBeNull();
    await submit();
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0][0]).toMatchObject({
      simulation: mockCatalog.simulationExample
    });
    pending.resolve({ taskId: "check-review" });
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks");
    expect(
      document.querySelector(".checks-created-row #check-task-check-review")
    ).not.toBeNull();
    expect(document.activeElement?.id).toBe("check-task-check-review");
    button("预览新任务").click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks/tasks/check-review");
  });
  it("rejects invalid numbers and preserves the draft after an oversized file", async () => {
    const create = vi.spyOn(source, "createTask");
    await mount();
    await openCreate();
    input("号码列表", "+12025550123\n+12025550123");
    await settle();
    await submit();
    expect(create).not.toHaveBeenCalled();
    input("号码列表", "+8613800000001\n+8613800000001");
    await settle();
    expect(document.body.textContent).toContain("自动去重");
    expect(document.querySelector("#app-check-create table")).toBeNull();
    expect(
      document.querySelector<HTMLButtonElement>(
        "#app-check-create button[type=submit]"
      )!.disabled
    ).toBe(false);
    button("上传文件").click();
    await settle();
    const file = document.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(file, "files", {
      configurable: true,
      value: [{ size: 10 * 1024 * 1024 + 1, name: "large.txt" }]
    });
    file.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();
    expect(document.body.textContent).toContain("不能超过 10 MiB");
    button("粘贴号码").click();
    await settle();
    expect(field("号码列表").value).toBe("+8613800000001\n+8613800000001");
  });
  it("does not unlock an uncertain creation after closing and reopening", async () => {
    const create = vi
      .spyOn(source, "createTask")
      .mockRejectedValue(new AppCheckCreationUnconfirmed("known"));
    await mount();
    await openCreate();
    input("号码列表", "+8613800000001");
    await settle();
    await submit();
    await submit();
    expect(
      document.querySelector('a[href="/app-checks/tasks/known?view=create"]')
    ).not.toBeNull();
    await closeDraft();
    button("创建任务").click();
    await settle();
    expect(
      document.querySelector<HTMLButtonElement>(
        "#app-check-create button[type=submit]"
      )!.disabled
    ).toBe(true);
    await submit();
    expect(create).toHaveBeenCalledTimes(1);
  });
  it.each([undefined, "check-empty"])(
    "requires manual acknowledgement to end an unconfirmed draft (%s)",
    async (knownTaskId) => {
      const create = vi
        .spyOn(source, "createTask")
        .mockRejectedValue(new AppCheckCreationUnconfirmed(knownTaskId));
      const read = vi.spyOn(source, "loadTask");
      const { session, router } = await mount("/app-checks?view=create");
      button("粘贴号码").click();
      await settle();
      input("号码列表", "8613800000001\n8613800000002");
      await settle();
      await submit();
      const originalRequestId = create.mock.calls[0][0].requestId;
      expect(session.draft.uncertain).toBe(true);
      expect(
        document.querySelector<HTMLButtonElement>(
          "#app-check-create button[type=submit]"
        )!.disabled
      ).toBe(true);
      expect(read).not.toHaveBeenCalled();
      if (knownTaskId) {
        button("读取关联任务").click();
        await settle();
        expect(read).toHaveBeenCalledExactlyOnceWith(knownTaskId);
        expect(
          document.querySelector(".checks-recovery-observation")?.textContent
        ).toContain("任务已记录 1 个号码");
        expect(document.body.textContent).toContain("本次提交仍待人工核对");
        expect(session.draft.uncertain).toBe(true);
        expect(
          document.querySelector<HTMLButtonElement>(
            "#app-check-create button[type=submit]"
          )!.disabled
        ).toBe(true);
      }
      button("结束本次草稿").click();
      await settle();
      expect(button("确认结束草稿").disabled).toBe(true);
      button("继续核对").click();
      await settle();
      expect(session.draft.requestId).toBe(originalRequestId);
      button("结束本次草稿").click();
      await settle();
      const ack = document.querySelector<HTMLInputElement>(
        '[aria-label="已了解原提交仍需单独处理"]'
      )!;
      ack.checked = true;
      ack.dispatchEvent(new Event("change", { bubbles: true }));
      await settle();
      button("确认结束草稿").click();
      await settle();
      expect(router.currentRoute.value.query.view).toBe("create");
      expect(create).toHaveBeenCalledTimes(1);
      expect(session.draft.requestId).not.toBe(originalRequestId);
      expect(session.draft.uncertain).toBe(false);
      expect(session.draft.text).toBe("");
      expect(session.draft.report).toBeUndefined();
      expect(session.unconfirmedSubmissions).toHaveLength(1);
      expect(session.unconfirmedSubmissions[0]).toMatchObject({
        requestId: originalRequestId,
        knownTaskId,
        expectedCount: 2,
        sourceFile: "粘贴号码"
      });
      expect(JSON.stringify(session.unconfirmedSubmissions)).not.toContain(
        "8613800000001"
      );
      expect(document.body.textContent).toContain("待核对提交关联信息");
      await router.push("/app-checks");
      await settle();
      button("创建任务").click();
      await settle();
      expect(session.unconfirmedSubmissions[0].requestId).toBe(originalRequestId);
      expect(
        document.querySelector<HTMLButtonElement>(
          "#app-check-create button[type=submit]"
        )!.disabled
      ).toBe(true);
    }
  );
  it("does not end a draft while its original submission is still pending", async () => {
    const pending = deferred<{ taskId: string }>();
    const create = vi.spyOn(source, "createTask").mockReturnValue(pending.promise);
    const { router, session } = await mount("/app-checks?view=create");
    button("粘贴号码").click();
    await settle();
    input("号码列表", "8613800000001");
    await settle();
    await submit();
    await router.push("/app-checks");
    await settle();
    button("创建任务").click();
    await settle();
    expect(button("结束本次草稿").disabled).toBe(true);
    pending.resolve({ taskId: "check-empty" });
    await settle();
    expect(session.draft.uncertain).toBe(true);
    expect(session.draft.knownTaskId).toBe("check-empty");
    expect(button("结束本次草稿").disabled).toBe(false);
    expect(
      document.querySelector<HTMLButtonElement>(
        "#app-check-create button[type=submit]"
      )!.disabled
    ).toBe(true);
    expect(create).toHaveBeenCalledTimes(1);
  });
  it("keeps creation and approval separate in Mock, without any network request", async () => {
    const approve = vi.spyOn(source, "approveTask");
    await mount("/app-checks/tasks/check-review");
    expect(drawer().textContent).toContain("等待核对并启动");
    expect(disclosure("导入摘要").open).toBe(true);
    expect(document.body.textContent).toContain("us_october_batch_01.txt");
    expect(field("搜索查询号码")).toBeNull();
    await command("核对并启动");
    expect(approve).not.toHaveBeenCalled();
    button("确认启动").click();
    await settle();
    expect(approve).toHaveBeenCalledWith("check-review", 24000);
    expect((await source.loadTask("check-review")).task.state).toBe("running_visible");
    expect(document.body.textContent).toContain("任务已核对并启动");
    expect(field("搜索查询号码")).not.toBeNull();
  });
  it("does not enable prototype approval or export for an API-only source", async () => {
    const selected: AppCheckTaskSource = {
      mode: "api",
      catalog: source.catalog.bind(source),
      listTasks: source.listTasks.bind(source),
      loadTask: source.loadTask.bind(source),
      createTask: source.createTask.bind(source)
    };
    const { router } = await mount("/app-checks/tasks/check-review", selected);
    await openMenu();
    expect(menuItem("核对并启动").getAttribute("aria-disabled")).toBe("true");
    expect(menuItem("核对并启动").textContent).toContain("暂未接入");
    await openMenu();
    await router.push("/app-checks/tasks/check-mixed");
    await settle();
    await openMenu();
    expect(menuItem("导出结果").getAttribute("aria-disabled")).toBe("true");
  });
  it("drops stale detail and verification responses after navigation", async () => {
    const delayed = deferred<CheckDetail>();
    const read = vi.spyOn(source, "loadTask").mockReturnValueOnce(delayed.promise);
    const { router } = await mount("/app-checks/tasks/check-mixed");
    await router.push("/app-checks/tasks/check-unregistered");
    await settle();
    const search = field("搜索查询号码");
    search.focus();
    input("搜索查询号码", "138");
    delayed.resolve(await new MockAppCheckTaskSource().loadTask("check-mixed"));
    await settle();
    expect(drawer().querySelector(".checks-preview-title")?.textContent).toBe(
      "补充号码查询"
    );
    expect(read).toHaveBeenCalledTimes(2);
    expect(document.activeElement).toBe(search);
    expect(search.value).toBe("138");
    const check = deferred<Map<string, verification.Verification>>();
    vi.spyOn(verification, "verifyPreview").mockReturnValue(check.promise);
    await diagnostics();
    button("核对当前预览").click();
    await settle();
    await router.push("/app-checks/tasks/check-empty");
    await settle();
    check.resolve(new Map([["r-1", { status: "mismatch", reason: "stale-result" }]]));
    await settle();
    expect(document.body.textContent).not.toContain("stale-result");
  });
  it("invalidates verification when refresh returns a new snapshot", async () => {
    const check = deferred<Map<string, verification.Verification>>();
    vi.spyOn(verification, "verifyPreview").mockReturnValue(check.promise);
    await mount("/app-checks/tasks/check-mixed");
    await diagnostics();
    button("核对当前预览").click();
    await settle();
    previewButton("刷新").click();
    await settle();
    check.resolve(new Map([["r-1", { status: "mismatch", reason: "old-snapshot" }]]));
    await settle();
    expect(document.body.textContent).not.toContain("old-snapshot");
  });
  it("explains unavailable diagnostic hashing without blocking ordinary creation", async () => {
    vi.stubGlobal("crypto", undefined);
    const create = vi.spyOn(source, "createTask");
    const { router } = await mount("/app-checks/tasks/check-mixed");
    await diagnostics();
    expect(button("核对当前预览").disabled).toBe(true);
    expect(document.body.textContent).toContain("不支持 Web Crypto");
    await router.push("/app-checks");
    await settle();
    await openCreate();
    input("号码列表", "+8613800000001");
    await settle();
    await submit();
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0][0].requestId).toMatch(/^app-check-/);
  });
});
