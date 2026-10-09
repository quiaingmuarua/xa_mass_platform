import { z } from "zod";

export class MessageApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly taskId?: string,
    public readonly confirmedAddedCount?: number,
    public readonly existingCount?: number
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  body?: unknown,
  signal?: AbortSignal,
  encoding: "json" | "text" = "json"
): Promise<T> {
  const response = await fetch(`/api/v1/messages${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      "Content-Type":
        encoding === "text" ? "text/plain;charset=UTF-8" : "application/json"
    },
    body:
      body === undefined
        ? undefined
        : encoding === "text"
          ? String(body)
          : JSON.stringify(body),
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
        : undefined,
      error &&
      typeof error === "object" &&
      "confirmedAddedCount" in error &&
      typeof error.confirmedAddedCount === "number"
        ? error.confirmedAddedCount
        : undefined,
      error &&
      typeof error === "object" &&
      "existingCount" in error &&
      typeof error.existingCount === "number"
        ? error.existingCount
        : undefined
    );
  }
  return response.json() as Promise<T>;
}
const catalogSchema = z.object({
  projectId: z.string().min(1),
  version: z.string().min(1),
  applications: z
    .array(
      z.object({
        id: z.string().min(1),
        label: z.string().min(1),
        workerGroupId: z.string().min(1)
      })
    )
    .min(1)
    .refine(
      (apps) =>
        new Set(apps.map((app) => app.id)).size === apps.length &&
        new Set(apps.map((app) => app.workerGroupId)).size === apps.length
    ),
  countries: z
    .array(z.object({ id: z.enum(["CN", "US", "GB"]) }))
    .length(3)
    .refine((countries) => new Set(countries.map((country) => country.id)).size === 3),
  limits: z.object({
    recipientsPerImport: z.number().int().positive(),
    importFileBytes: z.number().int().positive()
  })
});
export type Catalog = z.infer<typeof catalogSchema>;
export async function loadCatalog(signal?: AbortSignal): Promise<Catalog> {
  return catalogSchema.parse(await api<unknown>("/catalog", undefined, signal));
}
