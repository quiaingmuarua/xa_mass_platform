import { inject, shallowRef, type InjectionKey } from "vue";
import { loadCatalog, SmsApiError, type Catalog } from "./api";

type Availability =
  | { status: "loading" | "disabled" | "demo" }
  | { status: "enabled"; catalog: Catalog }
  | { status: "unavailable"; message: string };

/** One bounded catalog observation per console lifetime, with explicit retries. */
export function createSmsAvailability(demo: boolean) {
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
    const timer = setTimeout(() => controller.abort(), 5_000);
    pending = (async () => {
      try {
        const catalog = await loadCatalog(controller.signal);
        if (!disposed && !controller.signal.aborted)
          state.value = { status: "enabled", catalog };
      } catch (error) {
        if (disposed) return;
        state.value =
          error instanceof SmsApiError && error.status === 404
            ? { status: "disabled" }
            : {
                status: "unavailable",
                message: controller.signal.aborted
                  ? "确认 SMS 可用性超时，请重试。"
                  : "暂时无法确认 SMS 可用性，请检查服务后重试。"
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
    acceptCatalog(catalog: Catalog) {
      if (!disposed && !demo) state.value = { status: "enabled", catalog };
    },
    dispose() {
      disposed = true;
      active?.abort();
    }
  };
}

export const smsAvailabilityKey: InjectionKey<
  ReturnType<typeof createSmsAvailability>
> = Symbol("sms-availability");

export function useSmsAvailability() {
  const value = inject(smsAvailabilityKey);
  if (!value) throw new Error("SMS requires the console availability context");
  return value;
}
