<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { Delete, Position, RefreshRight, Warning } from "@element-plus/icons-vue";

import JsonBlock from "@/components/JsonBlock.vue";
import { useRuntimeViewerConfig, useTaskCallDebugStore } from "@/runtime-context";
import type { TaskRuntimePreviewEntry } from "@/runtime-viewer/types";
import type { TaskCallDebugHistoryItem } from "@/stores/task-call-debug";
import {
  presentTaskCallDebugError,
  type TaskCallDebugErrorPresentation
} from "@/task-call-debug/errors";
import {
  DEFAULT_TASK_CALL_TIMEOUT_MILLIS,
  parseTaskCallResultJson,
  taskCallDebugAvailability
} from "@/task-call-debug/model";

const props = defineProps<{
  entry: TaskRuntimePreviewEntry;
}>();

const config = useRuntimeViewerConfig();
const taskCallDebug = useTaskCallDebugStore();
const eventName = ref("");
const payloadText = ref("{}");
const workerSelectorText = ref("[]");
const waitTimeoutMillis = ref(DEFAULT_TASK_CALL_TIMEOUT_MILLIS);
const validationError = ref<TaskCallDebugErrorPresentation>();
const historyViewport = ref<HTMLElement>();

const availability = computed(() =>
  taskCallDebugAvailability(config.mode, props.entry)
);
const eventOptions = computed(() =>
  [
    ...new Set((props.entry.workerGroup?.eventCodes ?? []).map((value) => value.trim()))
  ].filter((value) => value.length > 0)
);
const history = computed(() => taskCallDebug.history(props.entry.taskId));
const busy = computed(() => taskCallDebug.isBusy(props.entry.taskId));

watch(
  () => props.entry.taskId,
  () => resetDraft(),
  { immediate: true }
);

watch(
  () =>
    history.value
      .map(
        (item) =>
          `${item.localId}:${item.state}:${item.checkingResult}:${item.lastCheckedAt}`
      )
      .join("|"),
  () => void scrollToLatest(),
  { flush: "post", immediate: true }
);

async function send(): Promise<void> {
  if (!availability.value.enabled || busy.value) return;
  validationError.value = undefined;
  try {
    await taskCallDebug.send({
      taskId: props.entry.taskId,
      workerGroupId: props.entry.task?.workerGroupId ?? "",
      eventName: eventName.value,
      payloadText: payloadText.value,
      workerSelectorText: workerSelectorText.value,
      waitTimeoutMillis: waitTimeoutMillis.value
    });
  } catch (cause) {
    validationError.value = presentTaskCallDebugError(cause);
  }
}

async function loadResult(item: TaskCallDebugHistoryItem): Promise<void> {
  validationError.value = undefined;
  try {
    await taskCallDebug.loadResult(props.entry.taskId, item.localId);
  } catch (cause) {
    validationError.value = presentTaskCallDebugError(cause);
  }
}

function clearHistory(): void {
  taskCallDebug.clear(props.entry.taskId);
  validationError.value = undefined;
}

function resetDraft(): void {
  eventName.value = props.entry.workerGroup?.eventCodes[0] ?? "";
  payloadText.value = "{}";
  workerSelectorText.value = "[]";
  waitTimeoutMillis.value = DEFAULT_TASK_CALL_TIMEOUT_MILLIS;
  validationError.value = undefined;
}

function parsedResultPayload(item: TaskCallDebugHistoryItem) {
  return parseTaskCallResultJson(item.opaqueResultPayload);
}

function formattedTime(value?: string): string {
  if (value === undefined) return "—";
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(new Date(value));
}

async function scrollToLatest(): Promise<void> {
  await nextTick();
  if (historyViewport.value !== undefined) {
    historyViewport.value.scrollTop = historyViewport.value.scrollHeight;
  }
}
</script>

<template>
  <section class="worker-direct-debug task-call-debug" data-testid="task-call-debug">
    <div class="worker-direct-debug__heading task-call-debug__heading">
      <div>
        <h2>Task Call Debug</h2>
        <p>提交一个携带有限 Worker Selector 的普通 TaskItem</p>
      </div>
      <el-tag effect="plain" type="success" size="small">
        THROUGH KERNEL SCHEDULING
      </el-tag>
    </div>

    <dl class="worker-direct-debug__coordinates task-call-debug__coordinates">
      <div>
        <dt>Task</dt>
        <dd>{{ entry.taskId }}</dd>
      </div>
      <div>
        <dt>WorkerGroup</dt>
        <dd>{{ entry.task?.workerGroupId ?? "—" }}</dd>
      </div>
      <div>
        <dt>Allocation</dt>
        <dd>{{ entry.task?.workerAllocationMechanism ?? "MISSING" }}</dd>
      </div>
    </dl>

    <el-alert v-if="!availability.enabled" type="warning" :closable="false" show-icon>
      <template #title>{{ availability.reason }}</template>
    </el-alert>

    <div class="worker-direct-debug__history-heading task-call-debug__history-heading">
      <div>
        <strong>当前 Task 会话</strong>
        <small>仅保存在浏览器内存 · {{ history.length }} / 20</small>
      </div>
      <el-button
        link
        type="danger"
        :icon="Delete"
        :disabled="history.length === 0 || busy"
        @click="clearHistory"
      >
        清空
      </el-button>
    </div>

    <div
      ref="historyViewport"
      class="worker-direct-debug__history task-call-debug__history"
    >
      <div
        v-if="history.length === 0"
        class="worker-direct-debug__empty task-call-debug__empty"
      >
        <span>暂无调用记录</span>
        <small>选择 Event、填写 Payload 与 Worker Selector 后发送一个 Item。</small>
      </div>

      <article
        v-for="item in history"
        :key="item.localId"
        class="worker-direct-debug__exchange task-call-debug__exchange"
      >
        <div
          class="worker-direct-debug__message worker-direct-debug__message--request task-call-debug__message task-call-debug__message--request"
        >
          <div class="worker-direct-debug__message-meta task-call-debug__message-meta">
            <strong>{{ item.eventName }}</strong>
            <time>{{ formattedTime(item.sentAt) }}</time>
          </div>
          <small>Payload</small>
          <pre>{{ item.payloadText }}</pre>
          <small>Worker Selector</small>
          <pre>{{ item.workerSelectorText }}</pre>
          <code>{{ item.messageId }}</code>
        </div>

        <div
          class="worker-direct-debug__message worker-direct-debug__message--response task-call-debug__message task-call-debug__message--response"
        >
          <template v-if="item.state === 'sending'">
            <div
              class="worker-direct-debug__message-meta task-call-debug__message-meta"
            >
              <el-tag type="info" effect="light" size="small">Sending</el-tag>
              <time>等待 Task Result</time>
            </div>
          </template>

          <template v-else-if="item.state === 'failed'">
            <div
              class="worker-direct-debug__message-meta task-call-debug__message-meta"
            >
              <el-tag type="danger" effect="light" size="small">Failed</el-tag>
              <time>{{ formattedTime(item.completedAt) }}</time>
            </div>
            <template v-if="item.safeError">
              <strong>{{ item.safeError.title }}</strong>
              <p>{{ item.safeError.message }}</p>
            </template>
            <p v-else>Kernel 已将该 Item 收敛为最终失败；没有成功 Result payload。</p>
            <small v-if="item.safeError?.requestId">
              Request ID: {{ item.safeError.requestId }}
            </small>
          </template>

          <template v-else-if="item.state === 'succeeded'">
            <div
              class="worker-direct-debug__message-meta task-call-debug__message-meta"
            >
              <el-tag type="success" effect="light" size="small">Succeeded</el-tag>
              <time>{{ formattedTime(item.completedAt) }}</time>
            </div>
            <p>等待窗口内观测到了该 Message ID 的成功 Result。</p>
            <JsonBlock
              v-if="parsedResultPayload(item) !== undefined"
              :value="parsedResultPayload(item)!"
            />
            <details v-if="item.opaqueResultPayload !== undefined">
              <summary>Raw Result</summary>
              <pre>{{ item.opaqueResultPayload }}</pre>
            </details>
          </template>

          <template v-else>
            <div
              class="worker-direct-debug__message-meta task-call-debug__message-meta"
            >
              <el-tag type="warning" effect="light" size="small"> Not Observed </el-tag>
              <time>{{ formattedTime(item.completedAt) }}</time>
            </div>
            <p>Item 已被接受，但等待窗口内没有观测到 Result；这不表示 Item 未执行。</p>
            <el-button
              size="small"
              :icon="RefreshRight"
              :loading="item.checkingResult === true"
              :disabled="busy && item.checkingResult !== true"
              @click="loadResult(item)"
            >
              Load Result
            </el-button>
            <small v-if="item.lastCheckedAt">
              最近手工读取：{{ formattedTime(item.lastCheckedAt) }}
            </small>
            <div v-if="item.resultLoadError" class="task-call-debug__load-error">
              <strong>{{ item.resultLoadError.title }}</strong>
              <p>{{ item.resultLoadError.message }}</p>
              <small v-if="item.resultLoadError.requestId">
                Request ID: {{ item.resultLoadError.requestId }}
              </small>
            </div>
          </template>
        </div>
      </article>
    </div>

    <el-alert
      v-if="validationError"
      class="worker-direct-debug__error task-call-debug__error"
      type="error"
      :closable="false"
      show-icon
    >
      <template #title>{{ validationError.title }}</template>
      <p>{{ validationError.message }}</p>
      <small v-if="validationError.requestId">
        Request ID: {{ validationError.requestId }}
      </small>
    </el-alert>

    <form
      class="worker-direct-debug__composer task-call-debug__composer"
      @submit.prevent="send"
    >
      <label>
        <span>Event Name</span>
        <el-select
          v-model="eventName"
          class="worker-direct-debug__event-select task-call-debug__event-select"
          filterable
          allow-create
          default-first-option
          placeholder="选择建议或输入完整 Event Name"
          :disabled="!availability.enabled || busy"
        >
          <el-option
            v-for="eventCode in eventOptions"
            :key="eventCode"
            :label="eventCode"
            :value="eventCode"
          />
        </el-select>
        <small>WorkerGroup Event 只是输入建议，不是调度授权清单。</small>
      </label>

      <label>
        <span>Payload · JSON Object</span>
        <textarea
          v-model="payloadText"
          rows="5"
          spellcheck="false"
          :disabled="!availability.enabled || busy"
        />
      </label>

      <label>
        <span>Worker Selector · JSON Array</span>
        <textarea
          v-model="workerSelectorText"
          rows="5"
          spellcheck="false"
          :disabled="!availability.enabled || busy"
        />
        <small>
          [] 表示任意可服务 Worker；也可使用 workerId 的 $eq 或 $in 指令。
        </small>
      </label>

      <div
        class="worker-direct-debug__composer-actions task-call-debug__composer-actions"
      >
        <label class="worker-direct-debug__timeout task-call-debug__timeout">
          <span>Wait Timeout · ms</span>
          <input
            v-model.number="waitTimeoutMillis"
            type="number"
            min="1"
            max="60000"
            step="1"
            :disabled="!availability.enabled || busy"
          />
        </label>

        <el-button
          native-type="submit"
          type="primary"
          :icon="Position"
          :loading="busy"
          :disabled="!availability.enabled || busy"
        >
          Send Task Call
        </el-button>
      </div>
    </form>

    <div class="worker-direct-debug__notice task-call-debug__notice">
      <el-icon><Warning /></el-icon>
      <p>
        Task Call 会经过 Kernel Scheduling；成功 Result 不揭示最终匹配的
        Worker。not_observed 不表示 Item 未执行，关闭页面也不会撤回已接受的 Item。
      </p>
    </div>
  </section>
</template>
