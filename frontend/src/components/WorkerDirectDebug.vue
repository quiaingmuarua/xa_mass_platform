<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { Delete, Position, Warning } from "@element-plus/icons-vue";

import JsonBlock from "@/components/JsonBlock.vue";
import { useRuntimeViewerConfig, useWorkerDirectDebugStore } from "@/runtime-context";
import type { WorkerView } from "@/runtime-viewer/types";
import type { DirectDebugHistoryItem } from "@/stores/worker-direct-debug";
import {
  presentWorkerDirectCallError,
  type WorkerDirectCallErrorPresentation
} from "@/worker-direct-call/errors";
import {
  DEFAULT_DIRECT_CALL_TIMEOUT_MILLIS,
  isWorkerDirectCallEnabled,
  parseOpaqueJson
} from "@/worker-direct-call/model";
import { presentWorkerDirectCallTarget } from "@/worker-direct-call/presentation";

const props = defineProps<{
  worker: WorkerView;
  eventCodes: string[];
}>();

const config = useRuntimeViewerConfig();
const directDebug = useWorkerDirectDebugStore();
const eventName = ref("");
const payloadText = ref("{}");
const waitTimeoutMillis = ref(DEFAULT_DIRECT_CALL_TIMEOUT_MILLIS);
const validationError = ref<WorkerDirectCallErrorPresentation>();
const historyViewport = ref<HTMLElement>();

const isMock = computed(() => !isWorkerDirectCallEnabled(config.mode));
const eventOptions = computed(() =>
  [...new Set(props.eventCodes.map((value) => value.trim()))].filter(
    (value) => value.length > 0
  )
);
const history = computed(() => directDebug.history(props.worker.workerId));
const calling = computed(() => directDebug.isCalling(props.worker.workerId));

watch(
  () => props.worker.workerId,
  () => resetDraft(),
  { immediate: true }
);

watch(
  () => history.value.map((item) => `${item.localId}:${item.state}`).join("|"),
  () => void scrollToLatest(),
  { flush: "post", immediate: true }
);

async function send(): Promise<void> {
  if (calling.value || isMock.value) return;
  validationError.value = undefined;
  try {
    await directDebug.send({
      workerGroupId: props.worker.workerGroupId,
      workerId: props.worker.workerId,
      endpointManagerId: props.worker.endpointManagerId,
      eventName: eventName.value,
      payloadText: payloadText.value,
      waitTimeoutMillis: waitTimeoutMillis.value
    });
  } catch (cause) {
    validationError.value = presentWorkerDirectCallError(cause);
  }
}

function clearHistory(): void {
  directDebug.clear(props.worker.workerId);
  validationError.value = undefined;
}

function resetDraft(): void {
  eventName.value = props.eventCodes[0] ?? "";
  payloadText.value = "{}";
  waitTimeoutMillis.value = DEFAULT_DIRECT_CALL_TIMEOUT_MILLIS;
  validationError.value = undefined;
}

function resultPresentation(item: DirectDebugHistoryItem) {
  return item.response === undefined
    ? undefined
    : presentWorkerDirectCallTarget(item.response.target);
}

function rawResultPayload(item: DirectDebugHistoryItem): string | undefined {
  return item.response?.target.status === "observed"
    ? item.response.target.opaqueResultPayload
    : undefined;
}

function parsedResultPayload(item: DirectDebugHistoryItem) {
  return parseOpaqueJson(rawResultPayload(item));
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
  <section class="worker-direct-debug" data-testid="worker-direct-debug">
    <div class="worker-direct-debug__heading">
      <div>
        <h2>Direct Debug</h2>
        <p>向当前 Worker 发送 best-effort Direct Call</p>
      </div>
      <el-tag effect="plain" type="warning" size="small">BYPASS SCHEDULING</el-tag>
    </div>

    <dl class="worker-direct-debug__coordinates">
      <div>
        <dt>WorkerGroup</dt>
        <dd>{{ worker.workerGroupId }}</dd>
      </div>
      <div>
        <dt>Worker</dt>
        <dd>{{ worker.workerId }}</dd>
      </div>
      <div>
        <dt>Endpoint</dt>
        <dd>{{ worker.endpointManagerId }}</dd>
      </div>
    </dl>

    <el-alert v-if="isMock" type="warning" :closable="false" show-icon>
      <template #title>
        Mock 模式不执行 Direct Call；请使用真实 Server 和 Worker Profile。
      </template>
    </el-alert>

    <div class="worker-direct-debug__history-heading">
      <div>
        <strong>当前 Worker 会话</strong>
        <small>仅保存在浏览器内存 · {{ history.length }} / 20</small>
      </div>
      <el-button
        link
        type="danger"
        :icon="Delete"
        :disabled="history.length === 0 || calling"
        @click="clearHistory"
      >
        清空
      </el-button>
    </div>

    <div ref="historyViewport" class="worker-direct-debug__history">
      <div v-if="history.length === 0" class="worker-direct-debug__empty">
        <span>暂无调用记录</span>
        <small
          >选择 Event 并发送 JSON Payload 后，Request 和 Response 会显示在这里。</small
        >
      </div>

      <article
        v-for="item in history"
        :key="item.localId"
        class="worker-direct-debug__exchange"
      >
        <div class="worker-direct-debug__message worker-direct-debug__message--request">
          <div class="worker-direct-debug__message-meta">
            <strong>{{ item.eventName }}</strong>
            <time>{{ formattedTime(item.sentAt) }}</time>
          </div>
          <pre>{{ item.payloadText }}</pre>
        </div>

        <div
          class="worker-direct-debug__message worker-direct-debug__message--response"
        >
          <template v-if="item.state === 'sending'">
            <div class="worker-direct-debug__message-meta">
              <el-tag type="info" effect="light" size="small">Sending</el-tag>
              <time>等待 Direct Call 结果</time>
            </div>
          </template>

          <template v-else-if="item.state === 'failed'">
            <div class="worker-direct-debug__message-meta">
              <el-tag type="danger" effect="light" size="small">Failed</el-tag>
              <time>{{ formattedTime(item.completedAt) }}</time>
            </div>
            <strong>{{ item.safeError?.title }}</strong>
            <p>{{ item.safeError?.message }}</p>
            <small v-if="item.safeError?.requestId">
              Request ID: {{ item.safeError.requestId }}
            </small>
          </template>

          <template v-else-if="item.response">
            <div class="worker-direct-debug__message-meta">
              <el-tag
                :type="resultPresentation(item)?.tone"
                effect="light"
                size="small"
              >
                {{ resultPresentation(item)?.label }}
              </el-tag>
              <time>{{ formattedTime(item.completedAt) }}</time>
            </div>
            <p>{{ resultPresentation(item)?.description }}</p>
            <code>{{ item.response.directCallId }}</code>

            <template v-if="item.response.target.status === 'observed'">
              <JsonBlock
                v-if="parsedResultPayload(item) !== undefined"
                :value="parsedResultPayload(item)!"
              />
              <details v-if="rawResultPayload(item) !== undefined">
                <summary>Raw Result</summary>
                <pre>{{ rawResultPayload(item) }}</pre>
              </details>
              <small v-else>Worker Result 没有携带 opaque payload。</small>
            </template>
          </template>
        </div>
      </article>
    </div>

    <el-alert
      v-if="validationError"
      class="worker-direct-debug__error"
      type="error"
      :closable="false"
      show-icon
    >
      <template #title>{{ validationError.title }}</template>
      <p>{{ validationError.message }}</p>
    </el-alert>

    <form class="worker-direct-debug__composer" @submit.prevent="send">
      <label>
        <span>Event Name</span>
        <el-select
          v-model="eventName"
          class="worker-direct-debug__event-select"
          filterable
          allow-create
          default-first-option
          placeholder="选择建议或输入完整 Event Name"
          :disabled="isMock || calling"
        >
          <el-option
            v-for="eventCode in eventOptions"
            :key="eventCode"
            :label="eventCode"
            :value="eventCode"
          />
        </el-select>
        <small>WorkerGroup Event 只是输入建议，不是调用授权清单。</small>
      </label>

      <label>
        <span>Payload · JSON</span>
        <textarea
          v-model="payloadText"
          rows="6"
          spellcheck="false"
          :disabled="isMock || calling"
        />
        <small>浏览器校验 JSON，线路上仍按 opaque string 原样发送。</small>
      </label>

      <div class="worker-direct-debug__composer-actions">
        <label class="worker-direct-debug__timeout">
          <span>Wait Timeout · ms</span>
          <input
            v-model.number="waitTimeoutMillis"
            type="number"
            min="1"
            max="10000"
            step="1"
            :disabled="isMock || calling"
          />
        </label>

        <el-button
          native-type="submit"
          type="primary"
          :icon="Position"
          :loading="calling"
          :disabled="isMock || calling"
        >
          Send Direct Call
        </el-button>
      </div>
    </form>

    <div class="worker-direct-debug__notice">
      <el-icon><Warning /></el-icon>
      <p>
        Direct Call 不创建 TaskItem、不取得 Worker Lease，也不证明当前 Worker
        schedulable 或 executing。关闭页面不会撤回已投递的 Command。
      </p>
    </div>
  </section>
</template>
