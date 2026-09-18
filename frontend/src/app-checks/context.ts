import { inject, shallowRef, type InjectionKey } from "vue";
import type { Catalog } from "./model";
import { AppCheckApiError, type AppCheckTaskSource } from "./task-source";

type Availability =
  | { status: "loading" | "disabled" }
  | { status: "enabled"; catalog: Catalog }
  | { status: "unavailable"; message: string };
export function createAppCheckContext(source: AppCheckTaskSource) {
  const state = shallowRef<Availability>({ status: "loading" });
  let active: AbortController | undefined;
  let pending: Promise<void> | undefined;
  let disposed = false;
  function load(retry = false): Promise<void> {
    if (pending) return pending;
    if (disposed || (!retry && state.value.status !== "loading"))
      return Promise.resolve();
    const controller = new AbortController();
    active = controller;
    state.value = { status: "loading" };
    const timer = setTimeout(() => controller.abort(), 5000);
    pending = (async () => {
      try {
        const catalog = await source.catalog(controller.signal);
        if (!disposed && !controller.signal.aborted)
          state.value = { status: "enabled", catalog };
      } catch (error) {
        if (!disposed)
          state.value =
            error instanceof AppCheckApiError && error.status === 404
              ? { status: "disabled" }
              : {
                  status: "unavailable",
                  message: "暂时无法确认应用注册查询可用性，请重试。"
                };
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
    state,
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
