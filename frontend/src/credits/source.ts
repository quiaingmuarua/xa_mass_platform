import type { TaskCaller } from "@/task-caller";
import type { InjectionKey } from "vue";
import { callerHeaders, requireCaller } from "@/task-caller";
import { normalizeTaskUserId } from "@/task-user-id";
import {
  accountSchema,
  outcomeSchema,
  pageSchema,
  normalizeAdjustment,
  CreditApiError,
  CreditSubmissionUnknown,
  type CreditAccount,
  type CreditPage,
  type CreditOutcome,
  type CreditAdjustment
} from "./model";

export interface CreditSource {
  account(
    caller: TaskCaller,
    userId: string,
    signal?: AbortSignal
  ): Promise<CreditAccount>;
  transactions(
    caller: TaskCaller,
    userId: string,
    cursor?: string,
    limit?: number,
    signal?: AbortSignal
  ): Promise<CreditPage>;
  adjustment(
    caller: TaskCaller,
    userId: string,
    requestId: string,
    signal?: AbortSignal
  ): Promise<CreditOutcome>;
  adjust(
    caller: TaskCaller,
    userId: string,
    input: CreditAdjustment
  ): Promise<CreditOutcome>;
}
export const creditSourceKey: InjectionKey<CreditSource> = Symbol("credit-source");
export class ApiCreditSource implements CreditSource {
  constructor(private readonly baseUrl = "/api") {}
  private async request(
    caller: TaskCaller,
    userId: string,
    suffix = "",
    input?: CreditAdjustment,
    signal?: AbortSignal
  ): Promise<unknown> {
    const headers = callerHeaders(requireCaller(caller));
    const path = `${this.baseUrl}/v1/users/${encodeURIComponent(normalizeTaskUserId(userId))}/credits${suffix}`;
    let response: Response;
    try {
      response = await fetch(path, {
        headers: {
          ...headers,
          ...(input ? { "Content-Type": "application/json" } : {})
        },
        method: input ? "POST" : "GET",
        ...(input ? { body: JSON.stringify(input) } : {}),
        signal: signal
          ? AbortSignal.any([signal, AbortSignal.timeout(10000)])
          : AbortSignal.timeout(10000)
      });
    } catch (error) {
      if (input) throw new CreditSubmissionUnknown();
      throw error;
    }
    if (!response.ok) {
      if (input && ![400, 403, 404, 409].includes(response.status))
        throw new CreditSubmissionUnknown();
      let code: number | undefined;
      try {
        code = (await response.json()).code;
      } catch {
        /* The HTTP rejection remains definite. */
      }
      const message =
        response.status === 403
          ? "积分访问被拒绝"
          : response.status === 404
            ? "用户或积分账户不存在，当前目录外的账户只能查询"
            : code === 18004
              ? "余额不足，本次调账已拒绝。"
              : code === 18005
                ? "请求标识已被不同内容或操作者使用，请核对原请求。"
                : code === 18006
                  ? "积分超出安全整数范围，本次调账已拒绝。"
                  : `积分请求失败 (${response.status})`;
      throw new CreditApiError(response.status, message, code);
    }
    try {
      return await response.json();
    } catch (error) {
      if (input) throw new CreditSubmissionUnknown();
      throw error;
    }
  }
  async account(caller: TaskCaller, userId: string, signal?: AbortSignal) {
    return accountSchema.parse(
      await this.request(caller, userId, "", undefined, signal)
    );
  }
  async transactions(
    caller: TaskCaller,
    userId: string,
    cursor?: string,
    limit = 50,
    signal?: AbortSignal
  ) {
    const query = new URLSearchParams({ limit: String(limit) });
    if (cursor) query.set("cursor", cursor);
    return pageSchema.parse(
      await this.request(caller, userId, "/transactions?" + query, undefined, signal)
    );
  }
  async adjustment(
    caller: TaskCaller,
    userId: string,
    requestId: string,
    signal?: AbortSignal
  ) {
    return outcomeSchema.parse(
      await this.request(
        caller,
        userId,
        "/adjustments/" + encodeURIComponent(requestId),
        undefined,
        signal
      )
    );
  }
  async adjust(caller: TaskCaller, userId: string, input: CreditAdjustment) {
    const value = await this.request(
      caller,
      userId,
      "/adjustments",
      normalizeAdjustment(input)
    );
    const parsed = outcomeSchema.safeParse(value);
    if (
      !parsed.success ||
      parsed.data.requestId !== input.requestId ||
      parsed.data.status !== "applied"
    )
      throw new CreditSubmissionUnknown();
    return parsed.data;
  }
}
