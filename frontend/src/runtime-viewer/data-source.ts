import { HttpRuntimeViewerDataSource } from "./http-data-source";
import { MockRuntimeViewerDataSource } from "./mock-data-source";
import type { RuntimeViewerConfig, RuntimeViewerDataSource } from "./types";

export function createRuntimeViewerDataSource(
  config: RuntimeViewerConfig
): RuntimeViewerDataSource {
  if (config.mode === "mock") {
    return new MockRuntimeViewerDataSource();
  }
  return new HttpRuntimeViewerDataSource(config.apiBaseUrl);
}
