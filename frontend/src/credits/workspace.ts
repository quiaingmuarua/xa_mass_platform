import { ref, shallowRef } from "vue";
import { requireCaller, TaskApiError, type TaskCaller } from "@/task-caller";
import {
  normalizeAdjustment,
  CreditSubmissionUnknown,
  rejectionMessage,
  type CreditAccount,
  type CreditAdjustment,
  type CreditOutcome,
  type CreditTransaction
} from "./model";
import type { CreditSource } from "./source";

export function createCreditWorkspace(
  source: CreditSource,
  declared: TaskCaller,
  userId: string,
  isCurrent = () => true
) {
  const caller = requireCaller(declared);
  const account = shallowRef<CreditAccount>();
  const entries = shallowRef<CreditTransaction[]>([]);
  const nextCursor = ref<string | null>(null);
  const pending = shallowRef<CreditAdjustment>();
  const confirmed = shallowRef<CreditOutcome>();
  const loading = ref(false),
    busy = ref(false),
    error = ref(""),
    notice = ref("");
  let disposed = false;
  let read: AbortController | undefined;
  const alive = () => !disposed && isCurrent();
  const message = (cause: unknown) =>
    cause instanceof Error ? cause.message : "无法读取积分账户";
  async function load(cursor?: string) {
    if (!alive()) return;
    read?.abort();
    read = new AbortController();
    const request = read;
    loading.value = true;
    error.value = "";
    try {
      const [current, page] = await Promise.all([
        source.account(caller, userId, request.signal),
        source.transactions(caller, userId, cursor, 50, request.signal)
      ]);
      if (!alive() || read !== request) return;
      account.value = current;
      entries.value = page.entries;
      nextCursor.value = page.nextCursor;
    } catch (cause) {
      if (alive() && read === request) {
        error.value = message(cause);
        account.value = undefined;
        entries.value = [];
        nextCursor.value = null;
      }
    } finally {
      if (alive() && read === request) loading.value = false;
    }
  }
  async function accept(outcome: CreditOutcome) {
    if (!alive()) return;
    if (outcome.requestId !== pending.value?.requestId)
      throw new CreditSubmissionUnknown();
    if (outcome.status === "not_observed") {
      notice.value =
        "尚未观察到原请求的回执，不能据此认定失败。可以再次核对或使用原请求重试。";
      return;
    }
    pending.value = undefined;
    confirmed.value = outcome;
    notice.value =
      outcome.status === "applied"
        ? `调账已确认，流水 #${outcome.transaction.sequence}，变更后余额 ${outcome.transaction.balanceAfter}。`
        : rejectionMessage(outcome.rejectionCode);
    await load();
  }
  async function post(input: CreditAdjustment, retry: boolean) {
    if (!alive() || busy.value) return;
    pending.value = input;
    busy.value = true;
    error.value = "";
    notice.value = "";
    try {
      await accept(await source.adjust(caller, userId, input));
    } catch (cause) {
      if (!alive()) return;
      error.value = message(cause);
      // Once an outcome is unknown, a later authorization rejection cannot disprove the earlier commit.
      if (
        cause instanceof TaskApiError &&
        (!retry || [400, 409].includes(cause.status))
      )
        pending.value = undefined;
    } finally {
      if (alive()) busy.value = false;
    }
  }
  return {
    account,
    entries,
    nextCursor,
    pending,
    confirmed,
    loading,
    busy,
    error,
    notice,
    load,
    nextPage: () =>
      nextCursor.value && !loading.value ? load(nextCursor.value) : Promise.resolve(),
    async submit(delta: number, reason: string) {
      if (!alive() || busy.value || pending.value) return;
      try {
        await post(
          normalizeAdjustment({ requestId: crypto.randomUUID(), delta, reason }),
          false
        );
      } catch (cause) {
        if (alive()) error.value = message(cause);
      }
    },
    retry: () => (pending.value ? post(pending.value, true) : Promise.resolve()),
    async reconcile() {
      if (!alive() || busy.value || !pending.value) return;
      busy.value = true;
      error.value = "";
      notice.value = "";
      try {
        await accept(await source.adjustment(caller, userId, pending.value.requestId));
      } catch (cause) {
        if (alive()) error.value = message(cause);
      } finally {
        if (alive()) busy.value = false;
      }
    },
    dispose() {
      disposed = true;
      read?.abort();
      pending.value = undefined;
      confirmed.value = undefined;
      account.value = undefined;
      entries.value = [];
      nextCursor.value = null;
      error.value = "";
      notice.value = "";
    }
  };
}
