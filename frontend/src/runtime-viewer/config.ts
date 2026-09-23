import type { RuntimeDataSourceMode, RuntimeViewerConfigResult } from "./types";

const DEFAULT_API_BASE_URL = "/api";

export interface RuntimeViewerEnv {
  VITE_RUNTIME_DATA_SOURCE?: string;
  VITE_RUNTIME_API_BASE_URL?: string;
}

export function parseRuntimeViewerConfig(
  env: RuntimeViewerEnv
): RuntimeViewerConfigResult {
  const details: string[] = [];
  const rawMode = env.VITE_RUNTIME_DATA_SOURCE?.trim() || "api";
  const mode =
    rawMode === "api" || rawMode === "mock" ? (rawMode as RuntimeDataSourceMode) : null;
  if (mode === null) {
    details.push("VITE_RUNTIME_DATA_SOURCE 只能设置为 api 或 mock。");
  }

  const apiBaseUrl = normalizeApiBaseUrl(
    env.VITE_RUNTIME_API_BASE_URL?.trim() || DEFAULT_API_BASE_URL
  );
  if (apiBaseUrl === null) {
    details.push("VITE_RUNTIME_API_BASE_URL 必须是以单个 / 开头的同源相对路径。");
  }

  if (details.length > 0 || mode === null || apiBaseUrl === null) {
    return {
      ok: false,
      error: {
        title: "Runtime Viewer 配置不可用",
        details
      }
    };
  }

  return {
    ok: true,
    value: {
      mode,
      apiBaseUrl
    }
  };
}

function normalizeApiBaseUrl(value: string): string | null {
  if (!value.startsWith("/") || value.startsWith("//")) {
    return null;
  }
  if (value === "/") {
    return value;
  }
  return value.replace(/\/+$/, "");
}
