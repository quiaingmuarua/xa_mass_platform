import { TaskApiError } from "@/task-caller";
import { inject, shallowRef, type InjectionKey } from "vue";
import type { Catalog } from "./model";
import { type AppCheckTaskSource } from "./task-source";
import { createWorkbenchSession } from "./workbench";
import type { ConfigurationReadState } from "./configuration";

type Availability =
  | { status: "loading" | "disabled" }
  | { status: "enabled"; catalog: Catalog }
  | { status: "unavailable"; message: string };
export function createAppCheckContext(source: AppCheckTaskSource) {
  const sessions = new Map<string, ReturnType<typeof createWorkbenchSession>>();
  function sessionFor(userId: string) {
    let session = sessions.get(userId);
    if (!session) {
      session = createWorkbenchSession(userId);
      sessions.set(userId, session);
    }
    return session;
  }
  const state = shallowRef<Availability>({ status: "loading" });
  const configurationRead = shallowRef<ConfigurationReadState>({
    readAt: 0,
    busy: false,
    error: ""
  });
  let active: AbortController | undefined;
  let pending: Promise<void> | undefined;
  let disposed = false;
  function load(retry = false): Promise<void> {
    if (pending) return pending;
    if (disposed || (!retry && state.value.status !== "loading"))
      return Promise.resolve();
    const controller = new AbortController();
    active = controller;
    const previous = state.value.status === "enabled" ? state.value : undefined;
    if (!previous) state.value = { status: "loading" };
    configurationRead.value = { ...configurationRead.value, busy: true };
    const timer = setTimeout(() => controller.abort(), 5000);
    pending = (async () => {
      try {
        const catalog = await source.catalog(controller.signal);
        if (controller.signal.aborted) throw new Error("配置读取超时");
        if (!disposed) {
          state.value = { status: "enabled", catalog };
          configurationRead.value = { readAt: Date.now(), busy: false, error: "" };
        }
      } catch (error) {
        if (!disposed) {
          configurationRead.value = {
            ...configurationRead.value,
            busy: false,
            error: "配置读取失败，请重试。"
          };
          state.value =
            previous ??
            (error instanceof TaskApiError && error.status === 404
              ? { status: "disabled" }
              : {
                  status: "unavailable",
                  message: "暂时无法确认批量检查可用性，请重试。"
                });
        }
      } finally {
        clearTimeout(timer);
        pending = undefined;
        active = undefined;
      }
    })();
    return pending;
  }
  return {
    source,
    sessionFor,
    state,
    configurationRead,
    load,
    dispose() {
      disposed = true;
      active?.abort();
    }
  };
}
export const appCheckContextKey: InjectionKey<
  ReturnType<typeof createAppCheckContext>
> = Symbol("app-check-context");
export function useAppChecks() {
  const context = inject(appCheckContextKey);
  if (!context) throw new Error("App Checks requires its console context");
  return context;
}
