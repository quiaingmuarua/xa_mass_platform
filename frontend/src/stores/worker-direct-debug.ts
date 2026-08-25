import { reactive } from "vue";
import { defineStore } from "pinia";

import {
  presentWorkerDirectCallError,
  workerDirectCallConfigurationError,
  type WorkerDirectCallErrorPresentation
} from "@/worker-direct-call/errors";
import { validateWorkerDirectCallRequest } from "@/worker-direct-call/model";
import type {
  WorkerDirectCallClient,
  WorkerDirectCallRequest,
  WorkerDirectCallResult
} from "@/worker-direct-call/types";
import type { RuntimeDataSourceMode } from "@/runtime-viewer/types";

export const DIRECT_DEBUG_HISTORY_LIMIT = 20;

export type DirectDebugHistoryState = "sending" | "completed" | "failed";

/** Browser-memory diagnostic history. This is not a Server or Runtime resource. */
export interface DirectDebugHistoryItem {
  localId: string;
  workerId: string;
  eventName: string;
  payloadText: string;
  sentAt: string;
  state: DirectDebugHistoryState;
  response?: WorkerDirectCallResult;
  safeError?: WorkerDirectCallErrorPresentation;
  completedAt?: string;
}

export function createWorkerDirectDebugStore(
  client: WorkerDirectCallClient,
  mode: RuntimeDataSourceMode,
  now: () => Date = () => new Date()
) {
  return defineStore("workerDirectDebug", () => {
    const histories = reactive<Record<string, DirectDebugHistoryItem[]>>({});
    let localSequence = 0;

    function history(workerId: string): DirectDebugHistoryItem[] {
      return histories[workerId] ?? [];
    }

    function isCalling(workerId: string): boolean {
      return history(workerId).some((item) => item.state === "sending");
    }

    async function send(
      request: WorkerDirectCallRequest
    ): Promise<DirectDebugHistoryItem> {
      if (mode !== "api") {
        throw workerDirectCallConfigurationError(
          "Direct Call is disabled while the Runtime Viewer uses Mock data."
        );
      }
      if (isCalling(request.workerId)) {
        throw workerDirectCallConfigurationError(
          "This Worker already has a Direct Call in progress."
        );
      }

      const validated = validateWorkerDirectCallRequest(request);
      const sentAt = now().toISOString();
      const created: DirectDebugHistoryItem = {
        localId: `direct-debug-${sentAt}-${++localSequence}`,
        workerId: validated.workerId,
        eventName: validated.eventName,
        payloadText: validated.payloadText,
        sentAt,
        state: "sending"
      };
      if (histories[validated.workerId] === undefined) {
        histories[validated.workerId] = [];
      }
      const items = histories[validated.workerId]!;
      items.push(created);
      const item = items.at(-1)!;
      trimCompleted(items);

      try {
        item.response = await client.callWorker(validated);
        item.state = "completed";
      } catch (cause) {
        item.safeError = presentWorkerDirectCallError(cause);
        item.state = "failed";
      } finally {
        item.completedAt = now().toISOString();
        trimCompleted(items);
      }
      return item;
    }

    function clear(workerId: string): boolean {
      if (isCalling(workerId)) {
        return false;
      }
      delete histories[workerId];
      return true;
    }

    function trimCompleted(items: DirectDebugHistoryItem[]): void {
      while (items.length > DIRECT_DEBUG_HISTORY_LIMIT) {
        const removableIndex = items.findIndex((item) => item.state !== "sending");
        if (removableIndex < 0) return;
        items.splice(removableIndex, 1);
      }
    }

    return {
      histories,
      history,
      isCalling,
      send,
      clear
    };
  })();
}

export type WorkerDirectDebugStore = ReturnType<typeof createWorkerDirectDebugStore>;
