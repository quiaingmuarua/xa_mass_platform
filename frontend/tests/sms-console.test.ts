import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, nextTick, type App as VueApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import App from "../src/App.vue";
import { consoleRoutes } from "../src/router";
import { waitingMessage, type Reception } from "../src/sms/api";

let mounted: VueApp | undefined;
afterEach(() => {
  mounted?.unmount();
  mounted = undefined;
  document.body.replaceChildren();
  sessionStorage.clear();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});
const catalog = {
  runId: "one",
  version: "0.1.0-preview",
  countries: ["CN", "US", "GB"],
  applications: [
    { id: "A", name: "应用 A", templates: [{ id: "A-code", priority: 200 }] }
  ]
};
const initialMetrics = {
  runId: "one",
  requests: 0,
  queries: 0,
  errors: 0,
  acquisitionLatencyMillis: { count: 0, p95: 0, p99: 0 },
  queryLatencyMillis: { count: 0, p95: 0, p99: 0 },
  projection: {
    accepted: 0,
    dropped: 0,
    failures: 0,
    recorded: 0,
    queueBatches: 0,
    meanQueueMillis: 0
  }
};
const reception: Reception = {
  messageId: "sms-one",
  applicationId: "A",
  country: "CN",
  phoneNumber: "+861700000001",
  status: "WAITING",
  leaseActive: true,
  leaseUntil: Date.now() + 60000
};
function installApi() {
  let runId = "one";
  let current = { ...reception };
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.startsWith("/api/v1/messages/") || url.startsWith("/api/v1/app-checks/"))
      return new Response("{}", { status: 404 });
    let body: unknown;
    if (url.endsWith("/catalog")) body = { ...catalog, runId };
    else if (url.endsWith("/metrics")) body = { ...initialMetrics, runId };
    else if (url.endsWith("/numbers:lease") && init?.method === "POST") body = current;
    else if (url.includes("/messages/")) body = current;
    else return new Response("{}", { status: 404 });
    return new Response(JSON.stringify(body));
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    fetcher,
    newRun: () => {
      runId = "two";
    },
    update: (change: Partial<Reception>) => {
      current = { ...current, ...change };
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
async function submit(host: HTMLElement) {
  host
    .querySelector("form")!
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await settle();
}
describe("unified SMS console", () => {
  it("keeps an unobserved acquisition distinct from an expired lease", () => {
    expect(waitingMessage({ messageId: "id", status: "NOT_OBSERVED" })).toContain(
      "暂未观察"
    );
    expect(waitingMessage({ ...reception, leaseActive: false })).toContain(
      "租期已结束"
    );
  });
  it.each(["/sms", "/sms/", "/sms/metrics/"])(
    "loads %s in the shared shell",
    async (path) => {
      const { fetcher } = installApi();
      const { host } = await mountConsole(path);
      expect(host.textContent).toContain("CONSOLE");
      expect(host.querySelectorAll("aside")).toHaveLength(1);
      expect(host.querySelector('.sms-tabs [aria-current="page"]')).not.toBeNull();
      expect(
        fetcher.mock.calls.filter(([url]) => String(url) === "/api/v1/sms/catalog")
      ).toHaveLength(1);
    }
  );
  it("returns number and messageId and keeps polling after the first SMS", async () => {
    const { fetcher, update } = installApi();
    const { host } = await mountConsole();
    const duration = host.querySelector<HTMLInputElement>('input[type="number"]')!;
    duration.value = "137";
    duration.dispatchEvent(new Event("input", { bubbles: true }));
    await settle();
    await submit(host);
    const request = fetcher.mock.calls.find(([url]) =>
      String(url).endsWith("/numbers:lease")
    );
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({
      applicationId: "A",
      country: "CN",
      leaseSeconds: 137
    });
    expect(host.querySelector(".phone")?.textContent).toContain(reception.phoneNumber);
    expect(host.querySelector(".record-id")?.textContent).toContain(
      reception.messageId
    );
    update({
      status: "RECEIVED",
      sms: {
        smsId: "one",
        text: "[A] 111111",
        code: "111111",
        templateId: "A-code",
        receivedAt: 1
      }
    });
    await vi.advanceTimersByTimeAsync(1000);
    await settle();
    expect(host.querySelector(".sms-content")?.textContent).toContain("111111");
    update({
      sms: {
        smsId: "two",
        text: "[A] 222222",
        code: "222222",
        templateId: "A-code",
        receivedAt: 2
      }
    });
    await vi.advanceTimersByTimeAsync(1000);
    await settle();
    expect(host.querySelector(".sms-content")?.textContent).toContain("222222");
    update({ leaseActive: false });
    await vi.advanceTimersByTimeAsync(1000);
    await settle();
    const reads = fetcher.mock.calls.filter(([url]) =>
      String(url).includes("/sms/messages/")
    ).length;
    await vi.advanceTimersByTimeAsync(3000);
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).includes("/sms/messages/"))
    ).toHaveLength(reads);
    expect(host.querySelector(".sms-content")?.textContent).toContain("222222");
    expect(
      fetcher.mock.calls.some(
        ([url]) => String(url).includes("/listeners") || String(url).includes("/cancel")
      )
    ).toBe(false);
  });
  it("preserves local IDs across backend restarts and supports manual lookup", async () => {
    const { newRun, fetcher } = installApi();
    const { host } = await mountConsole();
    await submit(host);
    newRun();
    await vi.advanceTimersByTimeAsync(1000);
    await settle();
    expect(host.querySelector(".phone")?.textContent).toContain(reception.phoneNumber);
    expect(
      JSON.parse(sessionStorage.getItem("sms-reception-session")!)[0].messageId
    ).toBe(reception.messageId);
    host
      .querySelector(".lookup")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).includes("/sms/messages/"))
    ).not.toHaveLength(0);
  });
  it("continues a slow manual ID lookup across the next polling tick", async () => {
    const { fetcher } = installApi();
    const { host } = await mountConsole();
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (input, init) => {
      if (String(input).includes("/sms/messages/"))
        await new Promise((resolve) => setTimeout(resolve, 1500));
      return original(input, init);
    });
    const input = host.querySelector<HTMLInputElement>(".lookup input")!;
    input.value = reception.messageId;
    input.dispatchEvent(new Event("input", { bubbles: true }));
    await settle();
    host
      .querySelector(".lookup")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await vi.advanceTimersByTimeAsync(3000);
    await settle();
    expect(host.querySelector(".phone")?.textContent).toContain(reception.phoneNumber);
  });

  it("retains selection across metrics navigation and stops requests on exit", async () => {
    const { fetcher } = installApi();
    const { host, router } = await mountConsole("/sms", "invalid-runtime-setting");
    await submit(host);
    await router.push("/sms/metrics");
    await settle();
    expect(host.textContent).toContain("取号等待 P95");
    await router.push("/sms");
    await settle();
    expect(host.querySelector(".phone")?.textContent).toContain(reception.phoneNumber);
    await router.push("/runtime/tasks");
    await settle();
    const stopped = fetcher.mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetcher).toHaveBeenCalledTimes(stopped);
  });
  it("aborts in-flight acquisition and reads on exit without resubmitting", async () => {
    const { fetcher } = installApi();
    const { host, router } = await mountConsole("/sms", "invalid-runtime-setting");
    const original = fetcher.getMockImplementation()!;
    const signals: AbortSignal[] = [];
    fetcher.mockImplementation((input, init) => {
      if (init?.method === "POST" || String(input).endsWith("/metrics")) {
        const signal = init!.signal!;
        signals.push(signal);
        return new Promise<Response>((_, reject) =>
          signal.addEventListener(
            "abort",
            () => reject(new DOMException("Aborted", "AbortError")),
            { once: true }
          )
        );
      }
      return original(input, init);
    });
    await submit(host);
    await vi.advanceTimersByTimeAsync(1000);
    await router.push("/runtime/workers");
    await settle();
    expect(signals.every((signal) => signal.aborted)).toBe(true);
    fetcher.mockImplementation(original);
    await router.push("/sms");
    await settle();
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "POST")
    ).toHaveLength(1);
  });
  it("does not poll an unavailable or public demo scenario", async () => {
    const fetcher = vi.fn(async () => new Response("{}", { status: 404 }));
    vi.stubGlobal("fetch", fetcher);
    const { host } = await mountConsole("/sms", "mock");
    await vi.advanceTimersByTimeAsync(10000);
    expect(host.textContent).toContain("公开 Demo");
    expect(fetcher).not.toHaveBeenCalled();
  });
});
