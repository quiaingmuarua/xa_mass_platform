export type RuntimeViewerErrorKind =
  | "cancelled"
  | "configuration"
  | "http"
  | "network"
  | "schema";

interface RuntimeViewerErrorOptions {
  kind: RuntimeViewerErrorKind;
  message: string;
  requestId?: string;
  code?: number;
  status?: number;
  cause?: unknown;
}

export class RuntimeViewerError extends Error {
  readonly kind: RuntimeViewerErrorKind;
  readonly requestId?: string;
  readonly code?: number;
  readonly status?: number;

  constructor(options: RuntimeViewerErrorOptions) {
    super(options.message, { cause: options.cause });
    this.name = "RuntimeViewerError";
    this.kind = options.kind;
    this.requestId = options.requestId;
    this.code = options.code;
    this.status = options.status;
  }
}

export interface RuntimeViewerErrorPresentation {
  title: string;
  message: string;
  requestId?: string;
}

export function isRuntimeViewerCancellation(error: unknown): boolean {
  return error instanceof RuntimeViewerError && error.kind === "cancelled";
}

export function presentRuntimeViewerError(
  error: unknown
): RuntimeViewerErrorPresentation {
  if (error instanceof RuntimeViewerError) {
    return {
      title:
        error.status === 503 || error.code === 15002
          ? "Runtime View 暂不可用"
          : "无法读取 Runtime View",
      message: error.message,
      requestId: error.requestId
    };
  }
  return {
    title: "无法读取 Runtime View",
    message: "发生了未识别的读取错误。"
  };
}
