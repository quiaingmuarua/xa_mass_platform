<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import JsonBlock from "@/components/JsonBlock.vue";
import GroupCapabilitiesCard from "./components/GroupCapabilitiesCard.vue";
import { formatDateTime, formatRefillList, presentResultStatus } from "./presentation";
import { useTaskCallDebugStore, useRuntimeViewerConfig } from "@/runtime-context";
import { captureTaskCaller } from "@/console-identity";
import {
  parseRpcRequest,
  parseResultIds,
  rpcMessageIds,
  rpcCurl,
  parseTaskCallResultJson
} from "@/task-call-debug/model";
import { presentTaskCallDebugError } from "@/task-call-debug/errors";
import type { ProjectGroup, ProjectTask } from "@/task-management/projects";

const props = defineProps<{
  taskId: string;
  task?: ProjectTask;
  group?: ProjectGroup;
  enabled: boolean;
  readable: boolean;
  mode: "api" | "mock";
}>();

const calls = useTaskCallDebugStore();
const config = useRuntimeViewerConfig();
const draft = calls.draft(props.taskId);
const history = computed(() => calls.history(props.taskId));
const selected = ref(history.value.at(-1)?.localId ?? "");
const record = computed(() =>
  history.value.find((item) => item.localId === selected.value)
);
const resultFilter = ref<"all" | "succeeded" | "failed" | "not_observed">("all");

const requestItemsById = computed(() => {
  const map = new Map<string, { eventCode: string; executorName: string }>();
  for (const item of record.value?.request?.items ?? []) {
    // Same-request duplicate messageId uses last-input semantics.
    map.set(item.messageId, {
      eventCode: item.eventCode,
      executorName: item.workerSelector.executorName
    });
  }
  return map;
});

const results = computed(() =>
  Object.entries(record.value?.results ?? {}).map(([messageId, result]) => ({
    messageId,
    requestMeta: requestItemsById.value.get(messageId),
    ...result
  }))
);

const resultCounts = computed(() => {
  const counts = {
    all: results.value.length,
    succeeded: 0,
    failed: 0,
    not_observed: 0
  };
  for (const row of results.value) {
    if (row.status === "succeeded") counts.succeeded++;
    else if (row.status === "failed") counts.failed++;
    else counts.not_observed++;
  }
  return counts;
});

const filteredResults = computed(() =>
  resultFilter.value === "all"
    ? results.value
    : results.value.filter((row) => row.status === resultFilter.value)
);

const busy = computed(() => calls.isBusy(props.taskId));
const event = ref("");
const fn = ref("");
const error = ref("");
const curl = ref("");
let disposed = false;

onBeforeUnmount(() => {
  disposed = true;
});

watch(
  () => history.value.length,
  () => {
    selected.value = history.value.at(-1)?.localId ?? "";
    resultFilter.value = "all";
  }
);

watch(selected, () => {
  resultFilter.value = "all";
});

const validation = computed(() => {
  try {
    const request = parseRpcRequest(draft.requestText);
    if (
      props.group &&
      request.items.some(
        (item) =>
          !props.group!.matching.functions.some(
            (choice) => choice.name === item.workerSelector.executorName
          )
      )
    )
      throw new Error("请求包含当前 Group 未启用的查询函数。");
    const ids = rpcMessageIds(request);
    return { request, ids, duplicates: request.items.length - ids.length, error: "" };
  } catch (cause) {
    return { error: cause instanceof Error ? cause.message : "请求无效" };
  }
});

function format() {
  try {
    draft.requestText = JSON.stringify(JSON.parse(draft.requestText), null, 2);
    error.value = "";
  } catch {
    error.value = "请求不是合法 JSON。";
  }
}

function example() {
  try {
    const value = JSON.parse(draft.requestText) as { items?: unknown[] };
    if (!value || typeof value !== "object" || !Array.isArray(value.items))
      throw new Error("请先准备包含 items 数组的请求对象。");
    if (value.items.length >= 100) throw new Error("每次调用最多 100 个 Item。");
    value.items.push({
      messageId: "debug-" + crypto.randomUUID(),
      eventCode:
        event.value || props.group?.workerGroup?.eventCodes[0] || "填写事件名称",
      payload: {},
      workerSelector: {
        executorName:
          fn.value || props.group?.matching.functions[0]?.name || "选择查询函数",
        input: fn.value === "workerId" ? "填写 Worker ID" : {}
      }
    });
    draft.requestText = JSON.stringify(value, null, 2);
    error.value = "";
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "示例插入失败";
  }
}

async function send() {
  if (!props.enabled || busy.value) return;
  error.value = "";
  try {
    if (validation.value.error) throw new Error(validation.value.error);
    const item = await calls.send(props.taskId, draft.requestText);
    if (!disposed) selected.value = item.localId;
  } catch (cause) {
    if (!disposed)
      error.value =
        cause instanceof Error
          ? cause.message
          : presentTaskCallDebugError(cause).message;
  }
}

async function read(fromHistory = false) {
  if (!props.readable || busy.value) return;
  error.value = "";
  try {
    const current = fromHistory ? record.value : undefined;
    const ids = current?.messageIds ?? parseResultIds(draft.resultIdsText);
    const item = await calls.takeResults(
      props.taskId,
      ids,
      current?.localId,
      fromHistory
    );
    if (!disposed) selected.value = item.localId;
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : "读取失败";
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success("已复制");
  } catch {
    error.value = "复制失败，请手动复制。";
  }
}

function showCurl(load = false) {
  try {
    curl.value = rpcCurl(
      new URL(config.apiBaseUrl, window.location.origin).href,
      props.taskId,
      captureTaskCaller(),
      load ? parseResultIds(draft.resultIdsText) : parseRpcRequest(draft.requestText),
      load ? "results:take" : "items:call"
    );
    error.value = "";
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "请求无效";
  }
}

const stateLabels = {
  sending: "请求中",
  observed: "已返回结果映射",
  unconfirmed: "请求未确认",
  request_rejected: "请求被拒绝"
};
</script>
<template>
  <section class="rpc-debug" aria-label="RPC 批量调试">
    <el-alert
      v-if="!enabled"
      :title="
        mode === 'mock'
          ? 'Mock 仅展示调试样例，不发送请求。'
          : '当前任务尚未确认可调用，请核对生命周期、输入 Owner、状态及 Group 能力。'
      "
      type="info"
      :closable="false"
    />

    <details class="tw-fold tw-fold--boxed">
      <summary>
        Task 供给与 Group 能力参考
        <span v-if="task?.task" class="raw-muted">
          · {{ task.task.workerGroupId }} · 供给
          {{ formatRefillList(task.task.refill).join("、") || "无供给" }}
        </span>
      </summary>
      <div class="tw-debug-reference">
        <GroupCapabilitiesCard
          :group="group"
          :group-id="task?.task?.workerGroupId"
          compact
        />
        <details class="tw-fold">
          <summary>查看原始配置与能力 JSON</summary>
          <JsonBlock
            :value="{ task: task?.task ?? null, capabilities: group ?? null }"
          />
        </details>
      </div>
    </details>

    <section v-if="curl" class="tw-card tw-curl-box" aria-label="cURL 示例">
      <header class="tw-card__header">
        <h3>cURL · POSIX shell</h3>
        <div class="tw-button-row">
          <el-button size="small" @click="copy(curl)">复制 cURL</el-button>
          <el-button size="small" link @click="curl = ''">关闭</el-button>
        </div>
      </header>
      <pre>{{ curl }}</pre>
    </section>

    <div class="rpc-debug__grid">
      <section class="tw-card tw-debug-pane">
        <div class="rpc-tools">
          <strong>调用请求 · JSON</strong>
          <el-button size="small" :disabled="busy" @click="format">格式化</el-button>
          <el-button size="small" @click="copy(draft.requestText)">复制请求</el-button>
          <el-button size="small" @click="showCurl()">cURL 示例</el-button>
          <el-button
            type="primary"
            size="small"
            :loading="busy"
            :disabled="!enabled || !!validation.error"
            @click="send"
          >
            提交调用
          </el-button>
        </div>

        <details class="tw-fold">
          <summary>快捷插入 Item 示例</summary>
          <div class="rpc-example">
            <el-select
              v-model="event"
              filterable
              allow-create
              aria-label="示例 Event"
              placeholder="选择事件"
            >
              <el-option
                v-for="name in group?.workerGroup?.eventCodes ?? []"
                :key="name"
                :value="name"
                :label="name"
              />
            </el-select>
            <el-select
              v-model="fn"
              filterable
              aria-label="示例查询函数"
              placeholder="选择查询函数"
            >
              <el-option
                v-for="choice in group?.matching.functions ?? []"
                :key="choice.name"
                :value="choice.name"
                :label="
                  choice.name +
                  (choice.poolName ? ' → ' + choice.poolName : '（无库存）')
                "
              />
            </el-select>
            <el-button :disabled="busy" @click="example">插入示例</el-button>
          </div>
        </details>

        <el-input
          v-model="draft.requestText"
          type="textarea"
          :rows="16"
          :disabled="busy"
          aria-label="RPC 请求 JSON"
          spellcheck="false"
        />

        <div class="tw-debug-validation">
          <p v-if="validation.error" role="alert" class="rpc-validation">
            {{ validation.error }}
          </p>
          <p v-else class="raw-muted">
            已校验 <strong>{{ validation.request?.items.length }}</strong> 个输入 /
            <strong>{{ validation.ids?.length }}</strong> 个唯一 messageId
            <strong v-if="validation.duplicates" class="tw-warning-text">
              · 含 {{ validation.duplicates }} 个重复 ID（同请求后项生效）
            </strong>
          </p>
          <p class="raw-muted">
            每次 1～100 项，waitTimeoutMillis
            1～60000ms。提交产生真实执行，结果领取后从服务端移除。
          </p>
        </div>
      </section>

      <section class="tw-card tw-debug-pane">
        <div class="rpc-tools">
          <strong>本会话请求 · {{ history.length }} / 20</strong>
          <el-button
            link
            :disabled="busy || !history.length"
            @click="calls.clear(taskId)"
          >
            清空
          </el-button>
        </div>

        <el-select
          v-model="selected"
          aria-label="调试请求记录"
          placeholder="暂无会话请求"
        >
          <el-option
            v-for="item in [...history].reverse()"
            :key="item.localId"
            :value="item.localId"
            :label="
              formatDateTime(item.sentAt, '尚未读取') +
              ' · ' +
              item.messageIds.length +
              ' 项 · ' +
              stateLabels[item.state]
            "
          />
        </el-select>

        <template v-if="record">
          <div class="tw-debug-record-bar">
            <span class="raw-muted">
              {{ stateLabels[record.state] }} · 最近领取
              {{ formatDateTime(record.readAt, "尚未读取") }}
            </span>
            <el-button size="small" :disabled="!readable || busy" @click="read(true)">
              补领未取得结果
            </el-button>
          </div>

          <el-alert
            v-if="record.safeError"
            :title="record.safeError.message"
            type="error"
            :closable="false"
          >
            {{ record.safeError.requestId }}。不能据此推断全部 Item
            未执行；可按原身份领取当前结果。
          </el-alert>
          <el-alert
            v-if="record.resultTakeError"
            :title="
              (record.resultTakeError.rejected
                ? '结果领取被拒绝，保留原快照：'
                : '结果领取未确认，保留原快照：') + record.resultTakeError.message
            "
            type="error"
            :closable="false"
          />

          <div
            v-if="results.length"
            class="tw-segment"
            role="group"
            aria-label="本批结果汇总"
          >
            <button
              type="button"
              :class="{ 'is-active': resultFilter === 'all' }"
              @click="resultFilter = 'all'"
            >
              全部 <span>{{ resultCounts.all }}</span>
            </button>
            <button
              type="button"
              :class="{ 'is-active': resultFilter === 'succeeded' }"
              @click="resultFilter = 'succeeded'"
            >
              成功 <span>{{ resultCounts.succeeded }}</span>
            </button>
            <button
              type="button"
              :class="{ 'is-active': resultFilter === 'failed' }"
              @click="resultFilter = 'failed'"
            >
              失败 <span>{{ resultCounts.failed }}</span>
            </button>
            <button
              type="button"
              :class="{ 'is-active': resultFilter === 'not_observed' }"
              @click="resultFilter = 'not_observed'"
            >
              未观测 <span>{{ resultCounts.not_observed }}</span>
            </button>
          </div>

          <el-table :data="filteredResults" height="380" aria-label="RPC 批量结果">
            <el-table-column label="messageId / 请求上下文" min-width="180">
              <template #default="{ row }">
                <div class="tw-cell-identity">
                  <code class="tw-code-inline">{{ row.messageId }}</code>
                  <div v-if="row.requestMeta" class="tw-cell-identity__sub">
                    <span class="tw-supply-chip">{{ row.requestMeta.eventCode }}</span>
                    <span class="tw-supply-chip">{{
                      row.requestMeta.executorName
                    }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="观察结果" width="128">
              <template #default="{ row }">
                <div class="tw-status tw-status--compact">
                  <el-tag
                    :type="presentResultStatus(row.status).tone"
                    effect="light"
                    size="small"
                  >
                    {{ presentResultStatus(row.status).label }}
                  </el-tag>
                  <code class="tw-code-pill">{{ row.status }}</code>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Worker 原始输出" min-width="210">
              <template #default="{ row }">
                <template v-if="row.status === 'succeeded'">
                  <pre class="rpc-output">{{ row.opaqueResultPayload }}</pre>
                  <el-button link @click="copy(row.opaqueResultPayload)">
                    复制原值
                  </el-button>
                  <details>
                    <summary>格式化查看</summary>
                    <JsonBlock
                      :value="
                        parseTaskCallResultJson(row.opaqueResultPayload) ??
                        row.opaqueResultPayload
                      "
                    />
                  </details>
                </template>
                <span v-else class="raw-muted">{{
                  row.status === "failed" ? "执行失败" : "本次尚未观察到结果"
                }}</span>
              </template>
            </el-table-column>
            <template #empty>尚未取得结果映射</template>
          </el-table>

          <details class="tw-fold">
            <summary>本次冻结请求与消息身份（{{ record.messageIds.length }}）</summary>
            <pre>{{ record.requestText }}</pre>
            <JsonBlock :value="record.messageIds" />
          </details>
        </template>
        <p v-else class="raw-muted">记录仅保留当前登录会话，不是完整调用历史。</p>

        <details class="tw-fold">
          <summary>按已知 messageId 领取结果</summary>
          <el-input
            v-model="draft.resultIdsText"
            type="textarea"
            :rows="4"
            aria-label="领取 messageId"
            placeholder="每行一个，最多 100 个"
          />
          <div class="rpc-tools">
            <el-button :disabled="!readable || busy" @click="read()"
              >领取结果</el-button
            >
            <el-button @click="showCurl(true)">领取 cURL</el-button>
          </div>
        </details>
      </section>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
