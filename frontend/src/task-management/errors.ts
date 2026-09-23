export type FiniteTaskErrorKind = "configuration" | "http" | "network" | "schema";

interface FiniteTaskErrorOptions {
  kind: FiniteTaskErrorKind;
  message: string;
  requestId?: string;
  code?: number;
  status?: number;
  cause?: unknown;
}

export class FiniteTaskError extends Error {
  readonly kind: FiniteTaskErrorKind;
  readonly requestId?: string;
  readonly code?: number;
  readonly status?: number;

  constructor(options: FiniteTaskErrorOptions) {
    super(options.message, { cause: options.cause });
    this.name = "FiniteTaskError";
    this.kind = options.kind;
    this.requestId = options.requestId;
    this.code = options.code;
    this.status = options.status;
  }
}

export interface FiniteTaskErrorPresentation {
  title: string;
  message: string;
  requestId?: string;
}

export function presentFiniteTaskError(error: unknown): FiniteTaskErrorPresentation {
  if (error instanceof FiniteTaskError) {
    return {
      title: error.kind === "configuration" ? "Invalid Task" : "Task API failed",
      message: error.message,
      requestId: error.requestId
    };
  }
  return {
    title: "Task API failed",
    message: error instanceof Error ? error.message : "Unexpected Task API failure."
  };
}

export function finiteTaskConfigurationError(message: string): FiniteTaskError {
  return new FiniteTaskError({ kind: "configuration", message });
}
