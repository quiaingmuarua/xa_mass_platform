import { decodeUtf8 } from "@/files/text";
import { inspectImport, type ImportOptions } from "./import-model";

self.onmessage = (
  event: MessageEvent<{ content: string | ArrayBuffer; options: ImportOptions }>
) => {
  try {
    const text =
      typeof event.data.content === "string"
        ? event.data.content
        : decodeUtf8(event.data.content);
    self.postMessage({ text, report: inspectImport(text, event.data.options) });
  } catch (error) {
    self.postMessage({
      error: error instanceof Error ? error.message : "号码解析失败"
    });
  }
};
