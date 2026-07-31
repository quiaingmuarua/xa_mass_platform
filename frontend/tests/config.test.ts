import { describe, expect, it } from "vitest";

import { parseRuntimeViewerConfig } from "@/runtime-viewer/config";

describe("parseRuntimeViewerConfig", () => {
  it("defaults to API mode and requires configured group identities", () => {
    const result = parseRuntimeViewerConfig({});

    expect(result).toEqual({
      ok: false,
      error: {
        title: "Runtime Viewer 配置不可用",
        details: ["API 模式必须配置 VITE_RUNTIME_WORKER_GROUP_IDS。"]
      }
    });
  });

  it("deduplicates configured identities while preserving order", () => {
    const result = parseRuntimeViewerConfig({
      VITE_RUNTIME_WORKER_GROUP_IDS: "group-b, group-a,group-b"
    });

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value).toEqual({
        mode: "api",
        apiBaseUrl: "/api",
        workerGroupIds: ["group-b", "group-a"]
      });
    }
  });

  it("uses deterministic scenario groups only in explicit mock mode", () => {
    const result = parseRuntimeViewerConfig({
      VITE_RUNTIME_DATA_SOURCE: "mock"
    });

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.workerGroupIds).toEqual([
        "scenario-phone-number-workers",
        "scenario-string-utils-workers"
      ]);
    }
  });

  it("rejects empty segments, absolute URLs, and more than twenty groups", () => {
    const tooMany = Array.from({ length: 21 }, (_, index) => `group-${index}`).join(
      ","
    );
    const result = parseRuntimeViewerConfig({
      VITE_RUNTIME_API_BASE_URL: "http://127.0.0.1:18082/api",
      VITE_RUNTIME_WORKER_GROUP_IDS: `group-a,,${tooMany}`
    });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.details).toEqual(
        expect.arrayContaining([
          "VITE_RUNTIME_API_BASE_URL 必须是以单个 / 开头的同源相对路径。",
          "VITE_RUNTIME_WORKER_GROUP_IDS 不能包含空的 WorkerGroup ID。"
        ])
      );
    }
  });

  it("rejects unknown data-source modes", () => {
    const result = parseRuntimeViewerConfig({
      VITE_RUNTIME_DATA_SOURCE: "fallback",
      VITE_RUNTIME_WORKER_GROUP_IDS: "group-a"
    });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.details).toContain(
        "VITE_RUNTIME_DATA_SOURCE 只能设置为 api 或 mock。"
      );
    }
  });
});
