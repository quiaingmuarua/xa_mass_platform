import { MockUserDirectorySource } from "@/user-directory-source";
import { consoleIdentity } from "@/console-identity";
import { CALLER } from "./app-check-fixture";
import { webcrypto } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App } from "vue";
import { createRouter, createMemoryHistory, RouterView } from "vue-router";
import { ElButton, ElInput, ElSelect, ElOption } from "element-plus";
import AppCheckPage from "../src/app-checks/AppCheckPage.vue";
import { appCheckContextKey, createAppCheckContext } from "../src/app-checks/context";
import {
  savedTaskConfiguration,
  scenarioConfiguration
} from "../src/app-checks/configuration";
import { detailSchema, type CheckDetail } from "../src/app-checks/model";
import {
  MockAppCheckTaskSource,
  mockCatalog,
  mockSimulation
} from "../src/app-checks/mock-task-source";
import {
  AppCheckCreationUnconfirmed,
  AppCheckConfigurationUnconfirmed
} from "../src/app-checks/task-source";
import type { AppCheckConfigurationView } from "../src/app-checks/settings";
import { inspectImport } from "../src/app-checks/import-model";

let app: App | undefined;
let context: ReturnType<typeof createAppCheckContext>;
let source: MockAppCheckTaskSource;
const fetcher = vi.fn();
beforeEach(() => {
  consoleIdentity.login("1000");
  vi.useFakeTimers();
  vi.stubGlobal("crypto", webcrypto);
  vi.stubGlobal("fetch", fetcher.mockReset());
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  source = new MockAppCheckTaskSource(new MockUserDirectorySource());
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  consoleIdentity.logout();
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
  const session = context.sessionFor("1000");
  if (router.currentRoute.value.query.view === "configuration") {
    // Mock fingerprints use native Web Crypto; advancing fake timers does not
    // guarantee that the initial configuration read has completed.
    await vi.waitUntil(
      () => !!session.configuration.snapshot || !!session.configuration.error
    );
    await nextTick();
  }
  return { host, router, session };
}
function button(text: string, scope: ParentNode = document) {
  const node = [...scope.querySelectorAll<HTMLButtonElement>("button")].find(
    (node) => node.textContent?.trim() === text
  );
  expect(node, text).toBeDefined();
  return node!;
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
async function input(label: string, value: string) {
  const element = document.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  expect(element, label).not.toBeNull();
  element.value = value;
  element.dispatchEvent(new Event("input", { bubbles: true }));
  await settle();
}

describe("App Checks JSON configuration workspace", () => {
  async function editDocument(
    change: (value: AppCheckConfigurationView["saved"]["configuration"]) => void
  ) {
    button("编辑配置").click();
    await settle();
    const editor = document.querySelector<HTMLTextAreaElement>(
      '[aria-label="配置 JSON"]'
    )!;
    const value = JSON.parse(editor.value);
    change(value);
    await input("配置 JSON", JSON.stringify(value, null, 2));
  }
  it("saves a different contract/application only for the next startup and preserves task snapshots", async () => {
    const old = await source.createTask(CALLER, {
      requestId: "old",
      checkId: "app-a-registration",
      country: "CN"
    });
    const { session, host } = await mount();
    await editDocument((value) => {
      value.checks["app-a-registration"].label = "Alpha";
      value.common.typeDefaults["simulated-registration-v1"] = {
        ranges: { registered: [0, 0], unregistered: [0, 1000], failed: [1000, 1000] },
        delayMs: [0, 0]
      };
      value.checks["app-c"] = {
        label: "App C",
        workerGroupId: "app-c-sim",
        checkType: "registration-v1",
        execution: {
          eventCode: "extension.worker.custom.registration",
          refill: [{ poolName: "any", target: {}, count: 100 }],
          workerSelector: { executorName: "worker.any", input: {} }
        }
      };
    });
    button("保存配置").click();
    await vi.waitUntil(() => !session.configuration.saving);
    await settle();
    expect(session.configuration.snapshot?.saved.fingerprint).toMatch(/^[0-9a-f]{64}$/);
    expect(session.configuration.snapshot?.saved.fingerprint).not.toBe(
      session.configuration.snapshot?.effective.fingerprint
    );
    expect(host.textContent).toContain("待手动重启生效");
    expect((await source.catalog()).checks).toHaveLength(5);
    await expect(
      source.createTask(CALLER, {
        requestId: "pending",
        checkId: "app-c",
        country: "CN"
      })
    ).rejects.toThrow();
    button("模拟重启生效").click();
    await vi.waitUntil(() => !session.configuration.snapshot?.restartRequired);
    await settle();
    expect(host.textContent).not.toContain("配置已保存，待手动重启生效");
    expect(host.textContent).toContain("已模拟重启");
    const created = await source.createTask(CALLER, {
      requestId: "new",
      checkId: "app-c",
      country: "CN"
    });
    expect((await source.loadTask(CALLER, created.taskId)).task).toMatchObject({
      checkType: "registration-v1",
      contractParameters: {},
      workerGroupId: "app-c-sim",
      eventCode: "extension.worker.custom.registration"
    });
    expect((await source.loadTask(CALLER, old.taskId)).task.contractParameters).toEqual(
      mockSimulation
    );
    expect(
      await source.createTask(CALLER, {
        requestId: "old",
        checkId: "app-a-registration",
        country: "CN"
      })
    ).toEqual(old);
    await source.importSeeds(CALLER, created.taskId, new Blob(["8613800000001"]));
    await source.approveTask(CALLER, created.taskId, 1);
    await source.completeTask(CALLER, created.taskId);
    const detail = await source.loadTask(CALLER, created.taskId);
    expect(detail.results[0]).toMatchObject({
      resultStatus: "succeeded",
      matched: false
    });
    expect(detail.results[0]).not.toHaveProperty("details.simulatedDelayMillis");
    expect((await source.exportTask(CALLER, created.taskId, "all")).count).toBe(1);
  });
  it("checks only JSON syntax/root locally and preserves semantic rejection text", async () => {
    const save = vi.spyOn(source, "saveConfiguration");
    const { session } = await mount();
    button("编辑配置").click();
    await settle();
    await input("配置 JSON", "{bad");
    button("保存配置").click();
    await vi.waitUntil(() => !session.configuration.saving);
    await settle();
    expect(save).not.toHaveBeenCalled();
    expect(session.configuration.draft).toBe("{bad");
    await input("配置 JSON", "[]");
    button("保存配置").click();
    await vi.waitUntil(() => !session.configuration.saving);
    await settle();
    expect(session.configuration.error).toContain("根节点");
    await input("配置 JSON", '{"unknown":true}');
    button("保存配置").click();
    await vi.waitUntil(() => !session.configuration.saving);
    await settle();
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining(CALLER),
      session.configuration.baseFingerprint,
      {
        unknown: true
      }
    );
    expect(session.configuration.draft).toBe('{"unknown":true}');
  });
  it("preserves raw unsaved text through reads/navigation and rejects concurrent overwrite", async () => {
    const { router, session, host } = await mount();
    await editDocument((value) => {
      value.checks["app-a-registration"].label = "Local draft";
    });
    const text = session.configuration.draft;
    const saved = (await source.loadConfiguration(CALLER)).saved;
    const concurrent = saved.configuration;
    concurrent.checks["app-b-registration"].label = "Concurrent";
    await source.saveConfiguration(CALLER, saved.fingerprint, concurrent);
    button("保存配置").click();
    await vi.waitUntil(() => !session.configuration.saving);
    await settle();
    expect(session.configuration.draft).toBe(text);
    expect(session.configuration.stale).toBe(true);
    button("重新读取").click();
    await settle();
    await router.push("/app-checks");
    await settle();
    await router.push("/app-checks?view=configuration");
    await settle();
    expect(session.configuration.draft).toBe(text);
    expect(session.configuration.baseFingerprint).toBe(saved.fingerprint);
    expect(button("保存配置").disabled).toBe(true);
    expect(host.textContent).toContain("当前编辑未覆盖");
    button("取消编辑").click();
    await settle();
    expect(session.configuration.draft).toBeUndefined();
    expect(
      session.configuration.snapshot?.saved.configuration.checks["app-b-registration"]
        .label
    ).toBe("Concurrent");
  });
  it("reconciles an unconfirmed save by reading, without resending", async () => {
    const actual = source.saveConfiguration.bind(source);
    const save = vi
      .spyOn(source, "saveConfiguration")
      .mockImplementationOnce(async (caller, fingerprint, value) => {
        await actual(caller, fingerprint, value);
        throw new AppCheckConfigurationUnconfirmed();
      });
    const { session } = await mount();
    await editDocument((value) => {
      value.checks["app-a-registration"].label = "Accepted";
    });
    const text = session.configuration.draft;
    button("保存配置").click();
    await vi.waitUntil(() => !session.configuration.saving);
    await settle();
    expect(session.configuration.uncertain).toBe(true);
    expect(session.configuration.submittedText).toBe(text);
    expect(button("保存配置").disabled).toBe(true);
    button("重新读取").click();
    await vi.waitUntil(() => !session.configuration.uncertain);
    await settle();
    expect(save).toHaveBeenCalledTimes(1);
    expect(session.configuration.uncertain).toBe(false);
    expect(session.configuration.draft).toBeUndefined();
  });
  it("locks a pending save across remount and ignores its late response", async () => {
    const pending = deferred<AppCheckConfigurationView>();
    vi.spyOn(source, "saveConfiguration").mockReturnValueOnce(pending.promise);
    const { router, session } = await mount();
    await editDocument((value) => {
      value.checks["app-a-registration"].label = "Pending";
    });
    const text = session.configuration.draft;
    button("保存配置").click();
    await settle();
    await router.push("/app-checks");
    await settle();
    await router.push("/app-checks?view=configuration");
    await settle();
    expect(button("取消编辑").disabled).toBe(true);
    pending.resolve(await source.loadConfiguration(CALLER));
    await settle();
    expect(session.configuration.uncertain).toBe(true);
    expect(session.configuration.draft).toBe(text);
  });
  it("direct configuration entry does not read tasks and a failed refresh retains the snapshot/time", async () => {
    const list = vi.spyOn(source, "listTasks");
    const { session, host } = await mount();
    expect(list).not.toHaveBeenCalled();
    expect(host.querySelectorAll("textarea")).toHaveLength(1);
    expect(host.querySelector('[aria-label="配置应用"]')).toBeNull();
    const before = session.configuration.readAt;
    const snapshot = session.configuration.snapshot!;
    const refresh = deferred<AppCheckConfigurationView>();
    const load = vi
      .spyOn(source, "loadConfiguration")
      .mockRejectedValueOnce(new Error("offline"))
      .mockReturnValueOnce(refresh.promise);
    button("重新读取").click();
    await settle();
    expect(session.configuration.readAt).toBe(before);
    expect(session.configuration.snapshot).toBe(snapshot);
    expect(host.textContent).toContain("已保留上次快照");
    expect(context.state.value.status).toBe("enabled");
    button("重新读取").click();
    await settle();
    expect(load).toHaveBeenCalledTimes(2);
    expect(session.configuration.readAt).toBe(before);
    expect(session.configuration.stale).toBe(true);
    refresh.resolve(snapshot);
    await settle();
    expect(session.configuration.readAt).toBeGreaterThan(before);
    expect(session.configuration.stale).toBe(false);
    expect(session.configuration.error).toBe("");
  });
  it("keeps task-list filters/scroll and creation identity across navigation and Catalog changes", async () => {
    const list = vi.spyOn(source, "listTasks");
    const { router, host, session } = await mount("/app-checks");
    const table = host.querySelector(".checks-task-table");
    session.filters.check = "app-b-registration";
    await settle();
    const viewport = host.querySelector<HTMLElement>(".el-scrollbar__wrap")!;
    viewport.scrollTop = 125;
    viewport.dispatchEvent(new Event("scroll"));
    await settle();
    await router.push("/app-checks?view=configuration");
    await settle();
    await router.push("/app-checks");
    await settle();
    expect(host.querySelector(".checks-task-table")).toBe(table);
    expect(session.listTableScrollTop).toBe(125);
    expect(session.filters.check).toBe("app-b-registration");
    expect(list).toHaveBeenCalledTimes(1);
    const create = vi
      .spyOn(source, "createTask")
      .mockRejectedValueOnce(new AppCheckCreationUnconfirmed());
    const imports = vi.spyOn(source, "importSeeds");
    await router.push("/app-checks?view=create");
    await settle();
    session.draft.text = "8613800000001";
    session.draft.report = inspectImport(session.draft.text, {
      seedType: "phonenumber" as const,
      format: "txt",
      country: "CN",
      header: false,
      column: 0,
      limit: 100000
    });
    await settle();
    document
      .querySelector("#app-check-create")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    const frozen = create.mock.calls[0][1];
    expect(Object.keys(frozen).sort()).toEqual(["checkId", "country", "requestId"]);
    await router.push("/app-checks?view=configuration");
    await settle();
    await context.load(true);
    router.back();
    await settle();
    button("使用原请求核对创建").click();
    await settle();
    expect(create.mock.calls[1][1]).toEqual(frozen);
    expect(imports).not.toHaveBeenCalled();
  });
  it("shows saved task contract and returns to the drawer, without late responses stealing focus", async () => {
    const { router, host } = await mount("/app-checks/tasks/check-unregistered");
    const section = document.querySelector<HTMLDetailsElement>(
      ".checks-task-configuration"
    )!;
    expect(section.open).toBe(false);
    expect(section.textContent).toContain("simulated-registration-v1");
    expect(section.textContent).toContain('"delayMs"');
    button("查看当前检查配置").click();
    await settle();
    expect(document.activeElement).toBe(
      host.querySelector(".checks-configuration-page h1")
    );
    router.back();
    await settle();
    expect(router.currentRoute.value.params.taskId).toBe("check-unregistered");
    expect(document.activeElement?.closest(".checks-preview-drawer")).not.toBeNull();
    const pending = deferred<CheckDetail>();
    vi.spyOn(source, "loadTask").mockReturnValueOnce(pending.promise);
    await router.push("/app-checks/tasks/check-live");
    await settle();
    await router.push("/app-checks?view=configuration");
    await settle();
    pending.resolve(
      await new MockAppCheckTaskSource(new MockUserDirectorySource()).loadTask(
        CALLER,
        "check-live"
      )
    );
    await settle();
    expect(document.activeElement).toBe(
      host.querySelector(".checks-configuration-page h1")
    );
  });
  it("does not invent unavailable API policy or block task reads on configuration failure", async () => {
    expect(scenarioConfiguration(mockCatalog, "api").source).toBe("catalog");
    const detail = detailSchema.parse(
      await source.loadTask(CALLER, "check-unregistered")
    );
    expect(savedTaskConfiguration(detail.task, "api").policy.unavailable).toContain(
      "当前接口未提供"
    );

    vi.spyOn(source, "loadConfiguration").mockRejectedValueOnce(
      new Error("configuration unavailable")
    );
    const { router } = await mount("/app-checks?view=configuration", "api");
    await router.push("/app-checks/tasks/check-mixed");
    await settle();
    expect(document.querySelectorAll(".checks-results-table tbody tr")).toHaveLength(4);
    expect(
      document.querySelector(".checks-task-configuration")?.textContent
    ).not.toContain("365 天");
  });
});
