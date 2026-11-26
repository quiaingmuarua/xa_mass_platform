import { afterEach, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import {
  ElAlert,
  ElButton,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag
} from "element-plus";
import axios, { type AxiosInstance } from "axios";
import WorkerRuntimeView from "@/views/WorkerRuntimeView.vue";
import { consoleIdentity } from "@/console-identity";
import { consoleMockKey, createConsoleMock } from "@/console-mock";
import { runtimeViewerConfigKey } from "@/runtime-context";
import { MockUserDirectorySource } from "@/user-directory-source";
import { MockWorkerGroupManagementSource } from "@/runtime-viewer/worker-group-management";
import { developmentAccessKey, developmentUsers } from "@/development-users";
import { withAccessKey } from "@/task-caller";

const observation = vi.hoisted(() => ({ mounted: vi.fn() }));
vi.mock("@/views/WorkerObservationView.vue", () => ({
  default: {
    setup() {
      observation.mounted();
      return () =>
        h("div", { "data-testid": "mock-observation" }, "worker-observation");
    }
  }
}));

let app: App | undefined;

async function flush() {
  await Promise.resolve();
  await nextTick();
  await nextTick();
  await nextTick();
}

afterEach(() => {
  app?.unmount();
  app = undefined;
  consoleIdentity.logout();
  document.body.replaceChildren();
  vi.restoreAllMocks();
  observation.mounted.mockClear();
});

async function mountWorkspace(url: string, mode: "mock" | "api" = "mock") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/runtime/workers", component: WorkerRuntimeView }]
  });
  await router.push(url);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  const directory = new MockUserDirectorySource(developmentUsers);
  const mockBundle = createConsoleMock(directory);
  app = createApp({ render: () => h(RouterView) })
    .use(router)
    .provide(runtimeViewerConfigKey, { mode, apiBaseUrl: "/api" })
    .provide(consoleMockKey, mockBundle);
  [
    ElAlert,
    ElButton,
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
  await flush();
  return { router, host, mockBundle };
}

it("renders 3-tab Worker workspace, displays Project topology on bindings tab, and enforces admin on groups tab", async () => {
  const { router, host } = await mountWorkspace(
    "/runtime/workers?view=bindings",
    "mock"
  );
  expect(host.textContent).toContain("Worker 观察");
  expect(host.textContent).toContain("Group 管理");
  expect(host.textContent).toContain("资源绑定");
  expect(host.textContent).toContain("Project 资源拓扑（只读）");
  expect(host.textContent).toContain("Scenario 托管 (message-campaigns)");
  expect(host.textContent).toContain("pool-validation");
  expect(observation.mounted).not.toHaveBeenCalled();

  await router.push("/runtime/workers?view=groups");
  await flush();
  expect(host.textContent).toContain("当前会话没有管理员权限");
});

it("lists groups, protects startup_seeded from editing and startup-bound from deletion, and supports register/edit/safe-delete in mock mode", async () => {
  await consoleIdentity.login(developmentAccessKey("1000"));

  const { host, mockBundle } = await mountWorkspace(
    "/runtime/workers?view=groups",
    "mock"
  );
  expect(host.textContent).toContain("android-demo-workers");
  expect(host.textContent).toContain("scenario-phone-number-workers");
  expect(host.textContent).toContain("ops-sandbox-workers");
  expect(host.textContent).toContain("启动托管");
  expect(host.textContent).toContain("启动绑定");
  expect(host.textContent).toContain("动态注册");

  const caller = consoleIdentity.requireCaller();
  // Startup seeded group cannot be edited or deleted.
  await expect(
    mockBundle.workerGroupManagement.update(caller, "android-demo-workers", {}, [])
  ).rejects.toThrow("不允许运行时编辑");
  await expect(
    mockBundle.workerGroupManagement.deleteGroup(caller, "android-demo-workers")
  ).rejects.toThrow("受启动配置保护");

  // Register a dynamic group and edit its metadata.
  const registered = await mockBundle.workerGroupManagement.register(
    caller,
    "ops-temp-group",
    { team: "infra" },
    ["ev.one"]
  );
  expect(registered.status).toBe("registered");
  const updated = await mockBundle.workerGroupManagement.update(
    caller,
    "ops-temp-group",
    { team: "platform" },
    ["ev.one", "ev.two"]
  );
  expect(updated.status).toBe("updated");

  // Delete the dynamic group cleanly.
  const deleted = await mockBundle.workerGroupManagement.deleteGroup(
    caller,
    "ops-sandbox-workers"
  );
  expect(deleted.status).toBe("deleted");
  expect(deleted.removedWorkers).toBe(1);
});

it("calls real REST management endpoints and supports pagination merge in api mode", async () => {
  const source = new MockWorkerGroupManagementSource();
  const operatorCaller = withAccessKey(
    { userId: "op-1", role: "operator" as const },
    "xak_test_operator_1234"
  );
  await expect(source.list(operatorCaller)).rejects.toThrow("仅管理员");

  const get = vi.fn(async (_path: string, opts?: { params?: { cursor?: string } }) => ({
    data:
      opts?.params?.cursor === "10"
        ? {
            nextCursor: "0",
            unreadableCount: 0,
            readAt: "2026-10-20T00:00:00Z",
            groups: [
              {
                workerGroupId: "z-dynamic-group",
                attributes: {},
                eventCodes: ["ev.z"],
                source: "dynamic",
                editable: true,
                deletableByConfig: true,
                protectionReasons: [],
                declaredProjects: [],
                workerCounts: {
                  total: 0,
                  activeHot: 0,
                  pausedActive: 0,
                  recovery: 0,
                  cold: 0,
                  pausedOffline: 0,
                  activeTotal: 0
                }
              }
            ]
          }
        : {
            nextCursor: "10",
            unreadableCount: 1,
            readAt: "2026-10-20T00:00:00Z",
            groups: [
              {
                workerGroupId: "a-seeded-group",
                attributes: { tier: "core" },
                eventCodes: ["ev.a"],
                source: "startup_seeded",
                editable: false,
                deletableByConfig: false,
                protectionReasons: ["host_seed"],
                declaredProjects: ["default"],
                workerCounts: {
                  total: 3,
                  activeHot: 2,
                  pausedActive: 0,
                  recovery: 0,
                  cold: 1,
                  pausedOffline: 0,
                  activeTotal: 2
                }
              }
            ]
          }
  }));
  const del = vi.fn(async () => ({
    data: { workerGroupId: "z-dynamic-group", status: "deleted", removedWorkers: 0 }
  }));
  vi.spyOn(axios, "create").mockReturnValue({
    get,
    delete: del
  } as unknown as AxiosInstance);

  await consoleIdentity.login(developmentAccessKey("1000"));

  const { host } = await mountWorkspace("/runtime/workers?view=groups", "api");
  expect(host.textContent).toContain("a-seeded-group");
  expect(host.textContent).toContain("1 条无法解码");

  const nextBtn = [...host.querySelectorAll("button")].find((btn) =>
    btn.textContent?.includes("加载下一页")
  );
  expect(nextBtn).toBeDefined();
  nextBtn!.click();
  await flush();
  expect(host.textContent).toContain("z-dynamic-group");
  expect(get).toHaveBeenCalledTimes(2);
});
