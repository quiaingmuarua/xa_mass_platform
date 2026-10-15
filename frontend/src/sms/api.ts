import { type TaskCaller, callerHeaders } from "@/task-caller";
import { z } from "zod";

export interface Reception {
  messageId: string;
  revision?: number;
  status: "NOT_OBSERVED" | "WAITING" | "RECEIVED" | "EXPIRED" | "FAILED";
  applicationId?: string;
  country?: string;
  phoneNumber?: string;
  startedAt?: number;
  leaseUntil?: number;
  leaseActive?: boolean;
  sms?: {
    smsId: string;
    text: string;
    code?: string;
    templateId: string;
    receivedAt: number;
  };
  reason?: string;
}
export interface Metrics {
  runId: string;
  requests: number;
  queries: number;
  errors: number;
  acquisitionLatencyMillis: { count: number; p95: number; p99: number };
  queryLatencyMillis: { count: number; p95: number; p99: number };
  projection: {
    accepted: number;
    dropped: number;
    failures: number;
    recorded: number;
    queueBatches: number;
    meanQueueMillis: number;
  };
}
export interface Catalog {
  runId: string;
  version: string;
  countries?: string[];
  applications: {
    id: string;
    name: string;
    templates: { id: string; priority: number }[];
  }[];
}
const catalogSchema = z.object({
  runId: z.string().min(1),
  version: z.string().min(1),
  countries: z.array(z.string()).optional(),
  applications: z
    .array(
      z.object({
        id: z.string().min(1),
        name: z.string().min(1),
        templates: z
          .array(z.object({ id: z.string().min(1), priority: z.number().finite() }))
          .min(1)
      })
    )
    .min(1)
});
export class SmsApiError extends Error {
  constructor(
    public readonly status: number,
    message: string
  ) {
    super(status === 403 ? `${message}，请刷新页面或退出后重新登录。` : message);
  }
}
export async function loadCatalog(signal?: AbortSignal): Promise<Catalog> {
  return catalogSchema.parse(
    await readResponse(await fetch("/api/v1/sms/catalog", { signal }))
  );
}
export const statusLabels: Record<Reception["status"], string> = {
  NOT_OBSERVED: "尚未取得结果",
  WAITING: "等待短信",
  RECEIVED: "已收到短信",
  EXPIRED: "租期已结束",
  FAILED: "取号失败"
};
export function waitingMessage(item: Reception) {
  if (item.reason) return item.reason;
  if (item.status === "NOT_OBSERVED")
    return "本次未取得结果，可继续使用此 messageId 查询；已领取结果受服务端本地缓存期限限制。";
  if (item.leaseActive === false) return "租期已结束，最后收到的短信仅作短期保留。";
  if (item.status === "FAILED") return "本次取号失败，请重新申请。";
  return "租期内持续接收短信，收到后显示最新一条。";
}
/** Keep received evidence when the server has no current value or an older revision. */
export function mergeReception(
  previous: Reception | undefined,
  next: Reception
): Reception {
  if (
    previous &&
    (next.status === "NOT_OBSERVED" ||
      (previous.revision !== undefined && next.status === "FAILED") ||
      (previous.revision !== undefined &&
        next.revision !== undefined &&
        previous.revision > next.revision))
  ) {
    if (previous.leaseUntil !== undefined && Date.now() >= previous.leaseUntil)
      return {
        ...previous,
        leaseActive: false,
        status: previous.sms ? "RECEIVED" : "EXPIRED"
      };
    return previous;
  }
  return next;
}

export async function api<T>(
  caller: TaskCaller,
  path: string,
  body?: unknown,
  signal?: AbortSignal
): Promise<T> {
  const response = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      ...callerHeaders(caller),
      "Content-Type": "application/json"
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal
  });
  return readResponse(response) as Promise<T>;
}
async function readResponse(response: Response): Promise<unknown> {
  if (!response.ok) {
    const error: unknown = await response.json().catch(() => null);
    const message =
      error &&
      typeof error === "object" &&
      "message" in error &&
      typeof error.message === "string"
        ? error.message
        : "请求失败 (" + response.status + ")";
    throw new SmsApiError(response.status, message);
  }
  return response.json();
}
