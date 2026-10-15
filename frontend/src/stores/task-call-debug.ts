import type { TaskCaller } from "@/task-caller";
import { captureTaskCaller } from "@/console-identity";
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
  EMPTY_RPC_REQUEST,
  parseRpcRequest,
  rpcMessageIds
} from "@/task-call-debug/model";
import type {
  TaskCallDebugClient,
  RpcCallRequest,
  TaskCallDebugResults
} from "@/task-call-debug/types";

export const TASK_CALL_DEBUG_HISTORY_LIMIT = 20;
/** Browser-session evidence, never an execution-state owner. */
export interface TaskCallDebugHistoryItem {
  caller: TaskCaller;
  localId: string;
  taskId: string;
  request?: RpcCallRequest;
  requestText: string;
  messageIds: string[];
  sentAt: string;
  state: "sending" | "observed" | "unconfirmed" | "request_rejected";
  results?: TaskCallDebugResults;
  safeError?: TaskCallDebugErrorPresentation;
  resultLoadError?: TaskCallDebugErrorPresentation;
  readAt?: string;
  checkingResult?: boolean;
}
export function createTaskCallDebugStore(
  client: TaskCallDebugClient,
  mode: RuntimeDataSourceMode,
  now: () => Date = () => new Date()
) {
  return defineStore("taskCallDebug", () => {
    const histories = reactive<Record<string, TaskCallDebugHistoryItem[]>>(
      Object.create(null)
    );
    const drafts = reactive<
      Record<string, { requestText: string; resultIdsText: string }>
    >(Object.create(null));
    function draft(taskId: string) {
      if (mode === "mock" && !histories[taskId]) {
        histories[taskId] = [
          {
            caller: captureTaskCaller(),
            localId: "mock-batch",
            taskId,
            requestText: "Mock 批量结果样例",
            messageIds: ["demo-success", "demo-failure", "demo-waiting"],
            sentAt: "2026-01-01T00:00:00.000Z",
            readAt: "2026-01-01T00:00:00.000Z",
            state: "observed",
            results: {
              "demo-success": {
                status: "succeeded",
                opaqueResultPayload: '{"value":"Mock 原始输出"}'
              },
              "demo-failure": { status: "failed" },
              "demo-waiting": { status: "not_observed" }
            }
          }
        ];
      }
      drafts[taskId] ??= { requestText: EMPTY_RPC_REQUEST, resultIdsText: "" };
      return drafts[taskId]!;
    }
    function history(taskId: string) {
      return histories[taskId] ?? [];
    }
    function isBusy(taskId: string) {
      return history(taskId).some(
        (item) => item.state === "sending" || item.checkingResult
      );
    }
    function admit(taskId: string) {
      if (mode !== "api")
        throw taskCallDebugConfigurationError("Mock 不执行真实 RPC 调用或结果读取。");
      if (!taskId.trim()) throw taskCallDebugConfigurationError("请选择 Task ID。");
      if (isBusy(taskId))
        throw taskCallDebugConfigurationError("此 Task 的请求仍在进行中。");
    }
    function retain(
      taskId: string,
      requestText: string,
      messageIds: string[],
      request?: RpcCallRequest
    ) {
      histories[taskId] ??= [];
      const entries = histories[taskId]!;
      entries.push({
        caller: captureTaskCaller(),
        localId: crypto.randomUUID(),
        taskId,
        request,
        requestText,
        messageIds,
        sentAt: now().toISOString(),
        state: "sending"
      });
      while (entries.length > TASK_CALL_DEBUG_HISTORY_LIMIT) entries.shift();
      return entries[entries.length - 1]!;
    }
    async function send(taskId: string, requestText: string) {
      admit(taskId);
      const request = parseRpcRequest(requestText);
      const item = retain(taskId, requestText, rpcMessageIds(request), request);
      try {
        item.results = await client.callTask({
          caller: item.caller,
          taskId,
          request: JSON.parse(JSON.stringify(request)) as RpcCallRequest
        });
        item.readAt = now().toISOString();
        item.state = "observed";
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
      }
      return item;
    }
    async function loadResults(taskId: string, messageIds: string[], localId?: string) {
      admit(taskId);
      if (
        !messageIds.length ||
        messageIds.length > 100 ||
        messageIds.some((id) => !id.trim())
      )
        throw taskCallDebugConfigurationError("每次读取 1～100 个 messageId。");
      const existing = localId
        ? history(taskId).find((item) => item.localId === localId)
        : undefined;
      if (localId && !existing)
        throw taskCallDebugConfigurationError("会话请求已不存在。");
      const item =
        existing ??
        retain(taskId, JSON.stringify(messageIds), [...new Set(messageIds)]);
      item.checkingResult = true;
      item.resultLoadError = undefined;
      try {
        item.results = await client.loadResults(item.caller, item.taskId, [
          ...item.messageIds
        ]);
        item.readAt = now().toISOString();
        item.state = "observed";
      } catch (cause) {
        item.resultLoadError = presentTaskCallDebugError(cause);
        if (!existing) item.state = "unconfirmed";
      } finally {
        item.checkingResult = false;
      }
      return item;
    }
    function clear(taskId: string) {
      if (isBusy(taskId)) return false;
      delete histories[taskId];
      return true;
    }
    return { histories, draft, history, isBusy, send, loadResults, clear };
  })();
}
export type TaskCallDebugStore = ReturnType<typeof createTaskCallDebugStore>;
