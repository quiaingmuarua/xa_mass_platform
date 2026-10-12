<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Refresh } from "@element-plus/icons-vue";
import JsonBlock from "@/components/JsonBlock.vue";
import { useRuntimeViewerConfig, useTaskCallDebugStore } from "@/runtime-context";
import { parseWorkerSelector } from "@/task-call-debug/model";
import { presentTaskCallDebugError } from "@/task-call-debug/errors";
import type { TaskCallDebugHistoryItem } from "@/stores/task-call-debug";
import type { Project } from "@/task-management/projects";
import { useTaskSession } from "./session";
const props = defineProps<{ project: Project }>();
const session = useTaskSession();
const workspace = session.rpc;
const config = useRuntimeViewerConfig();
const calls = useTaskCallDebugStore();
const route = useRoute();
const router = useRouter();
const groupId = computed(() =>
  typeof route.query.group === "string" ? route.query.group : ""
);
const groups = computed(() => workspace.groups.default);
const group = computed(() =>
  groups.value?.value?.find((g) => g.workerGroupId === groupId.value)
);
const taskId = computed(() => props.project.managedTaskIds[groupId.value] ?? "");
const state = computed(() => workspace.detail.value);
const row = computed(() => state.value.value);
const valid = computed(
  () =>
    !!taskId.value &&
    row.value?.taskId === taskId.value &&
    row.value.task?.projectId === "default" &&
    row.value.task.workerGroupId === groupId.value &&
    row.value.task.idleDisposition === "PARK_WHEN_IDLE"
);
const fresh = computed(
  () =>
    valid.value &&
    !!group.value?.workerGroup &&
    !!row.value?.scoreBand &&
    row.value.scoreBand !== "terminal" &&
    !state.value.loading &&
    !state.value.stale &&
    !groups.value?.loading &&
    !groups.value?.stale &&
    !session.directory.loading &&
    !session.directory.stale
);
const enabled = computed(() => config.mode === "api" && fresh.value);
const draft = computed(() => calls.draft(taskId.value));
const history = computed(() => calls.history(taskId.value));
const busy = computed(() => calls.isBusy(taskId.value));
const validation = ref("");
let disposed = false;
let navigation = 0;
function time(value?: string) {
  return value ? new Date(value).toLocaleString() : "未提供";
}
async function sync() {
  const generation = ++navigation;
  validation.value = "";
  if (!groups.value) await workspace.loadGroups("default");
  if (disposed || generation !== navigation) return;
  if (!groupId.value) {
    const first =
      session.navigation.group || Object.keys(props.project.managedTaskIds)[0];
    if (first) await router.replace({ query: { tab: "rpc", group: first } });
    return;
  }
  session.navigation.group = groupId.value;
  if (taskId.value) {
    if (workspace.detailKey.value !== "default/" + taskId.value)
      await workspace.loadDetail("default", taskId.value);
  } else workspace.clearDetail();
}
onMounted(sync);
watch(
  () => [route.query.group, props.project],
  () => {
    if (route.path === "/task-workbench" && route.query.tab !== "finite") void sync();
  }
);
onBeforeUnmount(() => {
  disposed = true;
  navigation++;
});
async function refresh() {
  const id = taskId.value;
  await Promise.allSettled([
    session.loadDirectory(),
    workspace.loadGroups("default"),
    ...(id ? [workspace.loadDetail("default", id)] : [])
  ]);
}
async function send() {
  if (!enabled.value || busy.value) return;
  validation.value = "";
  const id = taskId.value;
  const generation = navigation;
  try {
    if (!group.value?.matching.functions.includes(draft.value.executorName))
      throw new Error("请选择当前 Group 已启用的查询函数。");
    const selector = parseWorkerSelector(
      JSON.stringify({
        executorName: draft.value.executorName,
        input: JSON.parse(draft.value.inputText)
      })
    );
    await calls.send({
      taskId: id,
      workerGroupId: groupId.value,
      eventName: draft.value.eventName,
      payloadText: draft.value.payloadText,
      workerSelectorText: JSON.stringify(selector),
      waitTimeoutMillis: draft.value.waitTimeoutMillis
    });
    await nextTick();
  } catch (cause) {
    if (!disposed && generation === navigation)
      validation.value =
        cause instanceof Error
          ? cause.message
          : presentTaskCallDebugError(cause).message;
  }
}
async function loadResult(item: TaskCallDebugHistoryItem) {
  try {
    await calls.loadResult(item.taskId, item.localId);
  } catch (cause) {
    if (taskId.value === item.taskId)
      validation.value = presentTaskCallDebugError(cause).message;
  }
}
const labels: Record<string, string> = {
  sending: "调用中",
  succeeded: "执行成功",
  failed: "执行失败",
  not_observed: "尚未观测到结果",
  unconfirmed: "请求结果未确认",
  request_rejected: "请求被拒绝"
};
</script>
<template>
  <section class="rpc-page" data-testid="rpc-task-page">
    <div class="rpc-page__scope">
      <el-select
        :model-value="groupId"
        aria-label="RPC WorkerGroup"
        placeholder="选择 Group"
        @change="router.push({ query: { tab: 'rpc', group: String($event) } })"
        ><el-option
          v-for="(_, id) in project.managedTaskIds"
          :key="id"
          :value="id"
          :label="id"
      /></el-select>
      <el-button
        :icon="Refresh"
        :loading="state.loading || groups?.loading"
        @click="refresh"
        >刷新</el-button
      >
      <span class="raw-muted">最近读取：{{ time(state.readAt) }}</span>
    </div>
    <el-alert
      v-if="state.error || groups?.error"
      :title="'读取失败，保留已有快照：' + (state.error || groups?.error)"
      type="error"
      :closable="false"
    />
    <el-alert
      v-if="!enabled"
      :title="
        config.mode === 'mock'
          ? 'Mock 只展示配置，不执行真实 RPC 调用。'
          : '当前 Group、managed Task 或最新读取状态不可用，不能提交调用。'
      "
      type="info"
      :closable="false"
    />
    <template v-if="valid">
      <p class="raw-muted">
        Task：<code>{{ taskId }}</code> · {{ row?.scoreBand ?? "状态不可用" }}
      </p>
      <details>
        <summary>Task 供给与原始配置（只读）</summary>
        <JsonBlock :value="row!" />
      </details>
      <p class="raw-muted">
        查询能力来自 Group 配置，不证明当前存在库存或可调度 Worker。调用通过 Kernel
        调度。
      </p>
      <div class="rpc-page__grid">
        <form class="rpc-page__form" @submit.prevent="send">
          <label
            ><span>Event</span
            ><el-select
              v-model="draft.eventName"
              filterable
              allow-create
              default-first-option
              :disabled="!enabled || busy"
              aria-label="RPC Event"
              placeholder="选择事件或输入完整名称"
              ><el-option
                v-for="event in group?.workerGroup?.eventCodes ?? []"
                :key="event"
                :value="event"
                :label="event" /></el-select
          ></label>
          <label
            ><span>Payload · JSON 对象</span
            ><textarea
              v-model="draft.payloadText"
              rows="6"
              aria-label="RPC Payload"
              :disabled="!enabled || busy"
              spellcheck="false"
            />
          </label>
          <label
            ><span>Worker 查询函数</span
            ><el-select
              v-model="draft.executorName"
              :disabled="!enabled || busy"
              aria-label="RPC 查询函数"
              placeholder="请选择查询函数"
              ><el-option
                v-for="fn in group?.matching.functions ?? []"
                :key="fn"
                :value="fn"
                :label="fn" /></el-select
          ></label>
          <label
            ><span>查询 input · 非 null JSON</span
            ><textarea
              v-model="draft.inputText"
              rows="4"
              aria-label="RPC 查询参数"
              :disabled="!enabled || busy"
              spellcheck="false"
            />
          </label>
          <label
            ><span>等待时间 · ms</span
            ><input
              v-model.number="draft.waitTimeoutMillis"
              type="number"
              min="1"
              max="60000"
              aria-label="RPC 等待时间"
              :disabled="!enabled || busy"
          /></label>
          <el-alert
            v-if="validation"
            :title="validation"
            type="error"
            :closable="false"
          />
          <el-button
            type="primary"
            native-type="submit"
            :loading="busy"
            :disabled="!enabled || busy"
            >提交调用</el-button
          >
          <p class="raw-muted">
            会提交真实 Item；等待超时不代表执行失败，离开页面不会撤回已接受的工作。
          </p>
        </form>
        <section aria-label="RPC 调用记录">
          <div class="rpc-call__heading">
            <strong>本会话调用 · {{ history.length }} / 20</strong
            ><el-button
              link
              :disabled="busy || !history.length"
              @click="calls.clear(taskId)"
              >清空记录</el-button
            >
          </div>
          <p v-if="!history.length" class="raw-muted">
            暂无调用记录。记录仅保留在浏览器内存，刷新页面后清空。
          </p>
          <div class="rpc-history">
            <article
              v-for="item in [...history].reverse()"
              :key="item.localId"
              class="rpc-call"
            >
              <div class="rpc-call__heading">
                <el-tag
                  :type="
                    item.state === 'succeeded'
                      ? 'success'
                      : item.state === 'failed'
                        ? 'danger'
                        : 'info'
                  "
                  >{{ labels[item.state] }}</el-tag
                ><span class="raw-muted">{{ time(item.sentAt) }}</span>
              </div>
              <p>
                <code>{{ item.messageId }}</code>
              </p>
              <p>{{ item.eventName }}</p>
              <p v-if="item.safeError" role="alert">
                {{ item.safeError.message }}
                <small>{{ item.safeError.requestId }}</small>
              </p>
              <p
                v-if="item.state === 'not_observed' || item.state === 'unconfirmed'"
                class="raw-muted"
              >
                执行结果尚未确认；可读取原 messageId，不自动重发。
              </p>
              <el-button
                v-if="['not_observed', 'unconfirmed'].includes(item.state)"
                size="small"
                :loading="item.checkingResult"
                :disabled="busy && !item.checkingResult"
                @click="loadResult(item)"
                >读取结果</el-button
              >
              <p v-if="item.lastCheckedAt" class="raw-muted">
                最近读取：{{ time(item.lastCheckedAt) }}
              </p>
              <p v-if="item.resultLoadError" role="alert">
                {{ item.resultLoadError.message }}
              </p>
              <pre v-if="item.opaqueResultPayload !== undefined">{{
                item.opaqueResultPayload
              }}</pre>
              <details>
                <summary>查看请求</summary>
                <pre>{{ item.payloadText }}</pre>
                <pre>{{ item.workerSelectorText }}</pre>
              </details>
            </article>
          </div>
        </section>
      </div>
    </template>
  </section>
</template>
