import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, nextTick, type App as VueApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import App from "../src/App.vue";
import { consoleRoutes } from "../src/router";
import { canCancel, waitingMessage } from "../src/sms/api";

let mounted: VueApp | undefined;
afterEach(() => {
  mounted?.unmount();
  mounted = undefined;
  document.body.replaceChildren();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

const catalog = {
  runId: "one",
  version: "0.1.0-preview",
  applications: [
    { id: "A", name: "应用 A", templates: [{ id: "A-code", priority: 200 }] }
  ]
};
const initialMetrics = {
  runId: "one",
  requests: 0,
  statuses: {},
  activeListenersObserved: 0,
  establishmentLatencyMillis: { count: 0, p95: 0, p99: 0 },
  smsObservationLatencyMillis: { count: 0, p95: 0, p99: 0 }
};
const listener = {
  id: "listener-one",
  requestId: "request-one",
  applicationId: "A",
  country: "CN",
  phone: "+861700000001",
  status: "LISTENING",
  createdAt: 1
};

function installApi() {
  let runId = "one";
  let status = "LISTENING";
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    let body: unknown;
    if (url.endsWith("/catalog")) body = { ...catalog, runId };
    else if (url.endsWith("/metrics")) body = { ...initialMetrics, runId };
    else if (url.includes("/cancel")) {
      status = "CANCELLING";
      body = { ...listener, status };
    } else if (url.endsWith("/listeners") && init?.method === "POST")
      body = { ...listener, status };
    else if (url.endsWith("/" + listener.id)) body = { ...listener, status };
    else body = { total: 45, items: [{ ...listener, status }] };
    return new Response(JSON.stringify(body));
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    fetcher,
    newRun: () => {
      runId = "two";
    }
  };
}

async function settle() {
  await vi.advanceTimersByTimeAsync(0);
  await nextTick();
}
async function mountConsole(path = "/sms", mode = "api") {
  vi.useFakeTimers();
  vi.stubEnv("VITE_RUNTIME_DATA_SOURCE", mode);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: consoleRoutes
  });
  await router.push(path);
  await router.isReady();
  const host = document.createElement("div");
  document.body.append(host);
  mounted = createApp(App).use(createPinia()).use(router).use(ElementPlus);
  mounted.mount(host);
  await settle();
  return { host, router };
}

describe("unified SMS console", () => {
  it("keeps uncertain outcomes distinct from received and expired", () => {
    expect(canCancel("UNCONFIRMED")).toBe(false);
    expect(canCancel("CANCELLING")).toBe(false);
    expect(waitingMessage({ ...listener, status: "UNCONFIRMED" })).toContain(
      "不能判定远端接码成功"
    );
  });

  it.each(["/sms", "/sms/", "/sms/listeners", "/sms/metrics/"])(
    "loads %s in the shared shell with one catalog observation",
    async (path) => {
      const { fetcher } = installApi();
      const { host } = await mountConsole(path);
      expect(host.textContent).toContain("CONSOLE");
      expect(host.textContent).toContain("BUSINESS");
      expect(host.querySelectorAll("aside")).toHaveLength(1);
      expect(
        host
          .querySelector('aside a[href="/sms"]')
          ?.classList.contains("router-link-active")
      ).toBe(true);
      expect(host.querySelector('.sms-tabs [aria-current="page"]')).not.toBeNull();
      expect(
        fetcher.mock.calls.filter(([url]) => String(url).endsWith("/catalog"))
      ).toHaveLength(1);
    }
  );

  it("preserves form and selection across tabs, cancels through SMS and stops requests on exit", async () => {
    const { fetcher } = installApi();
    const { host, router } = await mountConsole("/sms", "invalid-runtime-setting");
    expect(host.textContent).not.toContain("Runtime Viewer 配置不可用");
    const duration = host.querySelector<HTMLInputElement>('input[type="number"]')!;
    duration.value = "137";
    duration.dispatchEvent(new Event("input", { bubbles: true }));
    duration.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    const created = fetcher.mock.calls.find(
      ([url, init]) => String(url).endsWith("/listeners") && init?.method === "POST"
    );
    expect(JSON.parse(String(created?.[1]?.body))).toMatchObject({
      listenSeconds: 137,
      applicationId: "A",
      country: "CN"
    });
    expect(host.querySelector(".phone")?.textContent).toContain(listener.phone);
    await router.push("/sms/metrics");
    await settle();
    expect(host.textContent).toContain("业务结果与延迟");
    await router.push("/sms/listeners");
    await settle();
    host.querySelector<HTMLButtonElement>(".btn-next")!.click();
    await settle();
    expect(fetcher.mock.calls.some(([url]) => String(url).includes("offset=20"))).toBe(
      true
    );
    await router.push("/sms");
    await settle();
    expect(host.querySelector<HTMLInputElement>('input[type="number"]')?.value).toBe(
      "137"
    );
    expect(host.querySelector(".phone")?.textContent).toContain(listener.phone);
    host.querySelector<HTMLButtonElement>(".current-panel .actions button")!.click();
    await settle();
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).endsWith("/cancel"))
    ).toHaveLength(1);
    expect(host.querySelector(".current-panel")?.textContent).toContain("取消中");
    host.querySelector<HTMLButtonElement>('[data-testid="theme-toggle"]')!.click();
    await settle();
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(
      fetcher.mock.calls.every(([url]) => String(url).startsWith("/api/v1/sms/"))
    ).toBe(true);
    await router.push("/runtime/tasks");
    await settle();
    expect(host.textContent).toContain("Runtime Viewer 配置不可用");
    const finished = fetcher.mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetcher).toHaveBeenCalledTimes(finished);
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("discards an old selected listener when the backend run changes", async () => {
    const { newRun, fetcher } = installApi();
    const { host } = await mountConsole();
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    expect(host.querySelector(".phone")).not.toBeNull();
    newRun();
    await vi.advanceTimersByTimeAsync(1000);
    await settle();
    expect(host.querySelector(".phone")).toBeNull();
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).endsWith("/catalog"))
    ).toHaveLength(2);
  });

  it("aborts pending observations and a submitted operation on exit without resubmitting it", async () => {
    const { fetcher } = installApi();
    const { host, router } = await mountConsole("/sms", "invalid-runtime-setting");
    const original = fetcher.getMockImplementation()!;
    const signals: AbortSignal[] = [];
    fetcher.mockImplementation((input, init) => {
      if (init?.method === "POST" || String(input).endsWith("/metrics")) {
        const signal = init!.signal!;
        signals.push(signal);
        return new Promise<Response>((_, reject) => {
          signal.addEventListener(
            "abort",
            () => reject(new DOMException("Aborted", "AbortError")),
            { once: true }
          );
        });
      }
      return original(input, init);
    });
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    await vi.advanceTimersByTimeAsync(1000);
    expect(signals).toHaveLength(2);
    await router.push("/runtime/workers");
    await settle();
    expect(signals.every((signal) => signal.aborted)).toBe(true);
    const exited = fetcher.mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetcher).toHaveBeenCalledTimes(exited);
    fetcher.mockImplementation(original);
    await router.push("/sms");
    await settle();
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "POST")
    ).toHaveLength(1);
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).endsWith("/catalog"))
    ).toHaveLength(1);
  });

  it("keeps a failed submission uncertain and never retries it during polling or tab changes", async () => {
    const { fetcher } = installApi();
    const { host, router } = await mountConsole();
    fetcher.mockRejectedValueOnce(new TypeError("Connection closed"));
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    expect(host.querySelector(".phone")).toBeNull();
    await router.push("/sms/listeners");
    await settle();
    await vi.advanceTimersByTimeAsync(3000);
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "POST")
    ).toHaveLength(1);
  });

  it("offers the shared navigation in the mobile drawer and closes it after navigation", async () => {
    installApi();
    const { host, router } = await mountConsole("/sms", "invalid-runtime-setting");
    const toggle = host.querySelector<HTMLButtonElement>(
      '[aria-label="Open navigation"]'
    )!;
    toggle.click();
    await settle();
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    const drawer = document.querySelector('[role="dialog"]')!;
    expect(drawer.textContent).toContain("BUSINESS");
    drawer.querySelector<HTMLAnchorElement>('a[href="/runtime/workers"]')!.click();
    await settle();
    expect(router.currentRoute.value.path).toBe("/runtime/workers");
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
  });

  it.each([404, 503])(
    "hides the entry on %s and explains the state on a direct visit",
    async (status) => {
      const fetcher = vi.fn(async () => new Response("{}", { status }));
      vi.stubGlobal("fetch", fetcher);
      const { host } = await mountConsole();
      expect(host.textContent).not.toContain("BUSINESS");
      expect(host.querySelector('aside a[href="/sms"]')).toBeNull();
      expect(host.textContent).toContain(status === 404 ? "未启用" : "无法确认");
      await vi.advanceTimersByTimeAsync(6000);
      expect(fetcher).toHaveBeenCalledTimes(1);
      if (status === 503) {
        fetcher.mockImplementationOnce(
          async () => new Response(JSON.stringify(catalog))
        );
        host.querySelector<HTMLButtonElement>(".sms-unavailable button")!.click();
        await settle();
        expect(host.querySelector('aside a[href="/sms"]')).not.toBeNull();
      }
    }
  );

  it("shows loading until the catalog is received", async () => {
    let complete!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            complete = resolve;
          })
      )
    );
    const { host } = await mountConsole();
    expect(host.textContent).toContain("正在确认");
    expect(host.textContent).not.toContain("BUSINESS");
    complete(new Response("{}", { status: 404 }));
    await settle();
    expect(host.textContent).toContain("未启用");
  });

  it("never contacts SMS in public Mock Demo, including direct visits", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const { host, router } = await mountConsole("/sms", "mock");
    expect(host.textContent).toContain("不支持此业务");
    expect(host.querySelector('[data-testid="source-badge"]')?.textContent).toContain(
      "Mock source"
    );
    expect(host.textContent).not.toContain("BUSINESS");
    await router.push("/sms/listeners");
    await settle();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetcher).not.toHaveBeenCalled();
  });
});
