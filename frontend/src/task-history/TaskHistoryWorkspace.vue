<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, reactive, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { canUseConsole } from "@/access-policy";
import { captureTaskCaller, consoleIdentity } from "@/console-identity";
import { ProjectRequestError } from "@/task-management/projects";
import { resultDescription } from "@/app-checks/workbench";
import type { CheckResult } from "@/app-checks/model";
import { historySourceKey, historySessionsKey, type HistorySession } from "./context";
import { historyKind, historySourceLabel, matchesRuntime } from "./navigation";
import type { HistoryFilters, TaskHistorySource } from "./source";

const props = defineProps<{
  projectId?: string;
  runtimePath?: string;
  source?: TaskHistorySource;
}>();
const source = props.source ?? inject(historySourceKey)!;
const sessions = inject(historySessionsKey, new Map<string, HistorySession>());
const route = useRoute(),
  router = useRouter();
const caller = captureTaskCaller();
const identityGeneration = consoleIdentity.generation.value;
const platformView = computed(() => !props.projectId);
const canReadRuntime = canUseConsole(consoleIdentity.current.value, "runtime.view");
const queryText = (name: string) =>
  typeof route.query[name] === "string" ? (route.query[name] as string) : "";
const filters = computed<HistoryFilters>(() => ({
  projectId: props.projectId || queryText("project") || undefined,
  userId: platformView.value ? queryText("userId") || undefined : undefined,
  taskId: queryText(platformView.value ? "taskId" : "historyTask") || undefined
}));
const cursor = computed(() =>
  queryText(platformView.value ? "cursor" : "historyCursor")
);
const selected = computed(() => queryText("historyId"));
const draft = reactive({ projectId: "", userId: "", taskId: "" });
const state = computed(() => {
  const key = JSON.stringify([
    identityGeneration,
    props.projectId ?? null,
    filters.value,
    cursor.value
  ]);
  let value = sessions.get(key);
  if (!value) {
    value = reactive<HistorySession>({
      rows: [],
      listReadAt: 0,
      listError: "",
      listBusy: false,
      listVersion: 0,
      nextCursor: null,
      details: {},
      scroll: 0
    });
    sessions.set(key, value);
  }
  return value;
});
const detail = computed(() => state.value.details[selected.value]);
const kind = computed(() =>
  detail.value?.entry ? historyKind(detail.value.entry) : "raw"
);
let disposed = false;
let focusReturn: HTMLElement | undefined;
const active = () =>
  !disposed && consoleIdentity.generation.value === identityGeneration;
const downloading = reactive({ busy: false, error: "" });
const runtimeRead = reactive({ busy: false, message: "" });
const canExport = computed(
  () =>
    !!detail.value?.entry &&
    !detail.value.error &&
    !detail.value.busy &&
    !downloading.busy
);

function navigate(query: Record<string, string | undefined>) {
  return router.push({ path: route.path, query: { ...route.query, ...query } });
}
async function applyFilters() {
  const next = {
    project: platformView.value ? draft.projectId.trim() || undefined : undefined,
    userId: platformView.value ? draft.userId.trim() || undefined : undefined,
    [platformView.value ? "taskId" : "historyTask"]: draft.taskId.trim() || undefined,
    cursor: undefined,
    historyCursor: undefined,
    historyId: undefined
  };
  const before = state.value;
  await navigate(next);
  if (state.value === before) void loadList(before);
}
function page(next?: string) {
  return navigate({
    [platformView.value ? "cursor" : "historyCursor"]: next,
    historyId: undefined
  });
}
function openDetail(id: string) {
  focusReturn =
    document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
  return navigate({ historyId: id });
}
function closeDetail() {
  return navigate({ historyId: undefined });
}

async function loadList(target = state.value) {
  const version = ++target.listVersion;
  const requested = { ...filters.value },
    after = cursor.value;
  target.listBusy = true;
  try {
    const result = await source.list(caller, requested, after || undefined);
    if (active() && version === target.listVersion) {
      target.rows = result.entries;
      target.nextCursor = result.nextCursor;
      target.listReadAt = Date.now();
      target.listError = "";
    }
  } catch (error) {
    if (active() && version === target.listVersion) target.listError = message(error);
  } finally {
    if (version === target.listVersion) target.listBusy = false;
  }
}
async function loadDetail() {
  const id = selected.value,
    owner = state.value;
  if (!id) return;
  owner.details[id] ??= {
    nextItem: null,
    readAt: 0,
    previewReadAt: 0,
    error: "",
    previewError: "",
    busy: false,
    version: 0,
    tab: "summary"
  };
  const saved = owner.details[id],
    version = ++saved.version;
  const current = () => active() && saved.version === version;
  saved.busy = true;
  try {
    const entry = await source.get(caller, id);
    if (props.projectId && entry.task.projectId !== props.projectId)
      throw new Error("历史不属于当前场景");
    const [items, preview] = await Promise.allSettled([
      source.items(caller, id),
      source.preview(caller, id, historyKind(entry))
    ]);
    if (!current()) return;
    saved.entry = entry;
    if (items.status === "fulfilled") {
      saved.items = items.value.entries;
      saved.nextItem = items.value.nextCursor;
      saved.readAt = Date.now();
      saved.error = "";
    } else saved.error = message(items.reason);
    if (preview.status === "fulfilled") {
      saved.preview = preview.value;
      saved.previewReadAt = Date.now();
      saved.previewError = "";
    } else saved.previewError = message(preview.reason);
  } catch (error) {
    if (current()) saved.error = message(error);
  } finally {
    if (saved.version === version) saved.busy = false;
  }
}
async function nextInputs() {
  const saved = detail.value,
    id = selected.value;
  if (!saved?.nextItem || saved.busy) return;
  const version = ++saved.version;
  saved.busy = true;
  try {
    const result = await source.items(caller, id, saved.nextItem);
    if (active() && version === saved.version) {
      saved.items = result.entries;
      saved.nextItem = result.nextCursor;
      saved.readAt = Date.now();
    }
  } catch (error) {
    if (active() && version === saved.version) saved.error = message(error);
  } finally {
    if (saved.version === version) saved.busy = false;
  }
}
async function download(filter = "all", raw = false) {
  if (!canExport.value || (!raw && detail.value?.previewError)) return;
  const owner = state.value,
    saved = detail.value,
    id = selected.value;
  if (!saved?.entry) return;
  const requestedKind = raw ? "raw" : kind.value;
  downloading.busy = true;
  downloading.error = "";
  const current = () =>
    active() &&
    state.value === owner &&
    selected.value === id &&
    saved === detail.value;
  try {
    const file = await source.export(caller, id, requestedKind, filter);
    if (!current() || saved.error) return;
    const url = URL.createObjectURL(file.blob);
    try {
      const link = document.createElement("a");
      link.href = url;
      link.download = file.fileName;
      link.click();
    } finally {
      URL.revokeObjectURL(url);
    }
  } catch (error) {
    if (current()) downloading.error = message(error);
  } finally {
    downloading.busy = false;
  }
}
async function openRuntime() {
  const saved = detail.value?.entry,
    id = selected.value,
    owner = state.value;
  if (
    !canReadRuntime ||
    !saved ||
    detail.value?.error ||
    detail.value?.busy ||
    runtimeRead.busy
  )
    return;
  if (!saved.task.projectId || saved.createdAtMillis === null) {
    runtimeRead.message =
      "快照缺少创建身份，无法自动关联 Runtime；可以复制 Task ID 手动核对。";
    return;
  }
  const current = () => active() && state.value === owner && selected.value === id;
  runtimeRead.busy = true;
  runtimeRead.message = "";
  try {
    const value = await source.runtimeTask(caller, saved.task.projectId, saved.taskId);
    if (!current()) return;
    if (!value.task) runtimeRead.message = "Runtime 记录已不存在；历史仍可读取。";
    else if (!matchesRuntime(saved, value))
      runtimeRead.message = "当前 Runtime 创建身份与历史不同，未跳转。";
    else
      await router.push({
        path: "/runtime/tasks",
        query: { project: saved.task.projectId, tab: "finite", taskId: saved.taskId }
      });
  } catch (error) {
    if (current())
      runtimeRead.message =
        error instanceof ProjectRequestError && error.code === 12002
          ? "Runtime 记录已不存在；历史仍可读取。"
          : "Runtime 读取未成功，不能判断记录已删除：" + message(error);
  } finally {
    runtimeRead.busy = false;
  }
}
function message(error: unknown) {
  return error instanceof Error ? error.message : "历史读取失败";
}
function time(value?: number | null) {
  return value == null || value === 0 ? "未读取" : new Date(value).toLocaleString();
}
function pretty(value: unknown) {
  return JSON.stringify(value, null, 2);
}
function answer(row: Record<string, unknown>) {
  if (kind.value === "app-checks") return resultDescription(row as CheckResult);
  const stages: Record<string, string> = {
    SENT: "已发送",
    DELIVERED: "已送达",
    READ: "已读",
    REPLIED: "已回复",
    EXECUTION_FAILED: "执行失败"
  };
  return row.contentError ? "内容异常" : (stages[String(row.status)] ?? "");
}
watch(
  () => JSON.stringify(filters.value),
  () =>
    Object.assign(draft, {
      projectId: filters.value.projectId ?? "",
      userId: filters.value.userId ?? "",
      taskId: filters.value.taskId ?? ""
    }),
  { immediate: true }
);
watch(
  state,
  (value, previous) => {
    if (previous) previous.scroll = window.scrollY;
    void loadList(value);
    void nextTick(() => {
      if (active() && state.value === value) window.scrollTo(0, value.scroll);
    });
  },
  { immediate: true }
);
watch(
  () => [state.value, selected.value] as const,
  () => {
    downloading.error = "";
    runtimeRead.message = "";
    if (selected.value) void loadDetail();
    else void nextTick(() => focusReturn?.isConnected && focusReturn.focus());
  },
  { immediate: true }
);
onBeforeUnmount(() => {
  state.value.scroll = window.scrollY;
  disposed = true;
});
</script>

<template>
  <section class="history-workspace" data-testid="task-history">
    <header class="history-header">
      <div>
        <p class="history-eyebrow">STORAGE · 冻结快照</p>
        <h1>
          任务历史<span v-if="projectId"> · {{ projectId }}</span>
        </h1>
      </div>
      <router-link v-if="runtimePath" :to="runtimePath">返回当前任务</router-link>
    </header>
    <p class="history-note">
      当前 scope
      内已发布的有限任务历史。RPC、尚未归档及已过期记录不在此列表；历史只读，不恢复执行。
    </p>
    <p v-if="source.mode === 'mock'" class="history-note">
      Mock 会话内历史样例，不代表真实执行或数据库证明。
    </p>
    <form class="history-controls" @submit.prevent="applyFilters">
      <label v-if="platformView"
        >Project ID<input
          v-model="draft.projectId"
          aria-label="历史 Project ID"
          placeholder="全部 Project"
      /></label>
      <label v-if="platformView"
        >创建者 ID<input
          v-model="draft.userId"
          aria-label="历史创建者 ID"
          placeholder="全部创建者"
      /></label>
      <label
        >Task ID<input
          v-model="draft.taskId"
          aria-label="历史 Task ID"
          placeholder="精确 Task ID"
      /></label>
      <el-button native-type="submit" type="primary">查询</el-button>
      <el-button :loading="state.listBusy" @click="loadList()">刷新历史</el-button>
    </form>
    <p class="history-note">最近读取：{{ time(state.listReadAt) }} · 每页最多 20 条</p>
    <p v-if="state.listError" class="history-error" role="alert">
      历史列表过期，保留旧快照：{{ state.listError }}
    </p>
    <el-table
      :data="state.rows"
      row-key="historyId"
      empty-text="当前筛选没有已发布的历史任务"
    >
      <el-table-column label="任务" min-width="210"
        ><template #default="{ row }">
          <el-button link @click="openDetail(row.historyId)">{{
            row.task.name || row.taskId
          }}</el-button>
          <div class="history-id">{{ row.taskId }}</div>
        </template></el-table-column
      >
      <el-table-column prop="task.projectId" label="Project" min-width="110" />
      <el-table-column label="来源" min-width="105"
        ><template #default="{ row }">{{
          historySourceLabel(row)
        }}</template></el-table-column
      >
      <el-table-column prop="task.workerGroupId" label="Group" min-width="130" />
      <el-table-column label="创建者" width="95"
        ><template #default="{ row }">{{
          row.task.metadata.userId ?? "无归属"
        }}</template></el-table-column
      >
      <el-table-column prop="counts.total" label="执行项" width="90" />
      <el-table-column label="首次终止" min-width="165"
        ><template #default="{ row }">{{
          time(row.terminatedAtMillis)
        }}</template></el-table-column
      >
      <el-table-column label="历史到期" min-width="165"
        ><template #default="{ row }">{{
          time(row.expiresAtMillis)
        }}</template></el-table-column
      >
    </el-table>
    <div class="history-controls history-pagination">
      <el-button :disabled="!cursor || state.listBusy" @click="page()">首页</el-button>
      <el-button
        :disabled="!state.nextCursor || state.listBusy || !!state.listError"
        @click="page(state.nextCursor ?? undefined)"
        >下一页</el-button
      >
    </div>
    <el-drawer
      :model-value="!!selected"
      title="历史任务 · 只读详情"
      size="min(1100px, 96vw)"
      :close-on-click-modal="false"
      @update:model-value="(open: boolean) => !open && closeDetail()"
    >
      <section class="history-detail">
        <div class="history-controls">
          <el-button :loading="detail?.busy" @click="loadDetail"
            >重新读取详情</el-button
          >
          <el-button
            v-if="canReadRuntime && detail?.entry"
            :loading="runtimeRead.busy"
            :disabled="!!detail.error || detail.busy"
            @click="openRuntime"
            >查看 Runtime 记录</el-button
          >
        </div>
        <p v-if="runtimeRead.message" role="status">{{ runtimeRead.message }}</p>
        <p v-if="detail?.error" class="history-error" role="alert">
          详情过期，保留旧快照：{{ detail.error }}
        </p>
        <p class="history-note">最近读取：{{ time(detail?.readAt) }}</p>
        <template v-if="detail?.entry">
          <h2>{{ detail.entry.task.name || detail.entry.taskId }}</h2>
          <p class="history-id">
            {{ detail.entry.taskId }} · {{ detail.entry.historyId }}
          </p>
          <el-tabs v-model="detail.tab">
            <el-tab-pane label="概览" name="summary">
              <dl class="history-summary">
                <dt>Project / 创建者</dt>
                <dd>
                  {{ detail.entry.task.projectId ?? "无归属" }} /
                  {{ detail.entry.task.metadata.userId ?? "无归属" }}
                </dd>
                <dt>来源 / Group</dt>
                <dd>
                  {{ historySourceLabel(detail.entry) }} /
                  {{ detail.entry.task.workerGroupId }}
                </dd>
                <dt>冻结时执行状态</dt>
                <dd>{{ detail.entry.scoreBand }}</dd>
                <dt>首次终止</dt>
                <dd>{{ time(detail.entry.terminatedAtMillis) }}</dd>
                <dt>冻结 / 发布</dt>
                <dd>
                  {{ time(detail.entry.frozenAtMillis) }} /
                  {{ time(detail.entry.publishedAtMillis) }}
                </dd>
                <dt>历史到期</dt>
                <dd>{{ time(detail.entry.expiresAtMillis) }}</dd>
              </dl>
              <div class="history-counts">
                <span
                  >快照执行项 <strong>{{ detail.entry.counts.total }}</strong></span
                >
                <span
                  >保存输入 <strong>{{ detail.entry.itemRows }}</strong></span
                >
                <span
                  >实际结果 <strong>{{ detail.entry.resultRows }}</strong></span
                >
              </div>
              <p class="history-note">
                三种数量是独立观测；没有结果不等于执行失败。当前配置和运行状态不会覆盖此快照。
              </p>
              <h3>保存的执行配置</h3>
              <pre>{{
                pretty({
                  group: detail.entry.task.workerGroupId,
                  config: detail.entry.task.config,
                  refill: detail.entry.task.refill
                })
              }}</pre>
              <h3>冻结时状态计数</h3>
              <pre>{{ pretty(detail.entry.counts) }}</pre>
            </el-tab-pane>
            <el-tab-pane label="结果" name="results">
              <div class="history-controls">
                <template v-if="kind === 'app-checks'">
                  <el-button
                    :disabled="!canExport || !!detail.previewError"
                    @click="download()"
                    >导出全部有效结果 CSV</el-button
                  >
                  <el-button
                    :disabled="!canExport || !!detail.previewError"
                    @click="download('matched')"
                    >导出满足</el-button
                  >
                  <el-button
                    :disabled="!canExport || !!detail.previewError"
                    @click="download('unmatched')"
                    >导出不满足</el-button
                  >
                </template>
                <el-button
                  :disabled="!canExport || source.mode === 'mock'"
                  @click="download('all', true)"
                  >下载原始成功 JSONL</el-button
                >
              </div>
              <p v-if="downloading.error" class="history-error" role="alert">
                {{ downloading.error }}
              </p>
              <p class="history-note">
                结果最近读取：{{
                  time(detail.previewReadAt)
                }}。完整导出不受当前页或预览上限影响。
              </p>
              <p v-if="detail.previewError" class="history-error" role="alert">
                业务预览读取失败，旧预览已过期：{{
                  detail.previewError
                }}。原始内容仍可在“输入”中查看，原始导出保持独立。
              </p>
              <p v-if="detail.preview?.truncated">
                预览已截断，最多 100 条；完整导出不受预览影响。
              </p>
              <el-table
                :data="detail.preview?.results ?? []"
                :row-key="
                  (row: Record<string, unknown>) =>
                    JSON.stringify([row.messageId, row.seed ?? null])
                "
              >
                <el-table-column prop="messageId" label="messageId" min-width="140" />
                <el-table-column
                  v-if="kind === 'app-checks'"
                  prop="seed"
                  label="种子"
                  min-width="130"
                />
                <el-table-column
                  v-if="kind === 'messages'"
                  prop="recipientId"
                  label="收件人"
                  min-width="140"
                />
                <el-table-column label="结果" min-width="260"
                  ><template #default="{ row }">
                    <strong v-if="typeof row.matched === 'boolean'">{{
                      row.matched ? "满足" : "不满足"
                    }}</strong>
                    <p v-if="answer(row)">{{ answer(row) }}</p>
                    <pre>{{ pretty(row.details ?? row.result ?? row) }}</pre>
                  </template></el-table-column
                >
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="输入" name="inputs">
              <h3>原始输入与独立状态</h3>
              <p class="history-note">
                按执行项分页；组内种子保持原始结构。未观察到 Result 时不生成业务答案。
              </p>
              <ul class="history-items">
                <li v-for="item in detail.items ?? []" :key="item.messageId">
                  <details>
                    <summary>
                      {{ item.messageId }} ·
                      {{ item.result ? "有观测结果" : "未观察到结果" }}
                    </summary>
                    <pre>{{ pretty(item) }}</pre>
                  </details>
                </li>
              </ul>
              <el-button
                :disabled="!detail.nextItem || detail.busy || !!detail.error"
                @click="nextInputs"
                >下一页输入</el-button
              >
            </el-tab-pane>
            <el-tab-pane label="技术详情" name="technical">
              <pre>{{ pretty(detail.entry) }}</pre>
            </el-tab-pane>
          </el-tabs>
        </template>
        <p v-else>{{ detail?.busy ? "正在读取历史…" : "没有可用历史快照。" }}</p>
      </section>
    </el-drawer>
  </section>
</template>

<style scoped>
.history-workspace {
  padding: 24px;
  background: var(--rv-surface);
  border: 1px solid var(--rv-border);
  border-radius: 12px;
}
.history-header,
.history-controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.history-header {
  justify-content: space-between;
}
.history-header h1 {
  margin: 4px 0;
}
.history-eyebrow,
.history-note,
.history-id {
  color: var(--el-text-color-secondary);
}
.history-eyebrow {
  font-size: 12px;
  letter-spacing: 0.08em;
}
.history-id {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.history-error {
  color: var(--el-color-danger);
}
.history-controls label {
  display: grid;
  gap: 5px;
  font-size: 13px;
}
.history-controls input {
  padding: 8px 10px;
  border: 1px solid var(--rv-border);
  border-radius: 6px;
  background: var(--rv-surface);
  color: inherit;
}
.history-pagination {
  margin: 16px 0 0;
}
.history-summary {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 12px 20px;
}
.history-summary dt {
  color: var(--el-text-color-secondary);
}
.history-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.history-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  padding: 16px;
  border: 1px solid var(--rv-border);
  border-radius: 8px;
}
.history-counts strong {
  margin-left: 6px;
}
.history-items {
  padding-left: 20px;
}
.history-items li {
  margin: 12px 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 360px;
  overflow: auto;
}
@media (max-width: 600px) {
  .history-workspace {
    padding: 14px;
  }
  .history-summary {
    grid-template-columns: 1fr;
    gap: 6px;
  }
}
</style>
