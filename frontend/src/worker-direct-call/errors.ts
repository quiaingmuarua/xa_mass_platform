export type WorkerDirectCallErrorKind = "configuration" | "http" | "network" | "schema";

interface WorkerDirectCallErrorOptions {
  kind: WorkerDirectCallErrorKind;
  message: string;
  requestId?: string;
  code?: number;
  status?: number;
  cause?: unknown;
}

export class WorkerDirectCallError extends Error {
  readonly kind: WorkerDirectCallErrorKind;
  readonly requestId?: string;
  readonly code?: number;
  readonly status?: number;

  constructor(options: WorkerDirectCallErrorOptions) {
    super(options.message, { cause: options.cause });
    this.name = "WorkerDirectCallError";
    this.kind = options.kind;
    this.requestId = options.requestId;
    this.code = options.code;
    this.status = options.status;
  }
}

export interface WorkerDirectCallErrorPresentation {
  title: string;
  message: string;
  requestId?: string;
}

export function presentWorkerDirectCallError(
  error: unknown
): WorkerDirectCallErrorPresentation {
  if (error instanceof WorkerDirectCallError) {
    return {
      title:
        error.kind === "configuration" ? "Direct Call 配置无效" : "Direct Call 失败",
      message: error.message,
      requestId: error.requestId
    };
  }
  return {
    title: "Direct Call 失败",
    message: "发生了未识别的 Direct Call 错误。"
  };
}

export function workerDirectCallConfigurationError(
  message: string
): WorkerDirectCallError {
  return new WorkerDirectCallError({ kind: "configuration", message });
}
