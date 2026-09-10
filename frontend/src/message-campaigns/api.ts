import { z } from "zod";

export class MessageApiError extends Error {
  constructor(
    public readonly status: number,
    message: string
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
        : `请求失败 (${response.status})`
    );
  }
  return response.json() as Promise<T>;
}
const catalogSchema = z.object({
  runId: z.string().min(1),
  version: z.string().min(1),
  countries: z
    .array(
      z.object({ id: z.enum(["CN", "US", "GB"]), workerGroupId: z.string().min(1) })
    )
    .length(3)
    .refine((countries) => new Set(countries.map((country) => country.id)).size === 3),
  limits: z.object({
    campaigns: z.number().int().positive(),
    messages: z.number().int().positive(),
    recipientsPerCampaign: z.number().int().positive()
  })
});
export type Catalog = z.infer<typeof catalogSchema>;
export async function loadCatalog(signal?: AbortSignal): Promise<Catalog> {
  return catalogSchema.parse(await api<unknown>("/catalog", undefined, signal));
}
export function confirmedCampaign(value: unknown, requestId: string): { id: string } {
  return z
    .object({
      id: z.string().min(1),
      requestId: z.literal(requestId),
      submission: z.enum(["SUBMITTING", "SUBMITTED", "SUBMISSION_UNCONFIRMED"])
    })
    .parse(value);
}
export interface Campaign {
  id: string;
  requestId: string;
  name: string;
  country: string;
  body: string;
  senderPhone: string | null;
  taskId: string | null;
  submission: string;
  messageCount: number;
  statuses: Record<string, number>;
}
export interface Message {
  id: string;
  recipientId: string;
  status: string;
  phone?: string;
  workerId?: string;
  reply?: string;
  lastObservedAt: number;
}
export interface Page<T> {
  runId: string;
  total: number;
  items: T[];
}
export interface Metrics {
  runId: string;
  campaigns: number;
  messages: number;
  statuses: Record<string, number>;
  submissionUnknown: number;
  observationErrors: number;
  submissionQueue: number;
  sendingLatencyMillis: { count: number; p95: number; p99: number };
  receiptObservationLatencyMillis: { count: number; p95: number; p99: number };
}
export const labels: Record<string, string> = {
  SENT: "已发送，回执待观察",
  DELIVERED: "已送达",
  READ: "已读",
  REPLIED: "已回复",
  NOT_OBSERVED: "尚未观察到发送结果",
  EXECUTION_FAILED: "已观察到执行失败",
  SUBMITTING: "正在提交",
  SUBMITTED: "已提交",
  SUBMISSION_UNCONFIRMED: "提交结果未确认"
};
