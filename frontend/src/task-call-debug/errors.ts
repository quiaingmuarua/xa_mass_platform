export type TaskCallDebugErrorKind = "configuration" | "http" | "network" | "schema";

interface TaskCallDebugErrorOptions {
  kind: TaskCallDebugErrorKind;
  message: string;
  requestId?: string;
  code?: number;
  status?: number;
  cause?: unknown;
}

export class TaskCallDebugError extends Error {
  readonly kind: TaskCallDebugErrorKind;
  readonly requestId?: string;
  readonly code?: number;
  readonly status?: number;

  constructor(options: TaskCallDebugErrorOptions) {
    super(options.message, { cause: options.cause });
    this.name = "TaskCallDebugError";
    this.kind = options.kind;
    this.requestId = options.requestId;
    this.code = options.code;
    this.status = options.status;
  }
}

export interface TaskCallDebugErrorPresentation {
  title: string;
  message: string;
  requestId?: string;
}

export function presentTaskCallDebugError(
  error: unknown
): TaskCallDebugErrorPresentation {
  if (error instanceof TaskCallDebugError) {
    return {
      title: error.kind === "configuration" ? "Task Call 配置无效" : "Task Call 失败",
      message: error.message,
      requestId: error.requestId
    };
  }
  return {
    title: "Task Call 失败",
    message: "发生了未识别的 Task Call 错误。"
  };
}

export function taskCallDebugConfigurationError(message: string): TaskCallDebugError {
  return new TaskCallDebugError({ kind: "configuration", message });
}
