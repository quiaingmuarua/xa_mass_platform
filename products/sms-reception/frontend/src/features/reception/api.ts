export interface Listener {
    id: string;
    requestId: string;
    applicationId: string;
    country: string;
    status: string;
    phone?: string;
    createdAt: number;
    startedAt?: number;
    expiresAt?: number;
    sms?: {
        text: string;
        code?: string;
        templateId: string;
        receivedAt: number;
    };
    reason?: string;
}
export interface Page {
    total: number;
    items: Listener[];
}
export interface Metrics {
    runId: string;
    requests: number;
    activeListenersObserved: number;
    statuses: Record<string, number>;
    submissionUnknown: number;
    observationErrors: number;
    commandQueue: number;
    establishmentLatencyMillis: { count: number; p95: number; p99: number };
    smsObservationLatencyMillis: { count: number; p95: number; p99: number };
}
export interface Catalog {
    runId: string;
    version: string;
    applications: {
        id: string;
        name: string;
        templates: { id: string; priority: number }[];
    }[];
}
export const statusLabels: Record<string, string> = {
    ESTABLISHING: "正在取号",
    LISTENING: "监听中",
    RECEIVED: "已收到短信",
    CANCELLING: "取消中",
    CANCELLED: "已取消",
    EXPIRED: "已到期",
    UNCONFIRMED: "结果未确认",
    REJECTED: "建立被拒绝",
    INTERRUPTED: "运行已终止"
};
export function canCancel(status: string) {
    return ["ESTABLISHING", "LISTENING"].includes(status);
}
export function waitingMessage(listener: Listener) {
    if (listener.reason) return listener.reason;
    return (
        (
            {
                ESTABLISHING: "正在建立监听，成功后展示号码和有效时间。",
                LISTENING: "正在等待第一条匹配短信。",
                CANCELLING: "取消请求已记录，正在等待号码确认。",
                CANCELLED: "监听已明确取消，不再接收短信。",
                EXPIRED: "监听时间已结束，没有匹配到短信。",
                UNCONFIRMED: "观察截止前未确认结果，不能判定远端接码成功。",
                INTERRUPTED: "本次运行已终止。"
            } as Record<string, string>
        )[listener.status] ?? "等待业务结果。"
    );
}
export async function api<T>(
    path: string,
    body?: unknown,
    signal?: AbortSignal
): Promise<T> {
    const response = await fetch(path, {
        method: body === undefined ? "GET" : "POST",
        headers: { "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal
    });
    if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.message || `请求失败 (${response.status})`);
    }
    return response.json() as Promise<T>;
}
