import { webcrypto } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App, type Component } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { ElButton, ElDrawer, ElInput, ElOption, ElSelect } from "element-plus";
import { consoleIdentity } from "@/console-identity";
import { callerHeaders } from "@/task-caller";
import Workspace from "@/app-checks/AppCheckWorkspace.vue";
import Configuration from "@/app-checks/AppCheckConfiguration.vue";
import Recovery from "@/app-checks/SubmissionRecovery.vue";
import { ApiAppCheckTaskSource } from "@/app-checks/task-source";
import { mockCatalog, mockSettings } from "@/app-checks/mock-task-source";
import { createWorkbenchSession, freshDraft } from "@/app-checks/workbench";
import { devKey } from "./identity-fixture";

const taskId = "api-caller-check";
const taskPath = `/api/v1/projects/app-checks/tasks/${taskId}`;
const resultPath = `/api/v1/app-checks/tasks/${taskId}/results`;
const configurationPath = "/api/v1/app-checks/configuration";
const entry = {
  taskId,
  createdAtMillis: 1,
  retention: null,
  scoreBand: "pre_review",
  task: {
    taskId,
    projectId: "app-checks",
    workerGroupId: "demo-sim",
    idleDisposition: "CLOSE_WHEN_IDLE",
    name: "API caller check",
    config: {},
    refill: [{ poolName: "any", target: {}, count: 100 }],
    metadata: {
      userId: "1000",
      "server.inputOwner": "app-checks",
      checkId: "md5-prefix",
      checkType: "md5-prefix-demo-v1",
      checkLabel: "MD5 prefix",
      seedType: "text",
      unitPrice: "1",
      seedsPerItem: "1",
      eventCode: "extension.worker.string.md5",
      workerSelector: JSON.stringify({ executorName: "worker.any", input: {} }),
      contractParameters: "{}"
    }
  }
};
const configuration = {
  saved: { fingerprint: "a".repeat(64), configuration: mockSettings },
  effective: { fingerprint: "a".repeat(64), configuration: mockSettings },
  restartRequired: false,
  checkTypes: mockCatalog.checkTypes
};
const json = (value: unknown) => new Response(JSON.stringify(value));
const fetcher =
  vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
let app: App | undefined;
let source: ApiAppCheckTaskSource;
let pendingDetail: Promise<Response> | undefined;

beforeEach(async () => {
  await consoleIdentity.login(devKey("1000"));
  vi.useFakeTimers();
  vi.stubGlobal("crypto", webcrypto);
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  source = new ApiAppCheckTaskSource();
  pendingDetail = undefined;
  fetcher.mockReset().mockImplementation(async (input) => {
    const path = String(input);
    if (path === "/api/v1/projects/app-checks/tasks?limit=100")
      return json({ projectId: "app-checks", tasks: [entry], truncated: false });
    if (path === taskPath) return pendingDetail ?? json(entry);
    if (path === "/api/v1/projects/app-checks/tasks:stats")
      return json({ [taskId]: { total: 1, countsByTag: { "1": 1 } } });
    if (path === resultPath) return json({ taskId, results: [], truncated: false });
    if (path === configurationPath) return json(configuration);
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal("fetch", fetcher);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  consoleIdentity.logout();
  document.body.replaceChildren();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle() {
  await vi.advanceTimersByTimeAsync(250);
  await nextTick();
}

async function mount(
  component: Component,
  props: Record<string, unknown>,
  path = "/app-checks"
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ["/app-checks", "/app-checks/tasks/:taskId"].map((path) => ({
      path,
      component,
      props: () => props
    }))
  });
  await router.push(path);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp({ render: () => h(RouterView) }).use(router);
  [ElButton, ElDrawer, ElInput, ElOption, ElSelect].forEach((component) =>
    app!.use(component)
  );
  app.mount(host);
  await settle();
  return router;
}

function button(text: string) {
  const found = [...document.querySelectorAll<HTMLButtonElement>("button")].find(
    (node) => node.textContent?.trim() === text
  );
  expect(found, text).toBeDefined();
  return found!;
}

function assertAuthorization(userId = "1000") {
  expect(fetcher).toHaveBeenCalled();
  for (const [, init] of fetcher.mock.calls) {
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      `Bearer ${devKey(userId)}`
    );
    expect(String(init?.body ?? "")).not.toContain("xak_");
  }
}

describe("Batch Checks component API callers", () => {
  it("authenticates the actual list and detail HTTP requests from workspace navigation", async () => {
    const session = createWorkbenchSession();
    await mount(Workspace, { source, catalog: mockCatalog, session });
    expect(document.body.textContent).toContain("API caller check");
    button("API caller check").click();
    await settle();
    expect(fetcher.mock.calls.map(([path]) => String(path))).toContain(taskPath);
    expect(fetcher.mock.calls.map(([path]) => String(path))).toContain(resultPath);
    expect(document.querySelector(".checks-preview-drawer")?.textContent).toContain(
      "API caller check"
    );
    expect(document.body.textContent).not.toContain("没有访问密钥");
    expect(JSON.stringify(session)).not.toContain("xak_");
    assertAuthorization();
  });

  it("authenticates configuration reads and the explicit save without serializing the key", async () => {
    const session = createWorkbenchSession().configuration;
    await mount(Configuration, {
      source,
      catalog: mockCatalog,
      session,
      read: { readAt: 0, busy: false, error: "" },
      returnToCreate: false
    });
    expect(session.snapshot?.saved.fingerprint).toBe(configuration.saved.fingerprint);
    button("编辑配置").click();
    await settle();
    button("保存配置").click();
    await settle();
    const saved = fetcher.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(saved?.[0]).toBe(configurationPath);
    expect(JSON.parse(String(saved?.[1]?.body))).toEqual({
      expectedFingerprint: configuration.saved.fingerprint,
      configuration: mockSettings
    });
    expect(session.notice).toContain("配置已保存");
    expect(JSON.stringify(session)).not.toContain("xak_");
    assertAuthorization();
  });

  it("authenticates explicit recovery of a known Task without recreating it", async () => {
    const draft = { ...freshDraft(mockCatalog), knownTaskId: taskId, uncertain: true };
    await mount(Recovery, { source, draft });
    expect(fetcher).not.toHaveBeenCalled();
    button("读取关联任务").click();
    await settle();
    expect(fetcher.mock.calls.map(([path]) => String(path))).toEqual([
      taskPath,
      "/api/v1/projects/app-checks/tasks:stats",
      resultPath
    ]);
    expect(
      document.querySelector(".checks-recovery-observation")?.textContent
    ).toContain("任务已记录 1 个执行项");
    expect(draft.uncertain).toBe(true);
    assertAuthorization();
  });

  it("keeps an admitted detail read on its original login and discards its late view", async () => {
    let resolveDetail!: (response: Response) => void;
    pendingDetail = new Promise<Response>((resolve) => {
      resolveDetail = resolve;
    });
    const loading = vi.spyOn(source, "loadTask");
    await mount(
      Workspace,
      { source, catalog: mockCatalog, session: createWorkbenchSession() },
      `/app-checks/tasks/${taskId}`
    );
    expect(loading).toHaveBeenCalled();
    const captured = loading.mock.calls[0][0];
    expect(callerHeaders(captured)).toEqual({
      Authorization: `Bearer ${devKey("1000")}`
    });
    expect(Object.isFrozen(captured)).toBe(true);
    expect(Object.keys(captured)).not.toContain("accessKey");
    expect(JSON.stringify(captured)).not.toContain("xak_");
    await consoleIdentity.login(devKey("1001"));
    resolveDetail(json(entry));
    await settle();
    expect(fetcher.mock.calls.map(([path]) => String(path))).toContain(resultPath);
    expect(document.querySelector(".checks-preview-drawer")?.textContent).not.toContain(
      "API caller check"
    );
    assertAuthorization("1000");
  });
});
