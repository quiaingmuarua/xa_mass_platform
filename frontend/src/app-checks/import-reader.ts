import type { ImportOptions, ImportReport } from "./import-model";

export interface ImportRead {
  text: string;
  report: ImportReport;
}
export function createImportReader() {
  let active: Worker | undefined;
  let rejectActive: ((reason: Error) => void) | undefined;
  function cancel() {
    active?.terminate();
    active = undefined;
    rejectActive?.(new DOMException("已取消旧的号码解析", "AbortError"));
    rejectActive = undefined;
  }
  return {
    cancel,
    read(content: string | ArrayBuffer, options: ImportOptions): Promise<ImportRead> {
      cancel();
      return new Promise((resolve, reject) => {
        if (typeof Worker === "undefined") {
          reject(
            new Error("当前浏览器不支持后台文件解析，请使用支持 Web Worker 的浏览器。")
          );
          return;
        }
        const worker = new Worker(new URL("./import.worker.ts", import.meta.url), {
          type: "module"
        });
        active = worker;
        rejectActive = reject;
        const finish = () => {
          worker.terminate();
          active = undefined;
          rejectActive = undefined;
        };
        worker.onmessage = (event: MessageEvent<ImportRead & { error?: string }>) => {
          if (active !== worker) return;
          finish();
          if (event.data.error) reject(new Error(event.data.error));
          else resolve(event.data);
        };
        worker.onerror = () => {
          if (active !== worker) return;
          finish();
          reject(new Error("后台号码解析失败，请重新校验。"));
        };
        worker.postMessage(
          { content, options },
          typeof content === "string" ? [] : [content]
        );
      });
    }
  };
}
