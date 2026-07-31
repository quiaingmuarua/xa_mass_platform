import type { RuntimeDataSourceMode, RuntimeViewerConfigResult } from "./types";

const DEFAULT_API_BASE_URL = "/api";
const DEFAULT_MOCK_GROUP_IDS = [
  "scenario-phone-number-workers",
  "scenario-string-utils-workers"
];
const MAX_WORKER_GROUP_IDS = 20;

export interface RuntimeViewerEnv {
  VITE_RUNTIME_DATA_SOURCE?: string;
  VITE_RUNTIME_API_BASE_URL?: string;
  VITE_RUNTIME_WORKER_GROUP_IDS?: string;
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

  const rawGroupIds = env.VITE_RUNTIME_WORKER_GROUP_IDS;
  let workerGroupIds: string[] = [];
  if (rawGroupIds !== undefined && rawGroupIds.trim() !== "") {
    const segments = rawGroupIds.split(",").map((value) => value.trim());
    if (segments.some((value) => value.length === 0)) {
      details.push("VITE_RUNTIME_WORKER_GROUP_IDS 不能包含空的 WorkerGroup ID。");
    } else {
      workerGroupIds = [...new Set(segments)];
    }
  } else if (mode === "mock") {
    workerGroupIds = [...DEFAULT_MOCK_GROUP_IDS];
  }

  if (mode === "api" && workerGroupIds.length === 0) {
    details.push("API 模式必须配置 VITE_RUNTIME_WORKER_GROUP_IDS。");
  }
  if (workerGroupIds.length > MAX_WORKER_GROUP_IDS) {
    details.push(`去重后的 WorkerGroup ID 不能超过 ${MAX_WORKER_GROUP_IDS} 个。`);
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
      apiBaseUrl,
      workerGroupIds
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
