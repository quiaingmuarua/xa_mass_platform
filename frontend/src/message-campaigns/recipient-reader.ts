import {
  inspectRecipientInput,
  type RecipientInput,
  type RecipientLimits
} from "@/files/phone-numbers";

export function createRecipientReader() {
  let cancel: (() => void) | undefined;
  return {
    read(
      source: string | File,
      country: string,
      limits: RecipientLimits
    ): Promise<RecipientInput> {
      cancel?.();
      // Browser builds use a worker; the non-browser test runtime executes the same parser.
      if (typeof Worker === "undefined")
        return inspectRecipientInput(source, country, limits);
      return new Promise((resolve, reject) => {
        const worker = new Worker(new URL("./recipient.worker.ts", import.meta.url), {
          type: "module"
        });
        let settled = false;
        const finish = () => {
          if (settled) return false;
          settled = true;
          worker.terminate();
          cancel = undefined;
          return true;
        };
        cancel = () => {
          if (finish())
            reject(new DOMException("Replaced recipient input", "AbortError"));
        };
        worker.onmessage = (
          event: MessageEvent<{ value?: RecipientInput; error?: string }>
        ) => {
          if (!finish()) return;
          if (event.data.value) resolve(event.data.value);
          else reject(new Error(event.data.error ?? "号码读取失败"));
        };
        worker.onerror = () => {
          if (finish()) reject(new Error("号码后台校验失败"));
        };
        worker.postMessage({ source, country, limits });
      });
    },
    cancel() {
      cancel?.();
    }
  };
}
