import { can } from "@/access-policy";
import { type TaskCaller } from "@/task-caller";
import { normalizeTaskUserId } from "@/task-user-id";
import type { MockUserDirectorySource } from "@/user-directory-source";
import {
  MAX_CREDITS,
  normalizeAdjustment,
  CreditApiError,
  CreditSubmissionUnknown,
  rejectionMessage,
  type CreditAccount,
  type CreditTransaction,
  type CreditOutcome,
  type CreditAdjustment
} from "./model";
import type { CreditSource } from "./source";

interface Account {
  balance: number;
  entries: CreditTransaction[];
  receipts: Map<string, { fingerprint: string; outcome: CreditOutcome }>;
}
/** App-lifetime account facts; admission always uses the shared current directory. */
export class MockCreditSource implements CreditSource {
  private readonly accounts = new Map<string, Account>();
  private readonly lostResponses = new Set<string>();
  constructor(
    private readonly directory: MockUserDirectorySource,
    private readonly now = Date.now
  ) {}
  private admit(declared: TaskCaller, userId: string, write = false) {
    const caller = this.directory.resolveCaller(
      declared,
      undefined,
      write ? "credits.manage" : "credits.view"
    );
    const target = normalizeTaskUserId(userId);
    if (!write && target !== caller.userId && !can(caller, "credits.manage"))
      throw new CreditApiError(403, "积分访问被拒绝");
    const present = this.directory.containsUser(target);
    if (!present && (write || !this.accounts.has(target)))
      throw new CreditApiError(404, "用户或积分账户不存在，当前目录外的账户只能查询");
    return { caller, target, present };
  }
  async account(declared: TaskCaller, userId: string): Promise<CreditAccount> {
    const { target, present } = this.admit(declared, userId);
    const account = this.accounts.get(target);
    return {
      userId: target,
      balance: account?.balance ?? 0,
      latestSequence: account?.entries.length ?? 0,
      updatedAtMillis: account?.entries.at(-1)?.createdAtMillis ?? null,
      userPresent: present
    };
  }
  async transactions(caller: TaskCaller, userId: string, cursor?: string, limit = 50) {
    const { target } = this.admit(caller, userId);
    if (!Number.isInteger(limit) || limit < 1 || limit > 100)
      throw new CreditApiError(400, "每页条数须为 1～100");
    const rows = this.accounts.get(target)?.entries ?? [];
    let before = rows.length;
    if (cursor !== undefined) {
      try {
        const [owner, value, extra] = atob(
          cursor.replace(/-/g, "+").replace(/_/g, "/")
        ).split(":");
        if (
          extra !== undefined ||
          owner !== target ||
          !value ||
          !/^[1-9][0-9]*$/.test(value) ||
          !Number.isSafeInteger(+value)
        )
          throw new Error();
        before = Math.min(+value, before);
      } catch {
        throw new CreditApiError(400, "无效积分明细游标");
      }
    }
    const start = Math.max(0, before - limit);
    return {
      entries: rows
        .slice(start, before)
        .reverse()
        .map((row) => ({ ...row })),
      nextCursor: start ? btoa(`${target}:${start}`).replace(/=/g, "") : null
    };
  }
  async adjustment(
    caller: TaskCaller,
    userId: string,
    requestId: string
  ): Promise<CreditOutcome> {
    const { target } = this.admit(caller, userId);
    normalizeAdjustment({ requestId, delta: 1, reason: "validate" });
    return structuredClone(
      this.accounts.get(target)?.receipts.get(requestId)?.outcome ?? {
        requestId,
        status: "not_observed",
        transaction: null,
        rejectionCode: null,
        balanceAtDecision: null
      }
    );
  }
  dropNextResponse(caller: TaskCaller) {
    const admitted = this.directory.resolveCaller(caller, undefined, "demo.control");
    this.lostResponses.add(admitted.userId);
  }
  async adjust(
    declared: TaskCaller,
    userId: string,
    input: CreditAdjustment
  ): Promise<CreditOutcome> {
    const { caller, target } = this.admit(declared, userId, true);
    const value = normalizeAdjustment(input);
    const fingerprint = JSON.stringify([caller.userId, value.delta, value.reason]);
    const account: Account = this.accounts.get(target) ?? {
      balance: 0,
      entries: [],
      receipts: new Map()
    };
    const prior = account.receipts.get(value.requestId);
    if (prior && prior.fingerprint !== fingerprint)
      throw new CreditApiError(409, "请求标识已被不同内容或操作者使用", 18005);
    let outcome = prior?.outcome;
    if (!outcome) {
      const rejected =
        value.delta < 0 && account.balance < -value.delta
          ? "insufficient_balance"
          : value.delta > 0 && account.balance > MAX_CREDITS - value.delta
            ? "balance_limit"
            : null;
      if (rejected)
        outcome = {
          requestId: value.requestId,
          status: "rejected",
          transaction: null,
          rejectionCode: rejected,
          balanceAtDecision: account.balance
        };
      else {
        const row = {
          sequence: account.entries.length + 1,
          requestId: value.requestId,
          userId: target,
          actorUserId: caller.userId,
          delta: value.delta,
          balanceBefore: account.balance,
          balanceAfter: account.balance + value.delta,
          reason: value.reason,
          createdAtMillis: this.now()
        };
        account.entries.push(row);
        account.balance = row.balanceAfter;
        outcome = {
          requestId: value.requestId,
          status: "applied",
          transaction: row,
          rejectionCode: null,
          balanceAtDecision: row.balanceAfter
        };
      }
      account.receipts.set(value.requestId, { fingerprint, outcome });
      this.accounts.set(target, account);
    }
    // All admission, preflight and publication above are synchronous, with no await in the commit boundary.
    if (this.lostResponses.delete(caller.userId)) throw new CreditSubmissionUnknown();
    if (outcome.status === "rejected")
      throw new CreditApiError(
        409,
        rejectionMessage(outcome.rejectionCode),
        outcome.rejectionCode === "insufficient_balance" ? 18004 : 18006
      );
    return structuredClone(outcome);
  }
}
