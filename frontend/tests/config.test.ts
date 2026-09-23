import { describe, expect, it } from "vitest";

import { parseRuntimeViewerConfig } from "@/runtime-viewer/config";

describe("parseRuntimeViewerConfig", () => {
  it("defaults to API mode without configured WorkerGroup identities", () => {
    expect(parseRuntimeViewerConfig({})).toEqual({
      ok: true,
      value: {
        mode: "api",
        apiBaseUrl: "/api"
      }
    });
  });

  it("supports explicit Mock mode without Group configuration", () => {
    expect(
      parseRuntimeViewerConfig({
        VITE_RUNTIME_DATA_SOURCE: "mock"
      })
    ).toEqual({
      ok: true,
      value: {
        mode: "mock",
        apiBaseUrl: "/api"
      }
    });
  });

  it("rejects an absolute API URL", () => {
    const result = parseRuntimeViewerConfig({
      VITE_RUNTIME_API_BASE_URL: "http://127.0.0.1:18082/api"
    });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.details).toContain(
        "VITE_RUNTIME_API_BASE_URL 必须是以单个 / 开头的同源相对路径。"
      );
    }
  });

  it("rejects unknown data-source modes", () => {
    const result = parseRuntimeViewerConfig({
      VITE_RUNTIME_DATA_SOURCE: "fallback"
    });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.details).toContain(
        "VITE_RUNTIME_DATA_SOURCE 只能设置为 api 或 mock。"
      );
    }
  });
});
