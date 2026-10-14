import { type TaskCaller, captureTaskCaller } from "@/task-caller";
import { reactive } from "vue";
import { defineStore } from "pinia";

import type { RuntimeDataSourceMode } from "@/runtime-viewer/types";
import {
  presentTaskCallDebugError,
  TaskCallDebugError,
  taskCallDebugConfigurationError,
  type TaskCallDebugErrorPresentation
} from "@/task-call-debug/errors";
import {
  DEFAULT_TASK_CALL_TIMEOUT_MILLIS,
  validateTaskCallDebugDraft
} from "@/task-call-debug/model";
import type { TaskCallDebugClient, TaskCallDebugDraft } from "@/task-call-debug/types";

export const TASK_CALL_DEBUG_HISTORY_LIMIT = 20;

export type TaskCallDebugHistoryState =
  | "sending"
  | "succeeded"
  | "not_observed"
  | "unconfirmed"
  | "request_rejected"
  | "failed";

/** Browser-memory diagnostic evidence. This is not a Task or Server resource. */
export interface TaskCallDebugHistoryItem {
  caller: TaskCaller;
  localId: string;
  taskId: string;
  workerGroupId: string;
  messageId: string;
  eventName: string;
  payloadText: string;
  workerSelectorText: string;
  sentAt: string;
  state: TaskCallDebugHistoryState;
  opaqueResultPayload?: string;
  safeError?: TaskCallDebugErrorPresentation;
  resultLoadError?: TaskCallDebugErrorPresentation;
  completedAt?: string;
  lastCheckedAt?: string;
  checkingResult?: boolean;
}

export function createTaskCallDebugStore(
  client: TaskCallDebugClient,
  mode: RuntimeDataSourceMode,
  now: () => Date = () => new Date()
) {
  return defineStore("taskCallDebug", () => {
    const histories = reactive<Record<string, TaskCallDebugHistoryItem[]>>({});
    const drafts = reactive<
      Record<
        string,
        {
          eventName: string;
          payloadText: string;
          executorName: string;
          inputText: string;
          waitTimeoutMillis: number;
        }
      >
    >({});
    function draft(taskId: string) {
      drafts[taskId] ??= {
        eventName: "",
        payloadText: "{}",
        executorName: "",
        inputText: "{}",
        waitTimeoutMillis: DEFAULT_TASK_CALL_TIMEOUT_MILLIS
      };
      return drafts[taskId]!;
    }
    let lastMessageMillis = 0;
    let messageSequence = 0;

    function history(taskId: string): TaskCallDebugHistoryItem[] {
      return histories[taskId] ?? [];
    }

    function isBusy(taskId: string): boolean {
      return history(taskId).some(
        (item) => item.state === "sending" || item.checkingResult === true
      );
    }

    async function send(draft: TaskCallDebugDraft): Promise<TaskCallDebugHistoryItem> {
      requireApiMode();
      const validated = validateTaskCallDebugDraft(draft);
      if (isBusy(validated.taskId)) {
        throw taskCallDebugConfigurationError(
          "This Task already has a Task Call operation in progress."
        );
      }
      const sentAtValue = now();
      const messageId = nextMessageId(sentAtValue.getTime());
      const created: TaskCallDebugHistoryItem = {
        caller: captureTaskCaller(),
        localId: `task-call-debug-${messageId}`,
        taskId: validated.taskId,
        workerGroupId: validated.workerGroupId,
        messageId,
        eventName: validated.eventName,
        payloadText: validated.payloadText,
        workerSelectorText: validated.workerSelectorText,
        sentAt: sentAtValue.toISOString(),
        state: "sending"
      };
      if (histories[validated.taskId] === undefined) {
        histories[validated.taskId] = [];
      }
      const items = histories[validated.taskId]!;
      items.push(created);
      const item = items.at(-1)!;
      trimHistory(items);

      try {
        const outcome = await client.callTask({
          caller: item.caller,
          taskId: validated.taskId,
          messageId,
          eventName: validated.eventName,
          payload: validated.payload,
          workerSelector: validated.workerSelector,
          waitTimeoutMillis: validated.waitTimeoutMillis
        });
        item.state = outcome.status;
        if (outcome.status === "succeeded") {
          item.opaqueResultPayload = outcome.opaqueResultPayload;
        }
      } catch (cause) {
        item.state =
          cause instanceof TaskCallDebugError &&
          cause.kind === "http" &&
          cause.status !== undefined &&
          cause.status >= 400 &&
          cause.status < 500 &&
          cause.status !== 408
            ? "request_rejected"
            : "unconfirmed";
        item.safeError = presentTaskCallDebugError(cause);
      } finally {
        item.completedAt = now().toISOString();
        trimHistory(items);
      }
      return item;
    }

    async function loadResult(
      taskId: string,
      localId: string
    ): Promise<TaskCallDebugHistoryItem> {
      requireApiMode();
      if (isBusy(taskId)) {
        throw taskCallDebugConfigurationError(
          "This Task already has a Task Call operation in progress."
        );
      }
      const item = history(taskId).find((candidate) => candidate.localId === localId);
      if (item === undefined || !["not_observed", "unconfirmed"].includes(item.state)) {
        throw taskCallDebugConfigurationError(
          "Only a not_observed or unconfirmed Task Call can load its Result."
        );
      }
      item.checkingResult = true;
      item.resultLoadError = undefined;
      try {
        const outcome = await client.loadResult(
          item.caller,
          item.taskId,
          item.messageId
        );
        item.lastCheckedAt = now().toISOString();
        if (outcome.status === "succeeded") {
          item.opaqueResultPayload = outcome.opaqueResultPayload;
          item.state = "succeeded";
          item.safeError = undefined;
          item.completedAt = item.lastCheckedAt;
        } else if (outcome.status === "failed") {
          item.state = "failed";
          item.safeError = undefined;
          item.completedAt = item.lastCheckedAt;
        }
      } catch (cause) {
        item.lastCheckedAt = now().toISOString();
        item.resultLoadError = presentTaskCallDebugError(cause);
      } finally {
        item.checkingResult = false;
      }
      return item;
    }

    function clear(taskId: string): boolean {
      if (isBusy(taskId)) return false;
      delete histories[taskId];
      return true;
    }

    function requireApiMode(): void {
      if (mode !== "api") {
        throw taskCallDebugConfigurationError(
          "Task Call is disabled while the Runtime Viewer uses Mock data."
        );
      }
    }

    function nextMessageId(currentMillis: number): string {
      const monotonicMillis = Math.max(currentMillis, lastMessageMillis);
      if (monotonicMillis === lastMessageMillis) {
        messageSequence += 1;
      } else {
        lastMessageMillis = monotonicMillis;
        messageSequence = 1;
      }
      return `task-debug-${monotonicMillis}-${messageSequence}`;
    }

    function trimHistory(items: TaskCallDebugHistoryItem[]): void {
      while (items.length > TASK_CALL_DEBUG_HISTORY_LIMIT) {
        const removableIndex = items.findIndex(
          (item) => item.state !== "sending" && item.checkingResult !== true
        );
        if (removableIndex < 0) return;
        items.splice(removableIndex, 1);
      }
    }

    return {
      histories,
      draft,
      history,
      isBusy,
      send,
      loadResult,
      clear
    };
  })();
}

export type TaskCallDebugStore = ReturnType<typeof createTaskCallDebugStore>;
