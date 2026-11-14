import { computed, ref, shallowRef, type InjectionKey } from "vue";
import { can } from "@/access-policy";
import { requireCaller, TaskApiError, type TaskCaller } from "@/task-caller";
import {
  ConnectionControlUnknown,
  normalizeConnectionChange,
  type WorkerConnectionChange,
  type WorkerConnectionControl,
  type WorkerConnectionControlSource
} from "./connection-control";

export function createWorkerConnectionControlState(
  source: WorkerConnectionControlSource,
  declared: TaskCaller,
  group: string,
  worker: string
) {
  const caller = requireCaller(declared);
  const snapshot = shallowRef<WorkerConnectionControl>();
  const pending = shallowRef<Readonly<WorkerConnectionChange>>();
  const pendingStatus = ref<"unconfirmed" | "conflict" | "superseded" | "rejected">(
    "unconfirmed"
  );
  const stale = ref(false),
    loading = ref(false),
    busy = ref(false);
  const error = ref(""),
    notice = ref("");
  let disposed = false;
  let read: AbortController | undefined;
  const permitted = caller.role === "admin" && can(caller, "runtime.view");
  const readable = computed(
    () => permitted && !disposed && !loading.value && !busy.value
  );
  const fresh = computed(() => !!snapshot.value && !stale.value && !loading.value);
  const canWrite = computed(
    () => readable.value && fresh.value && snapshot.value?.supported && !pending.value
  );
  const canRetry = computed(
    () =>
      readable.value &&
      fresh.value &&
      snapshot.value?.supported &&
      !!pending.value &&
      pendingStatus.value === "unconfirmed"
  );
  const canDiscard = computed(
    () =>
      readable.value &&
      fresh.value &&
      !!pending.value &&
      pendingStatus.value !== "unconfirmed"
  );
  const message = (cause: unknown) =>
    cause instanceof Error ? cause.message : "接入控制读取失败";
  const sameInput = (value: WorkerConnectionControl, input: WorkerConnectionChange) =>
    value.requestId === input.requestId &&
    value.cooldownUntilMillis === input.cooldownUntilMillis &&
    value.reason === input.reason;
  const checked = (value: WorkerConnectionControl) => {
    if (value.workerGroupId !== group || value.workerId !== worker)
      throw new Error("Worker 接入控制身份不一致");
    return value;
  };

  async function refresh() {
    if (!permitted || disposed || busy.value) return;
    read?.abort();
    const controller = new AbortController();
    read = controller;
    loading.value = true;
    error.value = "";
    try {
      const current = checked(
        await source.read(caller, group, worker, controller.signal)
      );
      if (disposed || read !== controller) return;
      snapshot.value = current;
      stale.value = false;
      const input = pending.value;
      if (input) {
        if (sameInput(current, input)) {
          if (input.cooldownUntilMillis === null) {
            pending.value = undefined;
            notice.value =
              "原解除请求已保存。只恢复接入资格，没有解除调度暂停或改变 Pool 规则。";
          } else {
            pendingStatus.value = "unconfirmed";
            notice.value =
              "原冷却已保存；本次断连结果仍待核对。可显式重试原请求，截止时间不会延长。实际在线状态请单独刷新观察。";
          }
        } else if (
          current.requestId !== input.requestId &&
          current.revision !== input.expectedRevision
        ) {
          pendingStatus.value = "superseded";
          notice.value =
            "已读取更新的控制状态；保留旧请求，禁止重试覆盖该修订。旧请求是否曾生效仍未知；可明确放弃本地核对后重新编辑。";
        } else if (current.requestId === input.requestId) {
          pendingStatus.value = "conflict";
          notice.value =
            "同一请求身份对应不同内容，保留原请求供核对，不能覆盖 Server 当前状态。";
        } else {
          notice.value =
            "未确认原请求的最终结果，继续保留原身份和内容；可显式重试，不能据此认定未执行。";
        }
      }
    } catch (cause) {
      if (!disposed && read === controller) {
        stale.value = true;
        error.value = message(cause);
      }
    } finally {
      if (!disposed && read === controller) loading.value = false;
    }
  }

  async function post(input: Readonly<WorkerConnectionChange>, retry: boolean) {
    pending.value = input;
    if (!retry) pendingStatus.value = "unconfirmed";
    busy.value = true;
    error.value = "";
    notice.value = "";
    try {
      const saved = checked(await source.save(caller, group, worker, input));
      if (!sameInput(saved, input)) throw new ConnectionControlUnknown();
      if (disposed) return;
      snapshot.value = saved;
      stale.value = false;
      pending.value = undefined;
      notice.value =
        input.cooldownUntilMillis === null
          ? "解除已确认。Worker 需重新检查准入；调度暂停和 Pool 规则保持原值。"
          : saved.status === "allowed"
            ? "原冷却已到期，已核对保存状态。本次未要求新的断连；历史断连结果不据此确认。"
            : "冷却保存和路由撤销已确认。实际网络状态需单独刷新；已进入的 Handler 可能继续执行。";
    } catch (cause) {
      if (disposed) return;
      stale.value = true;
      error.value = message(cause);
      if (cause instanceof TaskApiError) {
        if (cause.status === 409) pendingStatus.value = "conflict";
        else if (!retry) pendingStatus.value = "rejected";
      }
    } finally {
      if (!disposed) busy.value = false;
    }
  }

  return {
    snapshot,
    pending,
    pendingStatus,
    stale,
    loading,
    busy,
    error,
    notice,
    permitted,
    canWrite,
    canRetry,
    canDiscard,
    refresh,
    async submit(cooldownUntilMillis: number | null, reason: string) {
      if (disposed || !canWrite.value) return;
      try {
        const input = normalizeConnectionChange({
          requestId: crypto.randomUUID(),
          expectedRevision: snapshot.value!.revision,
          cooldownUntilMillis,
          reason
        });
        if (
          input.cooldownUntilMillis !== null &&
          input.cooldownUntilMillis <= Date.now()
        )
          throw new Error("请选择未来的冷却截止时间；Server 会再次校验");
        await post(input, false);
      } catch (cause) {
        if (!disposed) error.value = message(cause);
      }
    },
    retry: () =>
      !disposed && canRetry.value && pending.value
        ? post(pending.value, true)
        : Promise.resolve(),
    discardPending() {
      if (disposed || !canDiscard.value) return;
      pending.value = undefined;
      error.value = "";
      notice.value =
        "已明确放弃本地核对。此操作不撤销 Server 上可能发生的修改或断连；新的修改将基于已读取修订。";
    },
    dispose() {
      disposed = true;
      read?.abort();
      stale.value = true;
    }
  };
}

export function createWorkerConnectionControlSession(
  source: WorkerConnectionControlSource,
  caller: TaskCaller
) {
  let disposed = false;
  const states = new Map<
    string,
    ReturnType<typeof createWorkerConnectionControlState>
  >();
  return {
    state(group: string, worker: string) {
      if (disposed) throw new Error("接入控制会话已结束");
      const key = JSON.stringify([group, worker]);
      let entry = states.get(key);
      if (!entry) {
        entry = createWorkerConnectionControlState(source, caller, group, worker);
        states.set(key, entry);
      }
      return entry;
    },
    dispose() {
      disposed = true;
      for (const entry of states.values()) entry.dispose();
      states.clear();
    }
  };
}
export const workerConnectionControlKey: InjectionKey<
  ReturnType<typeof createWorkerConnectionControlSession>
> = Symbol("worker-connection-control");
