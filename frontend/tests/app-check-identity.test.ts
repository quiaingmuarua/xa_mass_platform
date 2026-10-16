import { type TaskCaller } from "@/task-caller";
import type * as DevelopmentUsersModule from "@/development-users";
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
import { consoleIdentity } from "../src/console-identity";
import { installConsoleAccess, loginDestination } from "@/router/access";
import { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";
import { MockMessageTaskSource } from "../src/message-campaigns/mock-task-source";
import { inspectImport, type ImportOptions } from "../src/app-checks/import-model";
import { AppCheckCreationUnconfirmed } from "../src/app-checks/task-source";
import type { CheckDetail, CreateCheckTask } from "../src/app-checks/model";
import { inspectRecipientInput, type RecipientLimits } from "@/files/phone-numbers";

class ImportWorker {
  onmessage?: (event: MessageEvent) => void;
  onerror?: () => void;
  stopped = false;
  postMessage(
    input:
      | { content: string; options: ImportOptions }
      | { source: string; country: string; limits: RecipientLimits }
  ) {
    queueMicrotask(async () => {
      const data =
        "options" in input
          ? { text: input.content, report: inspectImport(input.content, input.options) }
          : {
              value: await inspectRecipientInput(
                input.source,
                input.country,
                input.limits
              )
            };
      if (!this.stopped) this.onmessage?.({ data } as MessageEvent);
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
  await Promise.all([
    import("../src/app-checks/AppCheckPage.vue"),
    import("../src/views/ConsoleEntryView.vue"),
    import("../src/message-campaigns/MessagePage.vue"),
    import("../src/views/WorkerRuntimeView.vue")
  ]);
});
beforeEach(() => {
  consoleIdentity.logout();
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
  consoleIdentity.logout();
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
async function mount(user?: string, path = "/app-checks") {
  if (user) consoleIdentity.login(user);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: consoleRoutes
  });
  installConsoleAccess(router);
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
async function login(user: string) {
  if (consoleIdentity.current.value) {
    button("退出").click();
    await settle();
  }
  const control = document.querySelector<HTMLInputElement>('[aria-label="选择用户"]')!;
  expect(control).not.toBeNull();
  control.click();
  await settle();
  const dropdown = document.getElementById(control.getAttribute("aria-controls")!)!;
  [...dropdown.querySelectorAll<HTMLElement>('[role="option"]')]
    .find((node) => node.textContent?.includes(user))!
    .click();
  await settle();
  button("进入控制台").click();
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
    'textarea[aria-label="种子列表"]'
  )!;
}
function input(label: string, value: string) {
  const control = document.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `[aria-label="${label}"]`
  )!;
  expect(control).not.toBeNull();
  control.value = value;
  control.dispatchEvent(new Event("input", { bubbles: true }));
}
async function select(label: string, text: string) {
  const control = document.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  control.click();
  await settle();
  const dropdown = document.getElementById(control.getAttribute("aria-controls")!)!;
  const option = [...dropdown.querySelectorAll<HTMLElement>('[role="option"]')].find(
    (node) => node.textContent?.trim() === text
  );
  expect(option, text).toBeDefined();
  option!.click();
  await settle();
}
async function draft(number = "8613800000001") {
  button("创建任务").click();
  await settle();
  button("粘贴种子").click();
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

vi.mock("@/development-users", async (importOriginal) => {
  const actual = await importOriginal<typeof DevelopmentUsersModule>();
  return {
    ...actual,
    developmentUsers: [
      ...actual.developmentUsers,
      {
        userId: "1003",
        displayName: "用户 C",
        role: "operator",
        projectIds: ["app-checks", "messages"]
      },
      { userId: "1004", displayName: "管理员 D", role: "admin", projectIds: [] }
    ]
  };
});

describe("console login and shared task access", () => {
  it("manages an in-memory Messages-only user, creates its task and preserves shared ownership across login", async () => {
    const create = vi.spyOn(MockMessageTaskSource.prototype, "createTask");
    const checks = vi.spyOn(MockAppCheckTaskSource.prototype, "listTasks");
    const router = await mount(undefined, "/?view=users");
    await login("1000");
    expect(router.currentRoute.value.fullPath).toBe("/?view=users");
    expect(document.querySelector("h1")?.textContent).toBe("用户管理");
    button("新增用户").click();
    await settle();
    input("userId", "userc");
    input("用户名称", "消息用户 C");
    await select("支持的 Project", "messages");
    document
      .getElementById("console-user-form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    expect(consoleIdentity.users.find((user) => user.userId === "userc")).toMatchObject(
      { role: "operator", projectIds: ["messages"] }
    );
    await login("userc");
    expect(router.currentRoute.value.fullPath).toBe("/messages");
    expect(document.querySelector('a[href="/app-checks"]')).toBeNull();
    expect(document.querySelector('a[href="/?view=users"]')).toBeNull();
    for (const path of ["/app-checks", "/app-checks/tasks/known", "/?view=users"]) {
      await router.push(path);
      await settle();
      expect(router.currentRoute.value.fullPath).toBe("/messages");
    }
    expect(checks).not.toHaveBeenCalled();
    await router.push("/messages?view=create");
    await settle();
    input("模板内容", "新用户 Messages 验证");
    await settle();
    document
      .getElementById("message-task-form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    const id = (await create.mock.results[0]!.value).taskId as string;
    expect(create.mock.calls[0]![0]).toEqual({ userId: "userc", role: "operator" });
    expect(document.getElementById("message-task-" + id)).not.toBeNull();
    await login("1001");
    await router.push("/messages");
    await settle();
    expect(document.getElementById("message-task-" + id)).toBeNull();
    await login("1000");
    await router.push("/messages");
    await settle();
    expect(document.getElementById("message-task-" + id)).not.toBeNull();
    await router.push("/?view=users");
    await settle();
    document.querySelector<HTMLButtonElement>('[aria-label="编辑用户 userc"]')!.click();
    await settle();
    await select("支持的 Project", "messages");
    await select("支持的 Project", "app-checks");
    document
      .getElementById("console-user-form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    await login("userc");
    expect(router.currentRoute.value.fullPath).toBe("/app-checks");
    await router.push("/messages/tasks/" + id);
    await settle();
    expect(router.currentRoute.value.fullPath).toBe("/app-checks");
  }, 15000);
  it("lands an unassigned user on an empty console without protected task reads", async () => {
    consoleIdentity.login("1000");
    await consoleIdentity.saveUser({
      userId: "noproject",
      displayName: "待分配用户",
      role: "operator",
      projectIds: ["default"]
    });
    consoleIdentity.logout();
    const checks = vi.spyOn(MockAppCheckTaskSource.prototype, "listTasks");
    const messages = vi.spyOn(MockMessageTaskSource.prototype, "listTasks");
    const router = await mount();
    await login("noproject");
    expect(router.currentRoute.value.fullPath).toBe("/?view=home");
    expect(document.body.textContent).toContain("尚未分配可用的业务 Project");
    expect(checks).not.toHaveBeenCalled();
    expect(messages).not.toHaveBeenCalled();
    button("退出").click();
    await settle();
    expect(document.querySelector(".runtime-shell")).toBeNull();
  });
  it("serves anonymous root login and restores a remembered user's permitted home", async () => {
    const router = await mount(undefined, "/");
    expect(router.currentRoute.value.name).toBe("login");
    expect(document.querySelector("h1")?.textContent).toBe("登录控制台");
    await login("1001");
    await router.push("/");
    await settle();
    expect(router.currentRoute.value.fullPath).toBe("/app-checks");
  });

  it("guards an anonymous deep link, returns to it after login and prevents history from reopening after logout", async () => {
    const list = vi.spyOn(MockAppCheckTaskSource.prototype, "listTasks");
    const router = await mount(undefined, "/app-checks?view=create");
    expect(router.currentRoute.value.path).toBe("/");
    expect(document.querySelector("h1")?.textContent).toBe("登录控制台");
    expect(document.querySelector(".runtime-shell")).toBeNull();
    expect(list).not.toHaveBeenCalled();
    await login("1001");
    expect(router.currentRoute.value.fullPath).toBe("/app-checks?view=create");
    expect(document.getElementById("app-check-create")).not.toBeNull();
    button("退出").click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/");
    router.back();
    await settle();
    expect(document.querySelector(".runtime-shell")).toBeNull();
    expect(document.getElementById("app-check-create")).toBeNull();
  });
  it.each(["1001", "1002", "1003"])(
    "uses the same role policy for %s menus and direct links",
    async (user) => {
      const configuration = vi.spyOn(
        MockAppCheckTaskSource.prototype,
        "loadConfiguration"
      );
      const router = await mount(undefined, "/messages?view=create");
      await login(user);
      expect(router.currentRoute.value.fullPath).toBe("/messages?view=create");
      expect(document.querySelector("#message-task-form")).not.toBeNull();
      expect(document.querySelector('a[href="/messages"]')).not.toBeNull();
      for (const path of [
        "/?view=users",
        "/runtime/tasks",
        "/task-workbench",
        "/sms",
        "/api-reference",
        "/app-checks?view=configuration"
      ]) {
        await router.push(path);
        await settle();
        expect(router.currentRoute.value.fullPath).toBe("/app-checks");
      }
      for (const path of ["/?view=users", "/runtime/tasks", "/task-workbench", "/sms"])
        expect(document.querySelector(`a[href="${path}"]`)).toBeNull();
      expect(document.querySelector('[aria-label="切换开发身份"]')).toBeNull();
      expect(configuration).not.toHaveBeenCalled();
      expect(loginDestination(router, "//outside.example")).toBe("/app-checks");
    }
  );
  it("keeps logout available when Runtime configuration is invalid", async () => {
    vi.stubEnv("VITE_RUNTIME_API_BASE_URL", "https://invalid.example/api");
    const router = await mount("1000", "/runtime/tasks");
    expect(document.querySelector('[data-testid="config-error"]')).not.toBeNull();
    await login("1001");
    expect(router.currentRoute.value.fullPath).toBe("/app-checks");
    expect(document.querySelector('[data-testid="config-error"]')).toBeNull();
    expect(document.querySelector("h1")?.textContent).toBe("我的任务 · 当前批次");
  });
  it("creates for A and B, preserves Mock tasks across logout, and lets any admin filter both", async () => {
    const create = vi.spyOn(MockAppCheckTaskSource.prototype, "createTask");
    const router = await mount();
    await login("1001");
    await draft();
    await submit();
    const a = (await create.mock.results[0]!.value).taskId as string;
    expect(document.getElementById("check-task-" + a)).not.toBeNull();
    await login("1002");
    expect(document.getElementById("check-task-" + a)).toBeNull();
    await draft("8613800000002");
    await submit();
    const b = (await create.mock.results[1]!.value).taskId as string;
    expect(document.getElementById("check-task-" + b)).not.toBeNull();
    expect(create.mock.calls.map((call) => call[0].userId)).toEqual(["1001", "1002"]);
    await login("1001");
    expect(document.getElementById("check-task-" + a)).not.toBeNull();
    expect(document.getElementById("check-task-" + b)).toBeNull();
    await login("1004");
    expect(router.currentRoute.value.path).toBe("/runtime/workers");
    expect(document.querySelector('a[href="/messages"]')).not.toBeNull();
    await router.push("/app-checks");
    await settle();
    expect(document.getElementById("check-task-" + a)).not.toBeNull();
    expect(document.getElementById("check-task-" + b)).not.toBeNull();
    const owner = document.querySelector<HTMLInputElement>(
      '[aria-label="归属 userId"]'
    )!;
    owner.value = "1002";
    owner.dispatchEvent(new Event("input", { bubbles: true }));
    button("按归属读取").click();
    await settle();
    expect(document.getElementById("check-task-" + a)).toBeNull();
    expect(document.getElementById("check-task-" + b)).not.toBeNull();
  });
  it("allows an additional operator to create using only its user declaration", async () => {
    const create = vi.spyOn(MockAppCheckTaskSource.prototype, "createTask");
    await mount();
    await login("1003");
    await draft();
    await submit();
    const id = (await create.mock.results[0]!.value).taskId as string;
    expect(create.mock.calls[0]![0]).toMatchObject({
      userId: "1003",
      role: "operator"
    });
    expect(document.getElementById("check-task-" + id)).not.toBeNull();
  });
  it("refuses a foreign detail without exposing content or loading auxiliaries", async () => {
    const auxiliary = vi.spyOn(MockAppCheckTaskSource.prototype, "loadImport");
    await mount("1001", "/app-checks/tasks/check-delivered");
    const drawer = document.querySelector(".checks-preview-drawer")!;
    expect(drawer.textContent).toContain("当前开发身份不能访问此任务");
    expect(drawer.querySelector("table")).toBeNull();
    expect(auxiliary).not.toHaveBeenCalled();
  });
  it("preserves a bounded empty observation without extra list reads", async () => {
    const list = vi
      .spyOn(MockAppCheckTaskSource.prototype, "listTasks")
      .mockResolvedValue({ tasks: [], truncated: true });
    await mount("1001");
    expect(document.body.textContent).toContain("当前载入范围内没有我的任务");
    expect(document.body.textContent).toContain("不代表全部历史任务");
    expect(list).toHaveBeenCalledTimes(1);
  });
  it("keeps late creation on its original caller and stops import after logout", async () => {
    const gate = deferred<void>();
    const original = MockAppCheckTaskSource.prototype.createTask;
    const create = vi
      .spyOn(MockAppCheckTaskSource.prototype, "createTask")
      .mockImplementationOnce(async function (
        this: MockAppCheckTaskSource,
        caller: TaskCaller,
        input: CreateCheckTask
      ) {
        await gate.promise;
        return original.call(this, caller, input);
      });
    const imported = vi.spyOn(MockAppCheckTaskSource.prototype, "importSeeds");
    await mount("1001");
    await draft();
    await submit();
    const request = structuredClone(create.mock.calls[0]);
    await login("1002");
    await draft("8613800000002");
    gate.resolve();
    await settle();
    expect(imported).not.toHaveBeenCalled();
    expect(numberInput().value).toBe("8613800000002");
    expect(create.mock.calls[0]).toEqual(request);
    await login("1001");
    expect(
      document.getElementById(
        "check-task-" + (await create.mock.results[0]!.value).taskId
      )
    ).not.toBeNull();
    expect(create).toHaveBeenCalledTimes(1);
  });
  it("clears unconfirmed local input on logout without replaying the old request", async () => {
    const create = vi
      .spyOn(MockAppCheckTaskSource.prototype, "createTask")
      .mockRejectedValueOnce(new AppCheckCreationUnconfirmed());
    await mount("1001");
    await draft();
    await submit();
    await login("1002");
    await login("1001");
    button("创建任务").click();
    await settle();
    button("粘贴种子").click();
    await settle();
    expect(numberInput().value).toBe("");
    expect(document.body.textContent).not.toContain("使用原请求核对创建");
    expect(create).toHaveBeenCalledTimes(1);
  });
  it("discards a late export download after logout", async () => {
    const gate = deferred<{ blob: Blob; fileName: string; count: number }>();
    const exported = vi
      .spyOn(MockAppCheckTaskSource.prototype, "exportTask")
      .mockImplementationOnce(() => gate.promise);
    await mount("1000", "/app-checks/tasks/check-unregistered");
    document
      .querySelector<HTMLButtonElement>(
        '.checks-preview-drawer [aria-label^="任务操作"]'
      )!
      .click();
    await settle();
    [...document.querySelectorAll<HTMLElement>('[role="menuitem"]')]
      .find((node) => node.textContent?.trim() === "导出结果")!
      .click();
    await settle();
    button("生成 CSV 文件").click();
    await settle();
    await login("1002");
    gate.resolve({ blob: new Blob(["user-a"]), fileName: "a.csv", count: 1 });
    await settle();
    expect(exported.mock.calls[0]![0]).toMatchObject({
      userId: "1000",
      role: "admin"
    });
    expect(document.querySelector("a[download]")).toBeNull();
    expect(document.body.textContent).not.toContain("a.csv");
  });
  it("discards a late detail after logout even when the same user logs in again", async () => {
    const gate = deferred<CheckDetail>();
    vi.spyOn(MockAppCheckTaskSource.prototype, "loadTask").mockImplementationOnce(
      () => gate.promise
    );
    const router = await mount("1001", "/app-checks/tasks/late-a");
    await login("1001");
    gate.resolve({
      task: {
        taskId: "late-a",
        userId: "1001",
        name: "Late user A",
        managed: false,
        workerGroupId: "app-a-sim",
        createdAtMillis: 1,
        state: "pre_review",
        checkId: "app-a-registration"
      },
      results: [],
      resultsTruncated: false
    });
    await settle();
    expect(router.currentRoute.value.fullPath).toBe("/app-checks");
    expect(document.body.textContent).not.toContain("Late user A");
  });
});
