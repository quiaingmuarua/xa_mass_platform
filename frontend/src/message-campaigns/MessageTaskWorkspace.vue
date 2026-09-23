<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  ArrowRight,
  Plus,
  Refresh,
  Search,
  Tickets
} from "@element-plus/icons-vue";
import MessageTaskCreate from "./MessageTaskCreate.vue";
import { receiptLabel } from "./model";
import {
  messageTaskSourceKey,
  senderRange,
  taskStateLabel,
  taskStateLabels,
  type MessageTask,
  type MessageTaskDetail
} from "./task-source";

const source = inject(messageTaskSourceKey);
if (!source) throw new Error("Messages task view requires an explicit data source");
const route = useRoute();
const router = useRouter();
const taskId = computed(() =>
  typeof route.params.taskId === "string" ? route.params.taskId : undefined
);
const tasks = ref<MessageTask[]>([]);
const truncated = ref(false);
const detail = ref<MessageTaskDetail>();
const query = ref("");
const stateFilter = ref("all");
const creating = ref(false);
const listBusy = ref(false);
const detailBusy = ref(false);
const listError = ref("");
const detailError = ref("");
const loaded = ref(false);
const expanded = ref(new Set<string>());
const title = ref<HTMLElement>();
let listGeneration = 0;
let detailGeneration = 0;
let disposed = false;
let listScroll = 0;
let lastOpened: string | undefined;
const filtered = computed(() =>
  tasks.value.filter((task) => {
    const search = query.value.trim().toLocaleLowerCase();
    return (
      (!search ||
        `${task.name ?? ""} ${task.taskId}`.toLocaleLowerCase().includes(search)) &&
      (stateFilter.value === "all" ||
        (task.state ?? "unavailable") === stateFilter.value)
    );
  })
);
const date = (value: number) =>
  new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(value);
const failureMessage = (failure: unknown) =>
  failure instanceof Error ? failure.message : "读取失败，请重试。";

async function loadList() {
  if (disposed) return;
  const generation = ++listGeneration;
  listBusy.value = true;
  listError.value = "";
  try {
    const value = await source!.listTasks();
    if (disposed || generation !== listGeneration) return;
    tasks.value = value.tasks;
    truncated.value = value.truncated;
    loaded.value = true;
  } catch (failure) {
    if (!disposed && generation === listGeneration)
      listError.value = failureMessage(failure);
  } finally {
    if (!disposed && generation === listGeneration) listBusy.value = false;
  }
}
async function loadDetail(id: string) {
  const generation = ++detailGeneration;
  detailBusy.value = true;
  detailError.value = "";
  try {
    const value = await source!.loadTask(id);
    if (!disposed && generation === detailGeneration && taskId.value === id)
      detail.value = value;
  } catch (failure) {
    if (!disposed && generation === detailGeneration && taskId.value === id)
      detailError.value = failureMessage(failure);
  } finally {
    if (!disposed && generation === detailGeneration) detailBusy.value = false;
  }
}
function refresh() {
  if (taskId.value) void loadDetail(taskId.value);
  else void loadList();
}
function openTask(id: string) {
  listScroll = window.scrollY;
  lastOpened = id;
  void router.push(`/messages/tasks/${encodeURIComponent(id)}`);
}
function toggleResult(id: string) {
  if (expanded.value.has(id)) expanded.value.delete(id);
  else expanded.value.add(id);
}
watch(
  taskId,
  async (id, previous) => {
    detailGeneration++;
    detail.value = undefined;
    detailError.value = "";
    expanded.value.clear();
    if (id) {
      await loadDetail(id);
      await nextTick();
      if (!disposed && taskId.value === id) title.value?.focus({ preventScroll: true });
    } else {
      await loadList();
      await nextTick();
      if (!disposed && !taskId.value && previous) {
        document
          .getElementById(`message-task-${lastOpened}`)
          ?.focus({ preventScroll: true });
        window.scrollTo({ top: listScroll, left: 0, behavior: "instant" });
      }
    }
  },
  { immediate: true }
);
onBeforeUnmount(() => {
  disposed = true;
  listGeneration++;
  detailGeneration++;
});
</script>

<template>
  <div class="message-tasks" data-testid="message-task-workspace">
    <div class="task-context">
      <span class="task-project">PROJECT <strong>messages</strong></span
      ><span v-if="source.mode === 'mock'" class="task-mock">Mock 数据</span
      ><span v-if="source.mode === 'mock'" class="task-context-note"
        >本地交互预览 · 不会实际发送</span
      >
    </div>
    <template v-if="!taskId">
      <header class="task-heading">
        <div>
          <h1>消息任务</h1>
          <p>创建发送任务，查看调度状态与后续回执。</p>
        </div>
        <div class="task-actions">
          <el-button :icon="Refresh" :loading="listBusy" @click="refresh"
            >刷新</el-button
          ><el-button type="primary" :icon="Plus" @click="creating = true"
            >创建消息任务</el-button
          >
        </div>
      </header>
      <el-alert
        v-if="listError"
        :title="listError"
        type="error"
        :closable="false"
        role="alert"
      />
      <section
        class="task-surface"
        aria-label="Messages 任务列表"
        :aria-busy="listBusy"
      >
        <div class="task-toolbar">
          <el-input
            v-model="query"
            :prefix-icon="Search"
            clearable
            placeholder="搜索任务名称或 Task ID"
            aria-label="搜索任务"
            class="task-search"
          />
          <el-select v-model="stateFilter" aria-label="筛选任务状态" class="task-filter"
            ><el-option label="全部任务状态" value="all" /><el-option
              v-for="(label, state) in taskStateLabels"
              :key="state"
              :label="label"
              :value="state" /><el-option label="状态不可用" value="unavailable"
          /></el-select>
          <span class="task-window">当前已加载 {{ tasks.length }} 个任务</span>
        </div>
        <p class="task-narrow-hint">表格可横向滚动</p>
        <div class="task-table-scroll" tabindex="0" aria-label="任务表格，可横向滚动">
          <table class="message-task-table">
            <thead>
              <tr>
                <th scope="col">任务</th>
                <th scope="col">创建时间</th>
                <th scope="col">收件国家</th>
                <th scope="col">发送范围</th>
                <th scope="col">发送总数</th>
                <th scope="col">发送成功数</th>
                <th scope="col">Task 状态</th>
                <th scope="col"><span class="task-sr-only">查看详情</span></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="task in filtered"
                :key="task.taskId"
                data-testid="message-task-row"
              >
                <td class="task-identity">
                  <button
                    :id="`message-task-${task.taskId}`"
                    class="task-name"
                    @click="openTask(task.taskId)"
                  >
                    {{ task.name ?? task.taskId }}</button
                  ><span v-if="task.name" class="task-id">{{ task.taskId }}</span
                  ><span v-if="task.managed" class="managed-label">managed Task</span>
                </td>
                <td class="task-date">{{ date(task.createdAtMillis) }}</td>
                <td>{{ task.recipientCountry ?? "—" }}</td>
                <td>{{ senderRange(task.senderCountry) }}</td>
                <td>{{ task.sendTotal?.toLocaleString() ?? "—" }}</td>
                <td class="task-delivered-count">
                  {{ task.deliveredCount?.toLocaleString() ?? "—" }}
                </td>
                <td>
                  <span class="task-state" :data-state="task.state">{{
                    taskStateLabel(task.state)
                  }}</span>
                </td>
                <td>
                  <el-button
                    link
                    :icon="ArrowRight"
                    :aria-label="`查看 ${task.name ?? task.taskId}`"
                    @click="openTask(task.taskId)"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="filtered.length === 0" class="task-empty" role="status">
          <el-icon><Tickets /></el-icon
          ><strong>{{
            listBusy
              ? "正在读取任务…"
              : listError && !loaded
                ? "任务列表暂不可用"
                : tasks.length
                  ? "没有符合筛选条件的任务"
                  : "还没有消息任务"
          }}</strong
          ><span>{{
            tasks.length
              ? "搜索与筛选仅作用于当前已加载的任务。"
              : "创建一个消息任务，从一份号码文件开始。"
          }}</span
          ><el-button
            v-if="!tasks.length && !listBusy && !listError"
            @click="creating = true"
            >创建消息任务</el-button
          >
        </div>
        <footer class="task-table-footer">
          <span>发送成功以送达回执为准，已读／已回复也计入。</span>
          <span>{{ filtered.length }} 个任务 · 搜索与筛选仅作用于当前列表</span
          ><span v-if="truncated" role="status"
            >仅显示最新 100 个任务，列表已截断。</span
          >
        </footer>
      </section>
    </template>
    <template v-else>
      <router-link to="/messages" class="task-back"
        ><el-icon><ArrowLeft /></el-icon>返回消息任务</router-link
      >
      <header class="task-heading task-detail-heading">
        <div>
          <h1 ref="title" tabindex="-1">{{ detail?.task.name ?? taskId }}</h1>
          <p class="task-detail-id">
            {{ taskId
            }}<span v-if="detail?.task.managed" class="managed-label"
              >managed Task</span
            >
          </p>
        </div>
        <div class="task-actions">
          <span v-if="detail" class="task-state" :data-state="detail.task.state">{{
            taskStateLabel(detail.task.state)
          }}</span
          ><el-button :icon="Refresh" :loading="detailBusy" @click="refresh"
            >刷新</el-button
          >
        </div>
      </header>
      <el-alert
        v-if="detailError"
        :title="detailError"
        type="error"
        :closable="false"
        role="alert"
      />
      <template v-if="detail">
        <section class="task-surface task-overview" aria-label="任务信息">
          <dl>
            <div>
              <dt>创建时间</dt>
              <dd>{{ date(detail.task.createdAtMillis) }}</dd>
            </div>
            <div>
              <dt>WorkerGroup</dt>
              <dd>{{ detail.task.workerGroupId }}</dd>
            </div>
            <div>
              <dt>收件国家</dt>
              <dd>{{ detail.task.recipientCountry ?? "—" }}</dd>
            </div>
            <div>
              <dt>发送范围</dt>
              <dd>{{ senderRange(detail.task.senderCountry) }}</dd>
            </div>
            <div>
              <dt>发送总数</dt>
              <dd data-testid="send-total">
                {{ detail.task.sendTotal?.toLocaleString() ?? "—" }}
              </dd>
            </div>
            <div>
              <dt>发送成功数（已送达）</dt>
              <dd data-testid="delivered-count">
                {{ detail.task.deliveredCount?.toLocaleString() ?? "—" }}
              </dd>
            </div>
          </dl>
          <p v-if="!detail.task.body" class="task-note">
            {{
              detail.task.managed
                ? "这是 Project 的 managed Task，不是消息发送批次。"
                : "业务配置未提供，仍可查看任务状态和已有结果。"
            }}
          </p>
          <details v-if="detail.task.body" class="task-parameters">
            <summary>消息正文与高级参数</summary>
            <pre>{{ detail.task.body }}</pre>
            <p>指定发送号码：{{ detail.task.senderPhone ?? "未指定" }}</p>
          </details>
        </section>
        <section
          class="task-surface task-result-section"
          aria-label="结果预览"
          :aria-busy="detailBusy"
        >
          <div class="task-results-heading">
            <div>
              <h2>
                结果预览<span>{{ detail.results.length }} 条</span>
              </h2>
              <p>已有结果的有界样本，不保证最新顺序或文件顺序，不代表整任务完成率。</p>
            </div>
            <span class="task-preview-limit">最多 100 条</span>
          </div>
          <p v-if="detail.task.state === 'terminal'" class="task-terminal-note">
            调度已结束，后续回执仍可更新。
          </p>
          <p v-if="detail.results.length" class="task-narrow-hint">
            最多 100 条结果 · 表格可横向滚动
          </p>
          <div
            v-if="detail.results.length"
            class="task-table-scroll"
            tabindex="0"
            aria-label="结果表格，可横向滚动"
          >
            <table class="message-task-table result-table">
              <thead>
                <tr>
                  <th scope="col">收件号码</th>
                  <th scope="col">发送状态</th>
                  <th scope="col">回执</th>
                  <th scope="col">实际发送号码</th>
                  <th scope="col">最新回复</th>
                  <th scope="col"><span class="task-sr-only">展开信息</span></th>
                </tr>
              </thead>
              <tbody>
                <template v-for="result in detail.results" :key="result.messageId"
                  ><tr data-testid="message-result-row">
                    <td class="result-number">{{ result.recipientId ?? "—" }}</td>
                    <td>
                      <span
                        class="result-send"
                        :class="{
                          'result-send--failed': result.status === 'EXECUTION_FAILED'
                        }"
                        >{{
                          result.status === "SENT"
                            ? "已发送"
                            : result.status === "EXECUTION_FAILED"
                              ? "失败"
                              : result.status
                                ? "发送成功"
                                : "内容不可解析"
                        }}</span
                      >
                    </td>
                    <td>
                      <span class="result-receipt" :data-receipt="result.status">{{
                        receiptLabel(result.status ?? "")
                      }}</span>
                    </td>
                    <td class="result-number">{{ result.phone ?? "—" }}</td>
                    <td class="result-reply">{{ result.reply ?? "—" }}</td>
                    <td>
                      <button
                        class="result-expand"
                        :aria-label="`${expanded.has(result.messageId) ? '收起' : '展开'} ${result.messageId}`"
                        :aria-expanded="expanded.has(result.messageId)"
                        :aria-controls="`result-${result.messageId}`"
                        @click="toggleResult(result.messageId)"
                      >
                        {{ expanded.has(result.messageId) ? "收起" : "详情" }}
                      </button>
                    </td>
                  </tr>
                  <tr
                    v-if="expanded.has(result.messageId)"
                    :id="`result-${result.messageId}`"
                    class="result-expanded"
                  >
                    <td colspan="6">
                      <dl>
                        <div>
                          <dt>执行 Result</dt>
                          <dd>
                            {{ result.resultStatus === "succeeded" ? "成功" : "失败" }}
                          </dd>
                        </div>
                        <div>
                          <dt>messageId</dt>
                          <dd>{{ result.messageId }}</dd>
                        </div>
                        <div>
                          <dt>Worker ID</dt>
                          <dd>{{ result.workerId ?? "—" }}</dd>
                        </div>
                        <div>
                          <dt>业务观察时间</dt>
                          <dd>
                            {{
                              result.observedAtMillis === undefined
                                ? "—"
                                : date(result.observedAtMillis)
                            }}
                          </dd>
                        </div>
                        <div v-if="result.contentError">
                          <dt>内容读取</dt>
                          <dd>{{ result.contentError }}</dd>
                        </div>
                      </dl>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
          <div v-else class="task-empty" role="status">
            <el-icon><Tickets /></el-icon><strong>尚未观察到 Result</strong
            ><span>空结果不表示失败。可以稍后手动刷新。</span>
          </div>
          <footer class="task-table-footer">
            <span>发送结果、回执和最新回复独立于 Task 调度状态。</span
            ><span v-if="detail.resultsTruncated" role="status">还有结果未展示。</span>
          </footer>
        </section>
      </template>
      <div v-else-if="detailBusy" class="task-surface task-empty" role="status">
        正在读取任务…
      </div>
    </template>
    <MessageTaskCreate
      v-model="creating"
      :source="source"
      @created="(id) => router.push(`/messages/tasks/${encodeURIComponent(id)}`)"
    />
  </div>
</template>

<style scoped>
.message-tasks {
  display: grid;
  gap: 20px;
  min-width: 0;
}
.task-context {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  font-size: 12px;
  color: var(--rv-text-secondary);
}
.task-project {
  display: inline-flex;
  gap: 8px;
  align-items: baseline;
  font-size: 10px;
  letter-spacing: 0.06em;
}
.task-project strong {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
  color: var(--rv-text);
}
.task-mock {
  color: var(--rv-text);
  background: var(--rv-warning-soft);
  border: 1px solid color-mix(in srgb, var(--rv-warning) 35%, transparent);
  border-radius: 5px;
  padding: 3px 7px;
  font-size: 11px;
}
.task-context-note {
  font-size: 11px;
}
.task-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin: 0 0 4px;
}
.task-heading h1 {
  margin: 0;
  font-size: 27px;
  line-height: 1.4;
  letter-spacing: -0.025em;
  overflow-wrap: anywhere;
  outline: none;
}
.task-heading p {
  color: var(--rv-text-secondary);
  font-size: 13px;
  margin: 8px 0 0;
  line-height: 1.7;
}
.task-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-shrink: 0;
}
.task-actions .el-button + .el-button {
  margin-left: 0;
}
.task-surface {
  background: var(--rv-surface);
  border: 1px solid var(--rv-border);
  border-radius: 10px;
  min-width: 0;
  overflow: hidden;
}
.task-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px;
  border-bottom: 1px solid var(--rv-border);
}
.task-search {
  max-width: 320px;
}
.task-filter {
  width: 175px;
  flex-shrink: 0;
}
.task-window {
  margin-left: auto;
  color: var(--rv-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}
.task-table-scroll {
  position: relative;
  overflow-x: auto;
}
.task-narrow-hint {
  display: none;
}
.task-table-scroll:focus-visible {
  outline: 2px solid var(--rv-primary);
  outline-offset: -2px;
}
.message-task-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  font-size: 13px;
  white-space: nowrap;
}
.message-task-table th {
  padding: 13px 18px;
  background: color-mix(in srgb, var(--rv-bg) 65%, var(--rv-surface));
  color: var(--rv-text-secondary);
  font-weight: 500;
  font-size: 12px;
}
.message-task-table td {
  border-top: 1px solid var(--rv-border);
  padding: 17px 18px;
}
.message-task-table th:first-child,
.message-task-table td:first-child {
  padding-left: 24px;
}
.message-task-table tbody > tr:hover {
  background: color-mix(in srgb, var(--rv-primary-soft) 25%, var(--rv-surface));
}
.task-name {
  display: block;
  max-width: 330px;
  background: none;
  border: 0;
  padding: 0;
  color: var(--rv-text);
  font-size: 13px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-name:hover {
  color: var(--rv-primary);
}
.task-name:focus-visible,
.result-expand:focus-visible,
summary:focus-visible {
  outline: 2px solid var(--rv-primary);
  outline-offset: 3px;
}
.task-id {
  display: block;
  color: var(--rv-text-secondary);
  font-size: 11px;
  margin-top: 6px;
  font-family: Consolas, monospace;
}
.task-date {
  color: var(--rv-text-secondary);
  font-size: 12px;
}
.task-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 9px;
  border-radius: 5px;
  font-size: 11px;
  white-space: nowrap;
  background: var(--rv-bg);
  color: var(--rv-text-secondary);
}
.task-state::before {
  content: "";
  height: 5px;
  width: 5px;
  background: currentColor;
  border-radius: 50%;
}
.task-state[data-state="running_visible"],
.task-state[data-state="running-initial"] {
  background: var(--rv-info-soft);
  color: var(--rv-info);
}
.task-state[data-state="pre_review"] {
  background: var(--rv-warning-soft);
  color: var(--rv-text);
}
.managed-label {
  display: inline-block;
  font-size: 10px;
  font-weight: 400;
  color: var(--rv-text-secondary);
  background: var(--rv-bg);
  border: 1px solid var(--rv-border);
  padding: 2px 5px;
  margin-top: 7px;
  border-radius: 4px;
}
.task-table-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid var(--rv-border);
  padding: 14px 20px;
  color: var(--rv-text-secondary);
  font-size: 11px;
  line-height: 1.7;
}
.task-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px 24px;
  text-align: center;
  color: var(--rv-text-secondary);
  font-size: 13px;
}
.task-empty .el-icon {
  font-size: 30px;
  color: var(--rv-text-muted);
  margin-bottom: 3px;
}
.task-empty strong {
  font-weight: 500;
  color: var(--rv-text);
}
.task-back {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  width: fit-content;
  font-size: 12px;
  text-decoration: none;
  color: var(--rv-text-secondary);
  margin-bottom: -5px;
}
.task-back:hover {
  color: var(--rv-primary);
}
.task-detail-id {
  display: flex;
  gap: 10px;
  align-items: center;
  overflow-wrap: anywhere;
}
.task-detail-id .managed-label {
  margin-top: 0;
}
.task-overview {
  padding: 22px 24px;
}
.task-overview dl {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 22px;
  margin: 0;
}
dt {
  color: var(--rv-text-secondary);
  font-size: 11px;
  margin-bottom: 9px;
}
dd {
  font-size: 13px;
  margin: 0;
  overflow-wrap: anywhere;
}
.task-note {
  margin: 20px 0 0;
  font-size: 12px;
  color: var(--rv-text-secondary);
}
.task-parameters {
  margin-top: 22px;
  padding-top: 15px;
  border-top: 1px solid var(--rv-border);
  font-size: 12px;
  color: var(--rv-text-secondary);
}
.task-parameters summary {
  cursor: pointer;
  width: fit-content;
}
.task-parameters pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 16px;
  background: var(--rv-bg);
  border-radius: 6px;
  line-height: 1.7;
  color: var(--rv-text);
}
.task-results-heading {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 22px 24px 18px;
}
.task-results-heading h2 {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 0;
  font-size: 16px;
}
.task-results-heading h2 > span {
  font-size: 11px;
  font-weight: 400;
  color: var(--rv-text-secondary);
  background: var(--rv-bg);
  padding: 3px 7px;
  border-radius: 4px;
}
.task-results-heading p {
  font-size: 12px;
  color: var(--rv-text-secondary);
  margin: 10px 0 0;
  line-height: 1.7;
}
.task-preview-limit {
  white-space: nowrap;
  font-size: 11px;
  color: var(--rv-text-secondary);
}
.task-terminal-note {
  color: var(--rv-text-secondary);
  font-size: 12px;
  margin: 0;
  padding: 0 24px 18px;
}
.result-table td {
  padding-top: 14px;
  padding-bottom: 14px;
}
.result-number {
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}
.result-reply {
  white-space: normal;
  min-width: 170px;
  max-width: 400px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.result-send {
  color: var(--rv-text);
}
.result-send--failed {
  color: var(--el-color-danger);
}
.result-receipt {
  display: inline-block;
  font-size: 11px;
  padding: 4px 7px;
  background: var(--rv-bg);
  border-radius: 4px;
  color: var(--rv-text-secondary);
}
.result-receipt[data-receipt="REPLIED"] {
  color: var(--rv-primary);
  background: var(--rv-primary-soft);
}
.result-expand {
  color: var(--rv-primary);
  font-size: 12px;
  border: 0;
  padding: 4px;
  background: none;
  cursor: pointer;
}
.result-expanded {
  background: var(--rv-bg);
}
.result-expanded dl {
  display: flex;
  flex-wrap: wrap;
  gap: 24px 40px;
  margin: 0;
  white-space: normal;
}
.task-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@media (max-width: 1000px) {
  .task-heading {
    align-items: flex-start;
  }
  .task-overview dl {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .task-window {
    display: none;
  }
}
@media (max-width: 600px) {
  .task-narrow-hint {
    display: block;
    margin: 0;
    padding: 0 18px 12px;
    color: var(--rv-text-secondary);
    font-size: 11px;
  }
  .task-toolbar + .task-narrow-hint {
    padding-top: 12px;
  }
  .message-tasks {
    gap: 16px;
  }
  .task-heading {
    flex-direction: column;
    gap: 16px;
  }
  .task-heading h1 {
    font-size: 24px;
  }
  .task-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .task-detail-heading .task-actions {
    justify-content: space-between;
  }
  .task-toolbar {
    flex-direction: column;
    padding: 14px;
  }
  .task-search,
  .task-filter {
    max-width: none;
    width: 100%;
  }
  .task-context-note {
    flex-basis: 100%;
  }
  .task-overview {
    padding: 18px;
  }
  .task-overview dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .task-results-heading {
    padding: 18px;
    align-items: flex-start;
  }
  .task-preview-limit {
    display: none;
  }
  .task-terminal-note {
    padding-left: 18px;
  }
  .task-detail-id {
    flex-wrap: wrap;
  }
}
</style>
