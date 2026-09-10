import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, nextTick, type App as VueApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import App from "../src/App.vue";
import { consoleRoutes } from "../src/router";
import { createMessageAvailability } from "../src/message-campaigns/availability";

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
  countries: ["CN", "US", "GB"].map((id) => ({
    id,
    workerGroupId: "demo-" + id.toLowerCase()
  })),
  limits: { campaigns: 50, messages: 50000, recipientsPerCampaign: 1000 }
};
const campaign = {
  id: "batch",
  requestId: "request",
  name: "Proof",
  country: "CN",
  body: "body",
  messageCount: 45,
  submission: "SUBMITTED",
  statuses: { SENT: 45 }
};
const message = { id: "m", recipientId: "recipient", status: "SENT", phone: "123" };
function installApi() {
  let runId = "one";
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/v1/sms/")) return new Response("{}", { status: 503 });
    let value: unknown;
    if (path.endsWith("/catalog")) value = { ...catalog, runId };
    else if (init?.method === "POST")
      value = { ...campaign, requestId: JSON.parse(String(init.body)).requestId };
    else if (path.endsWith("/campaigns/batch")) value = campaign;
    else if (path.includes("/batch/messages"))
      value = { runId, total: 45, items: [message] };
    else if (path.endsWith("/metrics"))
      value = {
        runId,
        campaigns: 1,
        messages: 45,
        statuses: {},
        submissionUnknown: 0,
        observationErrors: 0,
        sendingLatencyMillis: { p95: 1, p99: 1 },
        receiptObservationLatencyMillis: { p95: 1, p99: 1 }
      };
    else value = { runId, total: 45, items: [campaign] };
    return new Response(JSON.stringify(value));
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
async function mount(path = "/messages", mode = "api") {
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
function input(host: Element, label: string, value: string) {
  const element = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `input[aria-label="${label}"],textarea[aria-label="${label}"]`
  )!;
  element.value = value;
  element.dispatchEvent(new Event("input", { bubbles: true }));
}
async function fill(host: Element) {
  input(host, "批次名称", "Proof");
  input(host, "消息正文", "body");
  input(host, "收件人", "recipient-1\nrecipient-2");
  await settle();
}

describe("Messages console", () => {
  it.each([
    "/messages",
    "/messages/",
    "/messages/campaigns/batch",
    "/messages/metrics/"
  ])("opens %s independently of unavailable SMS", async (path) => {
    const { fetcher } = installApi();
    const { host } = await mount(path, "invalid-runtime-setting");
    expect(host.textContent).toContain("BUSINESS");
    expect(host.textContent).not.toContain("Runtime Viewer 配置不可用");
    expect(host.querySelector('aside a[href="/messages"]')).not.toBeNull();
    expect(host.querySelector('aside a[href="/sms"]')).toBeNull();
    expect(
      fetcher.mock.calls.filter(([url]) => String(url) === "/api/v1/messages/catalog")
    ).toHaveLength(1);
    expect(
      fetcher.mock.calls.filter(([url]) => String(url) === "/api/v1/sms/catalog")
    ).toHaveLength(1);
  });
  it("preserves inputs across tabs, submits once and pages messages", async () => {
    const { fetcher } = installApi();
    const { host, router } = await mount();
    await fill(host);
    await router.push("/messages/metrics");
    await settle();
    await router.push("/messages");
    await settle();
    expect(
      host.querySelector<HTMLInputElement>('input[aria-label="批次名称"]')?.value
    ).toBe("Proof");
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { cancelable: true, bubbles: true }));
    await settle();
    expect(router.currentRoute.value.path).toBe("/messages/campaigns/batch");
    expect(host.textContent).toContain("已发送");
    expect(host.textContent).not.toContain("已送达");
    host.querySelector<HTMLButtonElement>(".btn-next")!.click();
    await settle();
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).includes("/batch/messages?offset=30")
      )
    ).toBe(true);
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "POST")
    ).toHaveLength(1);
    await router.push("/reference/error-codes");
    await settle();
    const count = fetcher.mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetcher).toHaveBeenCalledTimes(count);
  });
  it.each(["disconnected", "invalid-response"])(
    "keeps %s submission uncertain and never retries on polls or navigation",
    async (failure) => {
      const { fetcher } = installApi();
      const { host, router } = await mount();
      await fill(host);
      if (failure === "disconnected")
        fetcher.mockRejectedValueOnce(new TypeError("connection closed"));
      else fetcher.mockResolvedValueOnce(new Response("{}"));
      host
        .querySelector("form")!
        .dispatchEvent(new Event("submit", { cancelable: true, bubbles: true }));
      await settle();
      expect(host.textContent).toContain("申请结果未确认");
      await router.push("/messages/metrics");
      await settle();
      await router.push("/messages");
      await settle();
      await vi.advanceTimersByTimeAsync(4000);
      expect(
        fetcher.mock.calls.filter(([, init]) => init?.method === "POST")
      ).toHaveLength(1);
    }
  );
  it("aborts pending business requests on exit and resets the workspace on instance change", async () => {
    const { fetcher, newRun } = installApi();
    const { host, router } = await mount();
    await fill(host);
    newRun();
    await vi.advanceTimersByTimeAsync(1000);
    await settle();
    expect(
      host.querySelector<HTMLInputElement>('input[aria-label="批次名称"]')?.value
    ).toBe("");
    await fill(host);
    let signal: AbortSignal | null | undefined;
    fetcher.mockImplementationOnce(async (_input, init) => {
      signal = init?.signal;
      return new Promise<Response>((_, reject) =>
        signal?.addEventListener("abort", () =>
          reject(new DOMException("Aborted", "AbortError"))
        )
      );
    });
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { cancelable: true, bubbles: true }));
    await settle();
    await router.push("/reference/error-codes");
    await settle();
    expect(signal?.aborted).toBe(true);
  });
  it("hides both business entries and performs no product calls in Mock Demo", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const { host, router } = await mount("/messages", "mock");
    expect(host.textContent).not.toContain("BUSINESS");
    expect(host.textContent).toContain("不支持此业务");
    await router.push("/sms");
    await settle();
    await vi.advanceTimersByTimeAsync(6000);
    expect(fetcher).not.toHaveBeenCalled();
  });
});

describe("Messages availability", () => {
  it.each([404, 503, 401])(
    "classifies HTTP %s without background probes",
    async (status) => {
      vi.useFakeTimers();
      const fetcher = vi.fn(async () => new Response("{}", { status }));
      vi.stubGlobal("fetch", fetcher);
      const availability = createMessageAvailability(false);
      await availability.load();
      expect(availability.state.value.status).toBe(
        status === 404 ? "disabled" : "unavailable"
      );
      await vi.advanceTimersByTimeAsync(10000);
      expect(fetcher).toHaveBeenCalledTimes(1);
      availability.dispose();
    }
  );
  it("shares a five-second observation and permits manual retry after timeout or invalid data", async () => {
    vi.useFakeTimers();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementationOnce(
        (_input, init) =>
          new Promise((_, reject) =>
            init?.signal?.addEventListener("abort", () => reject(new Error("aborted")))
          )
      )
      .mockResolvedValueOnce(new Response("{}"))
      .mockResolvedValueOnce(new Response(JSON.stringify(catalog)));
    vi.stubGlobal("fetch", fetcher);
    const availability = createMessageAvailability(false);
    const first = availability.load();
    expect(availability.load()).toBe(first);
    await vi.advanceTimersByTimeAsync(5000);
    await first;
    expect(availability.state.value.status).toBe("unavailable");
    await availability.load(true);
    expect(availability.state.value.status).toBe("unavailable");
    await availability.load(true);
    expect(availability.state.value.status).toBe("enabled");
    availability.dispose();
  });
});
