import { afterEach, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type App } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { ElButton, ElInput, ElOption, ElSelect, ElTabPane, ElTabs } from "element-plus";
import axios, { type AxiosInstance } from "axios";
import WorkerRuntimeView from "@/views/WorkerRuntimeView.vue";
import { runtimeViewerConfigKey } from "@/runtime-context";
import { mockWorkerGroupBindings } from "@/runtime-viewer/worker-group-bindings";
const observation = vi.hoisted(() => ({ mounted: vi.fn() }));
vi.mock("@/views/WorkerObservationView.vue", () => ({
  default: {
    setup() {
      observation.mounted();
      return () => null;
    }
  }
}));
let app: App | undefined;
afterEach(() => {
  app?.unmount();
  app = undefined;
  document.body.replaceChildren();
  vi.restoreAllMocks();
  observation.mounted.mockClear();
});
async function mount(url: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/runtime/workers", component: WorkerRuntimeView }]
  });
  await router.push(url);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp({ render: () => h(RouterView) })
    .use(router)
    .provide(runtimeViewerConfigKey, { mode: "api", apiBaseUrl: "/api" });
  [ElButton, ElInput, ElOption, ElSelect, ElTabPane, ElTabs].forEach((component) =>
    app!.use(component)
  );
  app.mount(host);
  await nextTick();
  await nextTick();
  await nextTick();
  return { router, host };
}
it("direct binding reads an out-of-directory Group without mounting Worker sampling and preserves text across tabs", async () => {
  const get = vi.fn(async (path: string) => ({
    data: path.endsWith("/worker-groups/bindings")
      ? { workerGroupIds: ["a"], readAt: "2026-10-19T00:00:00Z" }
      : mockWorkerGroupBindings("android-demo-workers")
  }));
  vi.spyOn(axios, "create").mockReturnValue({ get } as unknown as AxiosInstance);
  const { router, host } = await mount(
    "/runtime/workers?view=bindings&group=android-demo-workers"
  );
  expect(observation.mounted).not.toHaveBeenCalled();
  expect(host.textContent).toContain("device-window");
  expect(host.textContent).toContain("Project 使用范围");
  expect(get.mock.calls.map((c) => c[0])).toEqual(
    expect.arrayContaining([
      "/v1/runtime-view/worker-groups/bindings",
      "/v1/runtime-view/worker-groups/android-demo-workers/bindings"
    ])
  );
  expect(get.mock.calls.every((c) => !c[0].includes("preview"))).toBe(true);
  const input = host.querySelector<HTMLInputElement>(
    'input[aria-label="已知 Group ID"]'
  )!;
  input.value = "kept-draft";
  input.dispatchEvent(new Event("input", { bubbles: true }));
  await nextTick();
  await router.push("/runtime/workers?view=workers&group=android-demo-workers");
  await nextTick();
  expect(observation.mounted).toHaveBeenCalledTimes(1);
  await router.push("/runtime/workers?view=bindings&group=android-demo-workers");
  await nextTick();
  expect(
    host.querySelector<HTMLInputElement>('input[aria-label="已知 Group ID"]')!.value
  ).toBe("kept-draft");
});
it("directory refresh preserves selection and a failed read keeps the original snapshot", async () => {
  let fail = false;
  let ids = ["android-demo-workers"];
  const get = vi.fn(async (path: string) => {
    if (path.endsWith("/worker-groups/bindings")) {
      if (fail) throw new Error("unavailable");
      return {
        data: {
          workerGroupIds: ids,
          readAt: "2026-10-19T00:00:00Z"
        }
      };
    }
    return { data: mockWorkerGroupBindings("android-demo-workers") };
  });
  vi.spyOn(axios, "create").mockReturnValue({ get } as unknown as AxiosInstance);
  const { host } = await mount("/runtime/workers?view=bindings");
  const readTime = host.querySelector(
    'section[aria-label="Group 资源绑定"] > p:nth-of-type(2)'
  )!.textContent;
  const refresh = [...host.querySelectorAll("button")].find((button) =>
    button.textContent?.includes("刷新绑定目录")
  )!;
  ids = ["a-new-first-group", "android-demo-workers"];
  refresh.click();
  await nextTick();
  await nextTick();
  await nextTick();
  expect(
    get.mock.calls.some(([path]) => path.includes("/a-new-first-group/bindings"))
  ).toBe(false);
  expect(host.textContent).toContain("device-window");
  fail = true;
  refresh.click();
  await nextTick();
  await nextTick();
  await nextTick();
  expect(host.textContent).toContain("目录读取失败");
  expect(host.textContent).toContain(readTime);
  expect(host.textContent).toContain("device-window");
  expect(observation.mounted).not.toHaveBeenCalled();
});
