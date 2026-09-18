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
import { AppCheckCreationUnconfirmed } from "../src/app-checks/task-source";
import type { CheckDetail } from "../src/app-checks/model";
import * as verification from "../src/app-checks/verify";

let app: App | undefined;
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
  document.body.replaceChildren();
  expect(fetcher).not.toHaveBeenCalled();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
  await nextTick();
}
async function mount(path = "/app-checks") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ["/app-checks", "/app-checks/tasks/:taskId"].map((path) => ({
      path,
      component: Workspace,
      props: () => ({ source, catalog: mockCatalog })
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
  return { host, router };
}
function button(text: string) {
  const node = [...document.querySelectorAll<HTMLButtonElement>("button")].find(
    (b) => b.textContent?.trim() === text
  );
  expect(node, text).toBeDefined();
  return node!;
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
async function submit() {
  document
    .querySelector("#app-check-create")!
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await settle();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("App Checks Task workspace", () => {
  it("retains loaded-list filter and focus when returning from detail", async () => {
    const { router } = await mount();
    input("搜索查询任务", "混合");
    await settle();
    button("混合结果与复算").click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks/tasks/check-mixed");
    expect(document.body.textContent).toContain("执行失败");
    expect(document.body.textContent).toContain("无注册答案");
    expect(document.body.textContent).toContain("内容解析错误");
    await router.push("/app-checks");
    await settle();
    expect(field("搜索查询任务").value).toBe("混合");
    expect(document.activeElement?.textContent).toBe("混合结果与复算");
  });
  it("caps preview at 100 and does not manufacture whole-task business statistics", async () => {
    await mount("/app-checks/tasks/check-preview");
    expect(document.querySelectorAll("tbody tr")).toHaveLength(100);
    expect(document.body.textContent).toContain("本次未完整展示");
    expect(document.body.textContent).toContain("120");
    expect(document.body.textContent).not.toMatch(/导出|加载更多|完成率|已注册数/);
  });
  it("preserves known data on errors and allows manual refresh", async () => {
    await mount("/app-checks/tasks/check-read-error");
    button("刷新").click();
    await settle();
    expect(document.body.textContent).toContain("Mock 读取失败");
    expect(document.body.textContent).toContain("已注册");
    button("刷新").click();
    await settle();
    expect(document.body.textContent).not.toContain("Mock 读取失败");
  });
  it("retains unknown business Tasks and has an honest empty state", async () => {
    const { router } = await mount();
    expect(document.body.textContent).toContain("check-missing");
    expect(document.body.textContent).toContain("Managed Task");
    await router.push("/app-checks/tasks/check-empty");
    await settle();
    expect(document.body.textContent).toContain("当前未观察到 Result");
  });
  it("keeps draft, preserves delay on examples, and creates once with a frozen name", async () => {
    const pending = deferred<{ taskId: string }>();
    const create = vi.spyOn(source, "createTask").mockReturnValue(pending.promise);
    const { router } = await mount();
    button("创建查询任务").click();
    await settle();
    input("号码列表", "+8613800000001");
    input(
      "模拟 JSON",
      '{"ranges":{"registered":[0,500],"unregistered":[500,900],"failed":[900,1000]},"delayMs":[12,34]}'
    );
    await settle();
    button("全未注册").click();
    await settle();
    expect(JSON.parse(field("模拟 JSON").value).delayMs).toEqual([12, 34]);
    button("关闭并保留草稿").click();
    await settle();
    button("创建查询任务").click();
    await settle();
    expect(field("号码列表").value).toBe("+8613800000001");
    await submit();
    await submit();
    expect(create).toHaveBeenCalledTimes(1);
    const sent = create.mock.calls[0][0];
    expect(sent.name).toMatch(/^check-app-a-CN-1-\d{8}-\d{6}$/);
    expect(sent.numbers).toEqual(["+8613800000001"]);
    expect(sent.simulation.ranges.unregistered).toEqual([0, 1000]);
    pending.resolve({ taskId: "check-unregistered" });
    await settle();
    expect(router.currentRoute.value.path).toBe("/app-checks/tasks/check-unregistered");
    await router.push("/app-checks");
    await settle();
    button("创建查询任务").click();
    await settle();
    expect(field("号码列表").value).toBe("");
  });
  it("rejects invalid input before sending and retains failed file-import draft", async () => {
    const create = vi.spyOn(source, "createTask");
    await mount();
    button("创建查询任务").click();
    await settle();
    input("号码列表", "+12025550123\n+12025550123");
    await settle();
    await submit();
    expect(create).not.toHaveBeenCalled();
    input("号码列表", "+8613800000001");
    input("模拟 JSON", '{"rate":1}');
    await settle();
    await submit();
    expect(create).not.toHaveBeenCalled();
    const file = document.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(file, "files", {
      configurable: true,
      value: [{ size: 1024 * 1024 + 1 }]
    });
    file.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();
    expect(field("号码列表").value).toBe("+8613800000001");
    expect(document.body.textContent).toContain("不能超过 1 MiB");
  });
  it("does not unlock an uncertain submission on close/reopen", async () => {
    const create = vi
      .spyOn(source, "createTask")
      .mockRejectedValue(new AppCheckCreationUnconfirmed("known"));
    await mount();
    button("创建查询任务").click();
    await settle();
    input("号码列表", "+8613800000001");
    await settle();
    await submit();
    expect(document.querySelector('a[href="/app-checks/tasks/known"]')).not.toBeNull();
    button("关闭并保留草稿").click();
    await settle();
    button("创建查询任务").click();
    await settle();
    expect(button("创建并自动批准").disabled).toBe(true);
    await submit();
    expect(create).toHaveBeenCalledTimes(1);
  });
  it("lets a rejected request be corrected using a new requestId", async () => {
    const create = vi
      .spyOn(source, "createTask")
      .mockRejectedValueOnce(new Error("capacity"));
    await mount();
    button("创建查询任务").click();
    await settle();
    input("号码列表", "+8613800000001");
    await settle();
    await submit();
    const first = create.mock.calls[0][0];
    expect(field("号码列表").value).toBe(first.numbers[0]);
    await submit();
    expect(create.mock.calls[1][0].requestId).not.toBe(first.requestId);
  });
  it("drops stale details and verification after task navigation", async () => {
    const delayed = deferred<CheckDetail>();
    const read = vi.spyOn(source, "loadTask").mockReturnValueOnce(delayed.promise);
    const { router } = await mount("/app-checks/tasks/check-mixed");
    await router.push("/app-checks/tasks/check-unregistered");
    await settle();
    const old = await new MockAppCheckTaskSource().loadTask("check-mixed");
    delayed.resolve(old);
    await settle();
    expect(document.querySelector("h1")?.textContent).toBe("未注册也是执行成功");
    expect(read).toHaveBeenCalledTimes(2);
    const check = deferred<Map<string, verification.Verification>>();
    vi.spyOn(verification, "verifyPreview").mockReturnValue(check.promise);
    button("核对当前预览").click();
    await settle();
    await router.push("/app-checks/tasks/check-empty");
    await settle();
    check.resolve(new Map([["r-1", { status: "mismatch", reason: "stale-result" }]]));
    await settle();
    expect(document.body.textContent).not.toContain("stale-result");
  });
  it("invalidates a verification when refresh returns a new snapshot", async () => {
    const check = deferred<Map<string, verification.Verification>>();
    vi.spyOn(verification, "verifyPreview").mockReturnValue(check.promise);
    await mount("/app-checks/tasks/check-mixed");
    button("核对当前预览").click();
    await settle();
    button("刷新").click();
    await settle();
    check.resolve(new Map([["r-1", { status: "mismatch", reason: "old-snapshot" }]]));
    await settle();
    expect(document.body.textContent).not.toContain("old-snapshot");
  });
  it("explains disabled verification without Web Crypto", async () => {
    vi.stubGlobal("crypto", undefined);
    const create = vi.spyOn(source, "createTask");
    const { router } = await mount("/app-checks/tasks/check-mixed");
    expect(button("核对当前预览").disabled).toBe(true);
    expect(document.body.textContent).toContain("不支持 Web Crypto");
    await router.push("/app-checks");
    await settle();
    button("创建查询任务").click();
    await settle();
    input("号码列表", "+8613800000001");
    await settle();
    await submit();
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0][0].requestId).toMatch(/^app-check-/);
  });
});
