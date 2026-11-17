<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, reactive, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { captureTaskCaller, consoleIdentity } from "@/console-identity";
import { historySourceKey, historySessionsKey } from "./context";
import { resultDescription } from "@/app-checks/workbench";
import type { CheckResult } from "@/app-checks/model";
import type {
  HistoryEntry,
  HistoryItem,
  HistoryKind,
  HistoryPreview,
  TaskHistorySource
} from "./source";
const props = defineProps<{
  projectId: string;
  kind: HistoryKind;
  runtimePath: string;
  source?: TaskHistorySource;
}>();
const source = props.source ?? inject(historySourceKey)!;
const sessions = inject(historySessionsKey, new Map());
const route = useRoute(),
  router = useRouter();
const caller = captureTaskCaller();
const key = `${consoleIdentity.generation.value}:${props.projectId}:${props.kind}`;
const initial = () => ({
  rows: [] as HistoryEntry[],
  listReadAt: 0,
  listError: "",
  listBusy: false,
  nextCursor: null as string | null,
  details: {} as Record<
    string,
    {
      entry?: HistoryEntry;
      preview?: HistoryPreview;
      items?: HistoryItem[];
      nextItem: string | null;
      readAt: number;
      error: string;
      previewError: string;
      busy: boolean;
    }
  >,
  scroll: 0
});
const state = reactive(sessions.get(key) ?? initial());
sessions.set(key, state);
const selected = computed(() =>
  typeof route.query.historyId === "string" ? route.query.historyId : ""
);
const search = computed({
  get: () =>
    typeof route.query.historyTask === "string" ? route.query.historyTask : "",
  set: (value) =>
    void navigate({ historyTask: value || undefined, historyCursor: undefined })
});
const detail = computed(() => state.details[selected.value]);
const runtime = computed(() => ({
  path: props.runtimePath,
  query: props.kind === "raw" ? { project: props.projectId, tab: "finite" } : {}
}));
let listGeneration = 0,
  detailGeneration = 0,
  disposed = false;
const downloading = reactive({ busy: false, error: "" });
function navigate(query: Record<string, string | undefined>) {
  return router.push({ path: route.path, query: { ...route.query, ...query } });
}
async function loadList() {
  const token = ++listGeneration;
  state.listBusy = true;
  state.listError = "";
  try {
    const page = await source.list(
      caller,
      props.projectId,
      search.value,
      typeof route.query.historyCursor === "string"
        ? route.query.historyCursor
        : undefined
    );
    if (!disposed && token === listGeneration) {
      state.rows = page.entries;
      state.nextCursor = page.nextCursor;
      state.listReadAt = Date.now();
    }
  } catch (error) {
    if (!disposed && token === listGeneration) state.listError = message(error);
  } finally {
    if (!disposed && token === listGeneration) state.listBusy = false;
  }
}
async function loadDetail(cursor?: string) {
  const id = selected.value;
  if (!id) return;
  const token = ++detailGeneration;
  state.details[id] ??= {
    nextItem: null,
    readAt: 0,
    error: "",
    previewError: "",
    busy: false
  };
  const saved = state.details[id];
  saved.busy = true;
  saved.error = "";
  saved.previewError = "";
  try {
    const entry = await source.get(caller, id);
    if (entry.task.projectId !== props.projectId)
      throw new Error("历史不属于当前 Project");
    const [items, preview] = await Promise.allSettled([
      source.items(caller, id, cursor),
      source.preview(caller, id, props.kind)
    ]);
    if (!disposed && token === detailGeneration && selected.value === id) {
      saved.entry = entry;
      if (items.status === "fulfilled") {
        saved.items = items.value.entries;
        saved.nextItem = items.value.nextCursor;
        saved.readAt = Date.now();
      } else saved.error = message(items.reason);
      if (preview.status === "fulfilled") saved.preview = preview.value;
      else saved.previewError = message(preview.reason);
    }
  } catch (error) {
    if (!disposed && token === detailGeneration && selected.value === id)
      saved.error = message(error);
  } finally {
    if (!disposed && token === detailGeneration) saved.busy = false;
  }
}
async function download(filter = "all", raw = false) {
  const id = selected.value;
  if (!detail.value?.entry || detail.value.error || downloading.busy) return;
  downloading.busy = true;
  downloading.error = "";
  try {
    const file = await source.export(caller, id, raw ? "raw" : props.kind, filter);
    if (disposed || selected.value !== id) return;
    const url = URL.createObjectURL(file.blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = file.fileName;
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    if (!disposed && selected.value === id) downloading.error = message(error);
  } finally {
    downloading.busy = false;
  }
}
function message(error: unknown) {
  return error instanceof Error ? error.message : "历史读取失败";
}
function time(value?: number | null) {
  return value ? new Date(value).toLocaleString() : "未读取";
}
function pretty(value: unknown) {
  return JSON.stringify(value, null, 2);
}
function businessAnswer(row: Record<string, unknown>) {
  if (props.kind === "app-checks") return resultDescription(row as CheckResult);
  const stages: Record<string, string> = {
    SENT: "已发送",
    DELIVERED: "已送达",
    READ: "已读",
    REPLIED: "已回复",
    EXECUTION_FAILED: "执行失败"
  };
  if (props.kind === "messages")
    return row.contentError ? "内容异常" : (stages[String(row.status)] ?? "");
  return "";
}
watch(
  () => [route.query.historyCursor, route.query.historyTask],
  () => {
    void loadList();
  },
  { immediate: true }
);
watch(
  selected,
  () => {
    detailGeneration++;
    downloading.error = "";
    void loadDetail();
  },
  { immediate: true }
);
void nextTick(() => window.scrollTo(0, state.scroll));
onBeforeUnmount(() => {
  state.scroll = window.scrollY;
  disposed = true;
  listGeneration++;
  detailGeneration++;
});
</script>

<template>
  <section class="history-workspace" data-testid="task-history">
    <header>
      <h1>历史任务 · {{ projectId }}</h1>
      <router-link :to="runtime">返回当前任务</router-link>
    </header>
    <p>历史保存的是冻结时的输入、状态和实际结果；不支持恢复执行。到期后自动淘汰。</p>
    <p v-if="source.mode === 'mock'">
      Mock 会话内历史样例，不代表真实执行或数据库证明。
    </p>
    <form class="history-controls" @submit.prevent="loadList">
      <label
        >Task ID
        <input
          v-model.lazy="search"
          aria-label="历史 Task ID"
          placeholder="精确 Task ID"
      /></label>
      <el-button native-type="submit" :loading="state.listBusy">刷新历史</el-button>
      <span>最近读取：{{ time(state.listReadAt) }}</span>
    </form>
    <p v-if="state.listError" role="alert">
      历史列表过期，保留旧快照：{{ state.listError }}
    </p>
    <el-table
      :data="state.rows"
      row-key="historyId"
      empty-text="当前范围没有已发布的历史任务"
    >
      <el-table-column label="任务" min-width="220"
        ><template #default="{ row }"
          ><el-button link @click="navigate({ historyId: row.historyId })">{{
            row.task.name || row.taskId
          }}</el-button>
          <div>{{ row.taskId }}</div></template
        ></el-table-column
      >
      <el-table-column prop="task.workerGroupId" label="Group" min-width="150" />
      <el-table-column label="创建者" width="95"
        ><template #default="{ row }">{{
          row.task.metadata.userId ?? "无归属"
        }}</template></el-table-column
      >
      <el-table-column label="首次终止" min-width="175"
        ><template #default="{ row }">{{
          time(row.terminatedAtMillis)
        }}</template></el-table-column
      >
      <el-table-column label="历史到期" min-width="175"
        ><template #default="{ row }">{{
          time(row.expiresAtMillis)
        }}</template></el-table-column
      >
    </el-table>
    <div class="history-controls">
      <el-button
        :disabled="!route.query.historyCursor || state.listBusy"
        @click="navigate({ historyCursor: undefined })"
        >首页</el-button
      ><el-button
        :disabled="!state.nextCursor || state.listBusy || !!state.listError"
        @click="navigate({ historyCursor: state.nextCursor ?? undefined })"
        >下一页</el-button
      ><span>每页最多 20 条，仅查询当前 scope。</span>
    </div>
    <section v-if="selected" class="history-detail">
      <header>
        <h2>保存的任务详情</h2>
        <el-button :loading="detail?.busy" @click="loadDetail()"
          >重新读取详情</el-button
        >
      </header>
      <p v-if="detail?.error" role="alert">详情过期，保留旧快照：{{ detail.error }}</p>
      <p>最近读取：{{ time(detail?.readAt) }}</p>
      <template v-if="detail?.entry">
        <p>
          {{ detail.entry.task.name || detail.entry.taskId }} ·
          {{ detail.entry.task.workerGroupId }}
        </p>
        <p>
          首次终止：{{ time(detail.entry.terminatedAtMillis) }}；冻结：{{
            time(detail.entry.frozenAtMillis)
          }}；保存：{{ time(detail.entry.publishedAtMillis) }}；到期：{{
            time(detail.entry.expiresAtMillis)
          }}
        </p>
        <p>
          Owner 执行项数 {{ detail.entry.counts.total }}；保存输入
          {{ detail.entry.itemRows }}；实际结果
          {{ detail.entry.resultRows }}。这些独立观测不互相补齐。
        </p>
        <details>
          <summary>任务保存配置与状态计数</summary>
          <pre>{{
            pretty({ task: detail.entry.task, counts: detail.entry.counts })
          }}</pre>
        </details>
        <div class="history-controls">
          <el-button
            :disabled="
              !!detail.error ||
              (kind !== 'messages' && !!detail.previewError) ||
              downloading.busy ||
              (source.mode === 'mock' && kind !== 'app-checks')
            "
            @click="download()"
            >{{
              kind === "app-checks" ? "导出全部有效结果 CSV" : "导出完整成功结果 JSONL"
            }}</el-button
          >
          <template v-if="kind === 'app-checks'"
            ><el-button
              :disabled="!!detail.error || !!detail.previewError || downloading.busy"
              @click="download('matched')"
              >导出满足</el-button
            ><el-button
              :disabled="!!detail.error || !!detail.previewError || downloading.busy"
              @click="download('unmatched')"
              >导出不满足</el-button
            ><el-button
              :disabled="!!detail.error || downloading.busy || source.mode === 'mock'"
              @click="download('all', true)"
              >下载原始成功 JSONL</el-button
            ></template
          >
        </div>
        <p v-if="downloading.error" role="alert">{{ downloading.error }}</p>
        <h3>结果预览</h3>
        <p v-if="detail.previewError" role="alert">
          业务预览读取失败，旧预览已过期：{{
            detail.previewError
          }}。原始输入和结果仍可单独查看。
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
          <el-table-column prop="messageId" label="messageId" min-width="160" />
          <el-table-column
            v-if="kind === 'app-checks'"
            prop="seed"
            label="种子"
            min-width="160"
          />
          <el-table-column
            v-if="kind === 'messages'"
            prop="recipientId"
            label="收件人"
            min-width="160"
          />
          <el-table-column label="结果" min-width="280"
            ><template #default="{ row }"
              ><strong v-if="typeof row.matched === 'boolean'">{{
                row.matched ? "满足" : "不满足"
              }}</strong>
              <p v-if="businessAnswer(row)">{{ businessAnswer(row) }}</p>
              <pre>{{ pretty(row.details ?? row.result ?? row) }}</pre>
            </template></el-table-column
          >
        </el-table>
        <h3>原始输入与独立状态</h3>
        <ul class="history-items">
          <li v-for="item in detail.items ?? []" :key="item.messageId">
            <details>
              <summary>
                {{ item.messageId }} · {{ item.result ? "有观测结果" : "未观察到结果" }}
              </summary>
              <pre>{{ pretty(item) }}</pre>
            </details>
          </li>
        </ul>
        <el-button
          :disabled="!detail.nextItem || detail.busy || !!detail.error"
          @click="loadDetail(detail.nextItem ?? undefined)"
          >下一页输入</el-button
        >
      </template>
    </section>
  </section>
</template>
<style scoped>
.history-workspace {
  padding: 24px;
  background: var(--rv-surface);
  border: 1px solid var(--rv-border);
  border-radius: 12px;
}
header,
.history-controls {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
header {
  justify-content: space-between;
}
.history-detail {
  margin-top: 28px;
  border-top: 1px solid var(--rv-border);
  padding-top: 20px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 320px;
  overflow: auto;
}
input {
  padding: 7px 10px;
  margin-left: 8px;
}
.history-items {
  padding-left: 20px;
}
</style>
