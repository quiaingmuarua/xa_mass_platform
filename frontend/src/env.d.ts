/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_RUNTIME_DATA_SOURCE?: string;
  readonly VITE_RUNTIME_API_BASE_URL?: string;
  readonly VITE_RUNTIME_PROXY_TARGET?: string;
  readonly VITE_RUNTIME_WORKER_GROUP_IDS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
