import { z } from "zod";

export class MessageApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly taskId?: string
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  body?: unknown,
  signal?: AbortSignal
): Promise<T> {
  const response = await fetch(`/api/v1/messages${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal
  });
  if (!response.ok) {
    const error: unknown = await response.json().catch(() => null);
    throw new MessageApiError(
      response.status,
      error &&
      typeof error === "object" &&
      "message" in error &&
      typeof error.message === "string"
        ? error.message
        : `请求失败 (${response.status})`,
      error &&
      typeof error === "object" &&
      "taskId" in error &&
      typeof error.taskId === "string"
        ? error.taskId
        : undefined
    );
  }
  return response.json() as Promise<T>;
}
const catalogSchema = z.object({
  projectId: z.string().min(1),
  runId: z.string().min(1),
  version: z.string().min(1),
  countries: z
    .array(
      z.object({ id: z.enum(["CN", "US", "GB"]), workerGroupId: z.string().min(1) })
    )
    .length(3)
    .refine((countries) => new Set(countries.map((country) => country.id)).size === 3),
  limits: z.object({
    tasks: z.number().int().positive(),
    items: z.number().int().positive(),
    recipientsPerTask: z.number().int().positive()
  })
});
export type Catalog = z.infer<typeof catalogSchema>;
export async function loadCatalog(signal?: AbortSignal): Promise<Catalog> {
  return catalogSchema.parse(await api<unknown>("/catalog", undefined, signal));
}
