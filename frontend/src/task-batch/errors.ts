export type TaskBatchErrorKind = "configuration" | "http" | "network" | "schema";

interface TaskBatchErrorOptions {
  kind: TaskBatchErrorKind;
  message: string;
  requestId?: string;
  code?: number;
  status?: number;
  cause?: unknown;
}

export class TaskBatchError extends Error {
  readonly kind: TaskBatchErrorKind;
  readonly requestId?: string;
  readonly code?: number;
  readonly status?: number;

  constructor(options: TaskBatchErrorOptions) {
    super(options.message, { cause: options.cause });
    this.name = "TaskBatchError";
    this.kind = options.kind;
    this.requestId = options.requestId;
    this.code = options.code;
    this.status = options.status;
  }
}

export interface TaskBatchErrorPresentation {
  title: string;
  message: string;
  requestId?: string;
}

export function presentTaskBatchError(error: unknown): TaskBatchErrorPresentation {
  if (error instanceof TaskBatchError) {
    return {
      title:
        error.kind === "configuration" ? "Invalid Task Batch" : "Task Batch failed",
      message: error.message,
      requestId: error.requestId
    };
  }
  return {
    title: "Task Batch failed",
    message: "Task Batch encountered an unexpected error."
  };
}

export function taskBatchConfigurationError(message: string): TaskBatchError {
  return new TaskBatchError({ kind: "configuration", message });
}
