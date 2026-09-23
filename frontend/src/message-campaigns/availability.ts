import { inject, shallowRef, type InjectionKey } from "vue";
import { loadCatalog, MessageApiError, type Catalog } from "./api";

type Availability =
  | { status: "loading" | "disabled" | "demo" }
  | { status: "enabled"; catalog: Catalog }
  | { status: "unavailable"; message: string };

/** Messages has its own bounded observation; SMS availability cannot gate it. */
export function createMessageAvailability(demo: boolean) {
  const state = shallowRef<Availability>({ status: demo ? "demo" : "loading" });
  let active: AbortController | undefined;
  let pending: Promise<void> | undefined;
  let disposed = false;
  function load(retry = false): Promise<void> {
    if (pending) return pending;
    if (disposed || demo || (!retry && state.value.status !== "loading"))
      return Promise.resolve();
    const controller = new AbortController();
    active = controller;
    state.value = { status: "loading" };
    const timer = setTimeout(() => controller.abort(), 5000);
    pending = (async () => {
      try {
        const catalog = await loadCatalog(controller.signal);
        if (!disposed && !controller.signal.aborted)
          state.value = { status: "enabled", catalog };
      } catch (error) {
        if (disposed) return;
        state.value =
          error instanceof MessageApiError && error.status === 404
            ? { status: "disabled" }
            : {
                status: "unavailable",
                message: controller.signal.aborted
                  ? "确认 Messages 可用性超时，请重试。"
                  : "暂时无法确认 Messages 可用性，请重试。"
              };
      } finally {
        clearTimeout(timer);
        active = undefined;
        pending = undefined;
      }
    })();
    return pending;
  }
  return {
    state,
    load,
    dispose() {
      disposed = true;
      active?.abort();
    }
  };
}
export const messageAvailabilityKey: InjectionKey<
  ReturnType<typeof createMessageAvailability>
> = Symbol("message-availability");
export function useMessageAvailability() {
  const value = inject(messageAvailabilityKey);
  if (!value) throw new Error("Messages requires console availability context");
  return value;
}
