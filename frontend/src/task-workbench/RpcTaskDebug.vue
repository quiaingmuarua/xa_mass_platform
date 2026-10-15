<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import JsonBlock from "@/components/JsonBlock.vue";
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
const results = computed(() =>
  Object.entries(record.value?.results ?? {}).map(([messageId, result]) => ({
    messageId,
    ...result
  }))
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
  }
);
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
      eventCode: event.value || "填写事件名称",
      payload: {},
      workerSelector: {
        executorName: fn.value || "选择查询函数",
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
function time(value?: string) {
  return value ? new Date(value).toLocaleString() : "尚未读取";
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
    <details>
      <summary>Task 保存配置与 Group 能力参考</summary>
      <JsonBlock :value="{ task: task?.task ?? null, capabilities: group ?? null }" />
      <p class="raw-muted">
        配置不保证 Worker 在线或已有库存。查询可独立于本 Task 的供给；参数由 Matching
        校验。
      </p>
    </details>
    <div class="rpc-debug__grid">
      <section>
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
            >提交调用</el-button
          >
        </div>
        <details>
          <summary>插入 Item 示例</summary>
          <div class="rpc-example">
            <el-select
              v-model="event"
              filterable
              allow-create
              aria-label="示例 Event"
              placeholder="选择事件"
              ><el-option
                v-for="name in group?.workerGroup?.eventCodes ?? []"
                :key="name"
                :value="name"
                :label="name"
            /></el-select>
            <el-select
              v-model="fn"
              filterable
              aria-label="示例查询函数"
              placeholder="选择查询函数"
              ><el-option
                v-for="choice in group?.matching.functions ?? []"
                :key="choice.name"
                :value="choice.name"
                :label="
                  choice.name +
                  (choice.poolName ? ' → ' + choice.poolName : '（无库存）')
                "
            /></el-select>
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
        <p class="raw-muted">
          一次请求 1～100 个 Item。waitTimeoutMillis 允许
          1～60000；省略时使用服务端默认。messageId 由请求显式提供。
        </p>
        <p v-if="validation.error" role="alert" class="rpc-validation">
          {{ validation.error }}
        </p>
        <p v-else class="raw-muted">
          {{ validation.request?.items.length }} 个输入，{{
            validation.ids?.length
          }}
          个消息身份。<strong v-if="validation.duplicates"
            >包含 {{ validation.duplicates }} 个重复
            messageId：同请求最后一项生效，返回一项结果。</strong
          >
        </p>
        <p class="raw-muted">
          提交会产生真实工作。等待结束或离开页面不会撤回执行；重复 messageId
          不代表防重复执行。结果领取后从服务端删除，响应丢失可能导致结果无法再次取得。
        </p>
      </section>
      <section>
        <div class="rpc-tools">
          <strong>本会话请求 · {{ history.length }} / 20</strong
          ><el-button
            link
            :disabled="busy || !history.length"
            @click="calls.clear(taskId)"
            >清空</el-button
          >
        </div>
        <el-select
          v-model="selected"
          aria-label="调试请求记录"
          placeholder="暂无会话请求"
          ><el-option
            v-for="item in [...history].reverse()"
            :key="item.localId"
            :value="item.localId"
            :label="
              time(item.sentAt) +
              ' · ' +
              item.messageIds.length +
              ' 项 · ' +
              stateLabels[item.state]
            "
        /></el-select>
        <template v-if="record">
          <p class="raw-muted">
            {{ stateLabels[record.state] }} · 最近领取 {{ time(record.readAt) }}
          </p>
          <el-alert
            v-if="record.safeError"
            :title="record.safeError.message"
            type="error"
            :closable="false"
            >{{ record.safeError.requestId }}。不能据此推断全部 Item
            未执行；可按原身份领取当前结果。</el-alert
          >
          <el-alert
            v-if="record.resultTakeError"
            :title="'结果领取未确认，保留原快照：' + record.resultTakeError.message"
            type="error"
            :closable="false"
          />
          <el-button size="small" :disabled="!readable || busy" @click="read(true)"
            >补领未取得结果</el-button
          >
          <details>
            <summary>本次冻结请求与消息身份</summary>
            <pre>{{ record.requestText }}</pre>
            <JsonBlock :value="record.messageIds" />
          </details>
          <el-table :data="results" height="400" aria-label="RPC 批量结果">
            <el-table-column
              prop="messageId"
              label="messageId"
              min-width="145"
              show-overflow-tooltip
            />
            <el-table-column prop="status" label="观察结果" width="135" />
            <el-table-column label="Worker 原始输出" min-width="220"
              ><template #default="{ row }"
                ><template v-if="row.status === 'succeeded'">
                  <pre class="rpc-output">{{ row.opaqueResultPayload }}</pre>
                  <el-button link @click="copy(row.opaqueResultPayload)"
                    >复制原值</el-button
                  >
                  <details>
                    <summary>格式化查看</summary>
                    <JsonBlock
                      :value="
                        parseTaskCallResultJson(row.opaqueResultPayload) ??
                        row.opaqueResultPayload
                      "
                    /></details></template
                ><span v-else>{{
                  row.status === "failed" ? "执行失败" : "本次尚未观察到结果"
                }}</span></template
              ></el-table-column
            >
            <template #empty>尚未取得结果映射</template>
          </el-table>
        </template>
        <p v-else class="raw-muted">记录仅保留当前登录会话，不是完整调用历史。</p>
        <details>
          <summary>按已知 messageId 领取结果</summary>
          <el-input
            v-model="draft.resultIdsText"
            type="textarea"
            :rows="4"
            aria-label="领取 messageId"
            placeholder="每行一个，最多 100 个"
          />
          <div class="rpc-tools">
            <el-button :disabled="!readable || busy" @click="read()">领取结果</el-button
            ><el-button @click="showCurl(true)">领取 cURL</el-button>
          </div>
        </details>
      </section>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <details v-if="curl" open>
      <summary>cURL · POSIX shell</summary>
      <pre>{{ curl }}</pre>
      <el-button @click="copy(curl)">复制 cURL</el-button>
    </details>
  </section>
</template>
