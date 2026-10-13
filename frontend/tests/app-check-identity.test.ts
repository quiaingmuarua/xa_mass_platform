import { webcrypto } from "node:crypto";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
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
import { consoleIdentity, identityRouteAllowed } from "../src/console-identity";
import { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";
import { inspectImport, type ImportOptions } from "../src/app-checks/import-model";
import {
  type AppCheckCaller,
  AppCheckCreationUnconfirmed,
  AppCheckImportUnconfirmed
} from "../src/app-checks/task-source";
import type { CheckDetail, CreateCheckTask } from "../src/app-checks/model";

class ImportWorker {
  onmessage?: (event: MessageEvent) => void;
  onerror?: () => void;
  stopped = false;
  postMessage(input: { content: string; options: ImportOptions }) {
    queueMicrotask(() => {
      if (!this.stopped)
        this.onmessage?.({
          data: {
            text: input.content,
            report: inspectImport(input.content, input.options)
          }
        } as MessageEvent);
    });
  }
  terminate() {
    this.stopped = true;
  }
}
let app: VueApp | undefined;
const fetcher = vi.fn();
beforeAll(async () => {
  // Keep Vite's cold transformation of the lazy route outside the workflow assertion clock.
  await import("../src/app-checks/AppCheckPage.vue");
});
beforeEach(() => {
  consoleIdentity.select("1000");
  vi.useFakeTimers();
  vi.stubEnv("VITE_RUNTIME_DATA_SOURCE", "mock");
  vi.stubGlobal("fetch", fetcher.mockReset());
  vi.stubGlobal("crypto", webcrypto);
  vi.stubGlobal("Worker", ImportWorker);
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  document.body.replaceChildren();
  consoleIdentity.select("1000");
  expect(fetcher).not.toHaveBeenCalled();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});
async function settle() {
  await vi.advanceTimersByTimeAsync(250);
  await nextTick();
}
async function mount(user = "1001", path = "/app-checks") {
  consoleIdentity.select(user);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: consoleRoutes
  });
  router.beforeEach((to) =>
    identityRouteAllowed(consoleIdentity.current.value, to)
      ? undefined
      : { path: "/app-checks", replace: true }
  );
  await router.push(path);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp(App).use(createPinia()).use(router);
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
  ].forEach((component) => app!.use(component));
  app.mount(host);
  await settle();
  return router;
}
async function select(user: string) {
  consoleIdentity.select(user);
  await settle();
}
function button(text: string) {
  const result = [...document.querySelectorAll<HTMLButtonElement>("button")].find(
    (entry) => entry.textContent?.trim() === text
  );
  expect(result, text).toBeDefined();
  return result!;
}
function numberInput() {
  return document.querySelector<HTMLTextAreaElement>(
    'textarea[aria-label="号码列表"]'
  )!;
}
async function draft(number = "8613800000001") {
  button("创建任务").click();
  await settle();
  button("粘贴号码").click();
  await settle();
  numberInput().value = number;
  numberInput().dispatchEvent(new Event("input", { bubbles: true }));
  await settle();
}
async function submit() {
  document
    .querySelector("#app-check-create")!
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await settle();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (value: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

describe("shared identity and App Checks workspaces", () => {
  it("keeps identity switching available when Runtime configuration is invalid", async () => {
    vi.stubEnv("VITE_RUNTIME_API_BASE_URL", "https://invalid.example/api");
    const router = await mount("1000", "/runtime/tasks");
    expect(document.querySelector('[data-testid="config-error"]')).not.toBeNull();
    expect(document.querySelector('[aria-label="切换开发身份"]')).not.toBeNull();
    await select("1001");
    await vi.dynamicImportSettled();
    await settle();
    expect(router.currentRoute.value.fullPath).toBe("/app-checks");
    expect(document.querySelector('[data-testid="config-error"]')).toBeNull();
    expect(document.querySelector("h1")?.textContent).toBe("我的任务 · 当前批次");
  });

  it("creates for each operator, filters their loaded tasks, and retains admin operations", async () => {
    const create = vi.spyOn(MockAppCheckTaskSource.prototype, "createTask");
    const configuration = vi.spyOn(
      MockAppCheckTaskSource.prototype,
      "loadConfiguration"
    );
    const router = await mount("1001", "/runtime/tasks");
    expect(router.currentRoute.value.path).toBe("/app-checks");
    expect(document.querySelector('a[href="/runtime/tasks"]')).toBeNull();
    expect(document.querySelector('a[href="/messages"]')).toBeNull();
    expect(document.querySelector("h1")?.textContent).toBe("我的任务 · 当前批次");
    await router.push("/app-checks?view=configuration");
    await settle();
    expect(router.currentRoute.value.fullPath).toBe("/app-checks");
    expect(configuration).not.toHaveBeenCalled();
    await draft();
    await submit();
    const a = (await create.mock.results[0]!.value).taskId as string;
    expect(create.mock.calls[0]![0].userId).toBe("1001");
    await select("1002");
    expect(document.getElementById("check-task-" + a)).toBeNull();
    await draft("8613800000002");
    await submit();
    const b = (await create.mock.results[1]!.value).taskId as string;
    expect(create.mock.calls[1]![0].userId).toBe("1002");
    await select("1001");
    expect(document.getElementById("check-task-" + a)).not.toBeNull();
    expect(document.getElementById("check-task-" + b)).toBeNull();
    await select("1000");
    expect(document.getElementById("check-task-" + a)).not.toBeNull();
    expect(document.getElementById("check-task-" + b)).not.toBeNull();
    expect(document.querySelector('a[href="/runtime/tasks"]')).not.toBeNull();
    expect(document.body.textContent).toContain("创建者");
    expect(document.body.textContent).toContain("应用与运行策略");
  });

  it("refuses a foreign direct task before showing results or loading auxiliaries", async () => {
    const auxiliary = vi.spyOn(MockAppCheckTaskSource.prototype, "loadImport");
    await mount("1001", "/app-checks/tasks/check-delivered");
    const drawer = document.querySelector(".checks-preview-drawer")!;
    expect(drawer.textContent).toContain("当前开发身份不能访问此任务");
    expect(drawer.textContent).not.toContain("存量号码 · 十月注册状态查询");
    expect(drawer.querySelector(".checks-action-group")).toBeNull();
    expect(drawer.querySelector("table")).toBeNull();
    expect(auxiliary).not.toHaveBeenCalled();
  });

  it("does not fill a Project window occupied by other users with extra reads", async () => {
    const list = vi
      .spyOn(MockAppCheckTaskSource.prototype, "listTasks")
      .mockResolvedValue({
        tasks: Array.from({ length: 100 }, (_, i) => ({
          taskId: "b-" + i,
          userId: "1002",
          appId: "app-a",
          workerGroupId: "app-a-sim",
          createdAtMillis: i,
          managed: false,
          state: "pre_review" as const
        })),
        truncated: true
      });
    await mount();
    expect(document.querySelectorAll(".checks-task-table tbody tr")).toHaveLength(0);
    expect(document.body.textContent).toContain("当前载入范围内没有我的任务");
    expect(document.body.textContent).toContain("不代表全部历史任务");
    expect(list).toHaveBeenCalledTimes(1);
  });

  it("keeps late creation and frozen input with its original user and stops chained import", async () => {
    const gate = deferred<void>();
    const original = MockAppCheckTaskSource.prototype.createTask;
    const create = vi
      .spyOn(MockAppCheckTaskSource.prototype, "createTask")
      .mockImplementationOnce(async function (
        this: MockAppCheckTaskSource,
        caller: AppCheckCaller,
        input: CreateCheckTask
      ) {
        await gate.promise;
        return original.call(this, caller, input);
      });
    const imported = vi.spyOn(MockAppCheckTaskSource.prototype, "importNumbers");
    const router = await mount();
    await draft();
    await submit();
    const originalRequest = structuredClone(create.mock.calls[0]!);
    await select("1002");
    await draft("8613800000002");
    gate.resolve();
    await settle();
    expect(imported).not.toHaveBeenCalled();
    expect(numberInput().value).toBe("8613800000002");
    expect(router.currentRoute.value.query.view).toBe("create");
    await select("1001");
    button("创建任务").click();
    await settle();
    expect(numberInput().value).toBe("8613800000001");
    expect(document.body.textContent).toContain("尚未开始导入");
    expect(document.body.textContent).toContain(
      (await create.mock.results[0]!.value).taskId
    );
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0]!).toEqual(originalRequest);
  });

  it("keeps an unknown creation frozen across switching and reconciles with the same creator", async () => {
    const create = vi
      .spyOn(MockAppCheckTaskSource.prototype, "createTask")
      .mockRejectedValueOnce(new AppCheckCreationUnconfirmed());
    await mount();
    await draft();
    await submit();
    const submitted = structuredClone(create.mock.calls[0]!);
    await select("1002");
    await select("1001");
    button("创建任务").click();
    await settle();
    button("使用原请求核对创建").click();
    await settle();
    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[1]!).toEqual(submitted);
    expect(submitted[0].userId).toBe("1001");
  });

  it("retains late import failure and its known task without overwriting the other user's draft", async () => {
    const gate = deferred<never>();
    const imported = vi
      .spyOn(MockAppCheckTaskSource.prototype, "importNumbers")
      .mockImplementationOnce(() => gate.promise);
    const create = vi.spyOn(MockAppCheckTaskSource.prototype, "createTask");
    await mount();
    await draft();
    await submit();
    const target = imported.mock.calls[0]![1];
    await select("1002");
    await draft("8613800000002");
    gate.reject(new AppCheckImportUnconfirmed(target, "导入确认丢失"));
    await settle();
    expect(numberInput().value).toBe("8613800000002");
    expect(document.body.textContent).not.toContain("导入确认丢失");
    await select("1001");
    button("创建任务").click();
    await settle();
    expect(document.body.textContent).toContain("导入确认丢失");
    expect(document.body.textContent).toContain(target);
    expect(create).toHaveBeenCalledTimes(1);
    expect(numberInput().value).toBe("8613800000001");
  });

  it("keeps a late export with its original caller and discards its download after switching", async () => {
    const pending = deferred<{ blob: Blob; fileName: string; count: number }>();
    const exported = vi
      .spyOn(MockAppCheckTaskSource.prototype, "exportTask")
      .mockImplementationOnce(() => pending.promise);
    await mount("1001", "/app-checks/tasks/check-unregistered");
    document
      .querySelector<HTMLButtonElement>(
        '.checks-preview-drawer [aria-label^="任务操作"]'
      )!
      .click();
    await settle();
    [...document.querySelectorAll<HTMLElement>('[role="menuitem"]')]
      .find((item) => item.textContent?.trim() === "导出结果")!
      .click();
    await settle();
    button("生成 CSV 文件").click();
    await settle();
    expect(exported.mock.calls[0]![0]).toMatchObject({
      userId: "1001",
      role: "operator"
    });
    await select("1002");
    pending.resolve({ blob: new Blob(["original-user"]), fileName: "a.csv", count: 1 });
    await settle();
    expect(document.querySelector("a[download]")).toBeNull();
    expect(document.body.textContent).not.toContain("a.csv");
    expect(exported).toHaveBeenCalledTimes(1);
  });

  it("discards a prior user's detail response and focus after switching", async () => {
    const gate = deferred<CheckDetail>();
    vi.spyOn(MockAppCheckTaskSource.prototype, "loadTask").mockImplementationOnce(
      () => gate.promise
    );
    const router = await mount("1001", "/app-checks/tasks/late-a");
    await select("1002");
    gate.resolve({
      task: {
        taskId: "late-a",
        userId: "1001",
        name: "Late user A",
        managed: false,
        workerGroupId: "app-a-sim",
        createdAtMillis: 1,
        state: "pre_review",
        appId: "app-a"
      },
      results: [],
      resultsTruncated: false
    });
    await settle();
    expect(router.currentRoute.value.fullPath).toBe("/app-checks");
    expect(document.body.textContent).not.toContain("Late user A");
    const drawer = document.querySelector(".checks-preview-drawer");
    expect(drawer?.closest<HTMLElement>(".el-overlay")?.style.display).toBe("none");
    expect(document.activeElement?.closest(".checks-preview-drawer")).toBeNull();
  });
});
