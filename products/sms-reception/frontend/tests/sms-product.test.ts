import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, nextTick, type App as VueApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import App from "@/App.vue";
import SmsWorkspace from "@/features/reception/SmsWorkspace.vue";
import { canCancel, waitingMessage } from "@/features/reception/api";

let mounted: VueApp | undefined;
afterEach(() => {
    mounted?.unmount();
    mounted = undefined;
    document.body.replaceChildren();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

describe("SMS product boundary", () => {
    it("keeps uncertain outcomes separate from received and expired", () => {
        expect(canCancel("UNCONFIRMED")).toBe(false);
        expect(canCancel("CANCELLING")).toBe(false);
        expect(
            waitingMessage({
                id: "one",
                requestId: "one",
                applicationId: "A",
                country: "CN",
                status: "UNCONFIRMED",
                createdAt: 0
            })
        ).toContain("不能判定远端接码成功");
    });

    it("uses product APIs despite invalid Runtime config and stops polling on exit", async () => {
        vi.useFakeTimers();
        vi.stubEnv("VITE_RUNTIME_DATA_SOURCE", "invalid-runtime-setting");
        const fetched = vi.fn(async (url: string) => {
            let body: unknown;
            if (url.includes("/catalog")) {
                body = {
                    version: "0.1.0-preview",
                    runId: "one",
                    applications: [
                        {
                            id: "A",
                            name: "应用 A",
                            templates: [{ id: "A-code", priority: 200 }]
                        }
                    ]
                };
            } else if (url.includes("/metrics")) {
                body = {
                    runId: "one",
                    requests: 0,
                    statuses: {},
                    activeListenersObserved: 0,
                    establishmentLatencyMillis: { p95: 0, p99: 0 },
                    smsObservationLatencyMillis: { p95: 0, p99: 0 }
                };
            } else {
                body = { total: 0, items: [] };
            }
            return new Response(JSON.stringify(body), { status: 200 });
        });
        vi.stubGlobal("fetch", fetched);
        const router = createRouter({
            history: createMemoryHistory(),
            routes: [
                { path: "/sms", component: SmsWorkspace },
                { path: "/sms/metrics", component: SmsWorkspace }
            ]
        });
        await router.push("/sms");
        await router.isReady();
        const host = document.createElement("div");
        document.body.append(host);
        mounted = createApp(App).use(createPinia()).use(router).use(ElementPlus);
        mounted.mount(host);
        await vi.advanceTimersByTimeAsync(0);
        await nextTick();
        expect(host.textContent).toContain("接码工作台");
        expect(host.textContent).not.toContain("Runtime Viewer 配置不可用");
        expect(
            fetched.mock.calls.every(([url]) => url.startsWith("/api/v1/sms/"))
        ).toBe(true);
        const duration = host.querySelector<HTMLInputElement>('input[type="number"]')!;
        duration.value = "137";
        duration.dispatchEvent(new Event("input", { bubbles: true }));
        await nextTick();
        duration.dispatchEvent(new Event("change", { bubbles: true }));
        duration.dispatchEvent(new FocusEvent("blur", { bubbles: true }));
        await nextTick();
        expect(duration.value).toBe("137");
        const initial = fetched.mock.calls.length;
        await vi.advanceTimersByTimeAsync(1000);
        expect(fetched.mock.calls.length).toBeGreaterThan(initial);
        expect(duration.value).toBe("137");
        await router.push("/sms/metrics");
        await nextTick();
        expect(host.textContent).toContain("业务结果与延迟");
        mounted.unmount();
        mounted = undefined;
        const finished = fetched.mock.calls.length;
        await vi.advanceTimersByTimeAsync(5000);
        expect(fetched).toHaveBeenCalledTimes(finished);
    });
});
