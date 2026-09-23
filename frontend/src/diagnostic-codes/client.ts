import { platformDiagnosticCodesSchema, type PlatformDiagnosticCodes } from "./schema";

export const PLATFORM_DIAGNOSTIC_CODES_URL =
  "/reference/platform-diagnostic-codes.json";

export type DiagnosticCodeLoadErrorKind = "unavailable" | "incompatible";

export class DiagnosticCodeLoadError extends Error {
  constructor(
    readonly kind: DiagnosticCodeLoadErrorKind,
    message: string,
    options?: ErrorOptions
  ) {
    super(message, options);
    this.name = "DiagnosticCodeLoadError";
  }
}

export async function loadPlatformDiagnosticCodes(
  signal?: AbortSignal,
  fetcher: typeof fetch = fetch
): Promise<PlatformDiagnosticCodes> {
  let response: Response;
  try {
    response = await fetcher(PLATFORM_DIAGNOSTIC_CODES_URL, {
      headers: { Accept: "application/json" },
      signal
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new DiagnosticCodeLoadError(
      "unavailable",
      "The current-build diagnostic dictionary could not be loaded.",
      { cause: error }
    );
  }
  if (!response.ok) {
    throw new DiagnosticCodeLoadError(
      "unavailable",
      `The current-build diagnostic dictionary returned HTTP ${response.status}.`
    );
  }

  let payload: unknown;
  try {
    payload = JSON.parse(await response.text());
  } catch (error) {
    throw new DiagnosticCodeLoadError(
      "incompatible",
      "The diagnostic dictionary is not valid JSON.",
      { cause: error }
    );
  }
  const parsed = platformDiagnosticCodesSchema.safeParse(payload);
  if (!parsed.success) {
    throw new DiagnosticCodeLoadError(
      "incompatible",
      "The diagnostic dictionary does not match schema version 1.",
      { cause: parsed.error }
    );
  }
  return parsed.data;
}
