// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, h, ref } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import ElementPlus from "element-plus";
import ConsoleLayout from "@/layouts/ConsoleLayout.vue";
import { consoleIdentity } from "@/console-identity";
import { taskSessionKey, type TaskSession } from "@/task-workbench/session";
import { devKey } from "./identity-fixture";

const cleanups: (() => void)[] = [];

beforeEach(async () => {
  vi.stubEnv("VITE_RUNTIME_DATA_SOURCE", "api");
  await consoleIdentity.login(devKey("1000"));
});

afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
  consoleIdentity.logout();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  document.body.innerHTML = "";
});

async function mountLayout(projectIds: string[], initialPath = "/runtime/tasks") {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { render: () => h("div", "home") } },
      {
        path: "/runtime/tasks",
        meta: { section: "Runtime", title: "Tasks" },
        component: { render: () => h("div", "tasks") }
      },
      {
        path: "/sms",
        meta: { section: "Scenarios", title: "SMS" },
        component: { render: () => h("div", "sms") }
      },
      {
        path: "/messages",
        meta: { section: "Scenarios", title: "Messages" },
        component: { render: () => h("div", "messages") }
      },
      {
        path: "/app-checks",
        meta: { section: "Scenarios", title: "App Checks" },
        component: { render: () => h("div", "app-checks") }
      }
    ]
  });
  await router.push(initialPath);
  await router.isReady();
  const directory = {
    value: projectIds.map((projectId) => ({
      projectId,
      workerGroupIds: [],
      workerGroupSource: "declared" as const
    })),
    loading: false,
    stale: false,
    readAt: Date.now()
  };
  const fakeSession = {
    directory,
    ensureDirectory: vi.fn(async () => {})
  } as unknown as TaskSession;
  const host = document.createElement("div");
  document.body.appendChild(host);
  const app = createApp({
    setup() {
      const dummy = ref(0);
      return () => (dummy.value >= 0 ? h(ConsoleLayout) : null);
    }
  });
  app.use(pinia);
  app.use(router);
  app.use(ElementPlus);
  app.provide(taskSessionKey, fakeSession);
  app.mount(host);
  cleanups.push(() => {
    app.unmount();
    host.remove();
  });
  return { host, fakeSession };
}

describe("ConsoleLayout scenario discovery", () => {
  it("skips probing /api/v1/sms/catalog when the Project directory omits sms", async () => {
    const requested: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        requested.push(url);
        if (url.includes("/messages/catalog")) {
          return new Response(
            JSON.stringify({
              projectId: "messages",
              version: "0.3.0-preview",
              countries: ["CN"],
              maxRecipientsPerImport: 100000,
              applications: [{ id: "demo", name: "Demo", templates: [] }]
            })
          );
        }
        return new Response("not found", { status: 404 });
      })
    );
    await mountLayout(["app-checks", "default", "messages", "performance-consumer"]);
    await vi.waitFor(() =>
      expect(requested.some((u) => u.includes("/messages/catalog"))).toBe(true)
    );
    expect(requested.some((u) => u.includes("/sms/catalog"))).toBe(false);
  });

  it("probes /api/v1/sms/catalog when the Project directory includes sms", async () => {
    const requested: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        requested.push(url);
        if (url.includes("/sms/catalog")) {
          return new Response(
            JSON.stringify({
              runId: "r1",
              version: "0.1.0-preview",
              applications: [
                { id: "A", name: "A", templates: [{ id: "A-1", priority: 100 }] }
              ]
            })
          );
        }
        return new Response("not found", { status: 404 });
      })
    );
    await mountLayout(["default", "sms"]);
    await vi.waitFor(() =>
      expect(requested.some((u) => u.includes("/sms/catalog"))).toBe(true)
    );
  });
});
