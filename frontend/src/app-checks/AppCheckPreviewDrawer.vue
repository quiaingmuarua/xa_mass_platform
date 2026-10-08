<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  reactive,
  ref,
  shallowRef,
  watch
} from "vue";
import { ElDrawer } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import "element-plus/es/components/drawer/style/css";
import { appLabel, countryLabel, type CheckDetail, type CheckTask } from "./model";
import type { AppCheckTaskSource } from "./task-source";
import type { ImportSnapshot } from "./import-model";
import {
  filterResults,
  resultKind,
  resultLabels,
  quantity,
  timestamp,
  taskStage,
  type ResultFilter,
  type TaskActivity,
  type TaskAction
} from "./workbench";
import TaskStatus from "./TaskStatus.vue";
import TaskActions from "./TaskActions.vue";
import ImportSummary from "./ImportSummary.vue";
import AppCheckDiagnostics from "./AppCheckDiagnostics.vue";
const props = defineProps<{
  source: AppCheckTaskSource;
  taskId?: string;
  actionBusy: boolean;
  submissionBusy: boolean;
  actionError: string;
  notice: string;
}>();
const emit = defineEmits<{
  close: [];
  closed: [];
  dismissNotice: [];
  action: [task: CheckTask, action: TaskAction, trigger?: HTMLElement];
  demo: [action: "advance" | "complete" | "late"];
  loaded: [task: CheckTask | undefined, stale: boolean];
}>();
const detail = shallowRef<CheckDetail>();
const busy = ref(false),
  error = ref(""),
  readAt = ref(0),
  heading = ref<HTMLElement>();
const query = ref(""),
  filter = ref<ResultFilter>("all");
const importOpen = ref(false),
  activityOpen = ref(false);
const imported = shallowRef<ImportSnapshot>(),
  activities = shallowRef<TaskActivity[]>([]);
const auxiliary = reactive({
  import: { busy: false, error: "", loaded: false },
  activity: { busy: false, error: "", loaded: false }
});
type Auxiliary = keyof typeof auxiliary;
let generation = 0,
  navigation = 0,
  disposed = false;
const auxiliaryGeneration = { import: 0, activity: 0 };
const preview = computed(() =>
  detail.value ? (props.source.previewState?.(detail.value.task.taskId) ?? {}) : {}
);
const stage = computed(() => (detail.value ? taskStage(detail.value.task) : "unknown"));
const resultPreview = computed(() => detail.value?.results.slice(0, 100) ?? []);
const rows = computed(() =>
  filterResults(resultPreview.value, query.value, filter.value)
);
const truncated = computed(
  () =>
    !!detail.value &&
    (detail.value.resultsTruncated || detail.value.results.length > 100)
);
function invalidateAuxiliary() {
  for (const kind of ["import", "activity"] as const) {
    auxiliaryGeneration[kind]++;
    auxiliary[kind].busy = false;
    auxiliary[kind].loaded = false;
  }
}
async function readAuxiliary(kind: Auxiliary) {
  const id = props.taskId;
  if (!id || !detail.value) return;
  const token = ++auxiliaryGeneration[kind],
    state = auxiliary[kind];
  state.busy = true;
  state.error = "";
  try {
    const value =
      kind === "import"
        ? await props.source.loadImport?.(id)
        : await props.source.loadActivity?.(id);
    if (disposed || token !== auxiliaryGeneration[kind] || id !== props.taskId) return;
    if (kind === "import") imported.value = value as ImportSnapshot | undefined;
    else activities.value = (value as TaskActivity[] | undefined) ?? [];
    state.loaded = true;
  } catch (failure) {
    if (!disposed && token === auxiliaryGeneration[kind] && id === props.taskId)
      state.error = failure instanceof Error ? failure.message : "信息读取失败";
  } finally {
    if (token === auxiliaryGeneration[kind]) state.busy = false;
  }
}
function toggleAuxiliary(kind: Auxiliary, event: Event) {
  const open = (event.target as HTMLDetailsElement).open;
  if (kind === "import") importOpen.value = open;
  else activityOpen.value = open;
  if (open && !auxiliary[kind].loaded && !auxiliary[kind].busy && !busy.value)
    void readAuxiliary(kind);
}
async function refresh() {
  const id = props.taskId;
  if (!id) return;
  const token = ++generation;
  invalidateAuxiliary();
  busy.value = true;
  error.value = "";
  emit("loaded", detail.value?.task, true);
  try {
    const value = await props.source.loadTask(id);
    if (disposed || token !== generation || id !== props.taskId) return;
    const previous = detail.value?.task.state;
    if (
      !detail.value ||
      (previous === "pre_review" && value.task.state !== "pre_review")
    )
      importOpen.value = value.task.state === "pre_review";
    detail.value = value;
    readAt.value = Date.now();
    emit("loaded", value.task, false);
    if (importOpen.value) void readAuxiliary("import");
    if (activityOpen.value) void readAuxiliary("activity");
  } catch (failure) {
    if (!disposed && token === generation && id === props.taskId) {
      error.value = failure instanceof Error ? failure.message : "任务读取失败";
      emit("loaded", detail.value?.task, true);
    }
  } finally {
    if (token === generation) busy.value = false;
  }
}
function focusHeading() {
  heading.value?.focus({ preventScroll: true });
}
watch(
  () => props.taskId,
  async (id) => {
    const visit = ++navigation;
    generation++;
    invalidateAuxiliary();
    emit("loaded", undefined, true);
    if (!id) {
      busy.value = false;
      return;
    }
    detail.value = undefined;
    imported.value = undefined;
    activities.value = [];
    importOpen.value = false;
    activityOpen.value = false;
    auxiliary.import.error = "";
    auxiliary.activity.error = "";
    query.value = "";
    filter.value = "all";
    readAt.value = 0;
    await refresh();
    await nextTick();
    if (!disposed && visit === navigation && props.taskId === id) focusHeading();
  },
  { immediate: true }
);
function closed() {
  if (props.taskId) return;
  detail.value = undefined;
  emit("closed");
}
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  navigation++;
  invalidateAuxiliary();
});
defineExpose({ refresh, focusHeading });
</script>
<template>
  <ElDrawer
    :model-value="!!taskId"
    :title="detail?.task.name || taskId || '结果预览'"
    class="checks-preview-drawer"
    size="760px"
    append-to-body
    destroy-on-close
    :close-on-click-modal="!actionBusy"
    :close-on-press-escape="!actionBusy"
    :show-close="!actionBusy"
    :before-close="() => emit('close')"
    @closed="closed"
  >
    <template #header="{ titleId }">
      <h2 :id="titleId" ref="heading" tabindex="-1" class="checks-preview-title">
        {{ detail?.task.name || taskId || "结果预览" }}
      </h2>
    </template>
    <div class="checks-preview-content">
      <div v-if="!detail" class="checks-empty">
        <h3>{{ busy ? "正在读取任务…" : "暂时无法读取任务" }}</h3>
        <p v-if="error" role="alert" class="checks-error">{{ error }}</p>
        <el-button v-if="!busy" @click="refresh">重新读取</el-button>
      </div>
      <template v-else>
        <header class="checks-preview-meta">
          <div>
            <div class="checks-title-line">
              <span
                >{{ appLabel(detail.task.appId) }} ·
                {{ countryLabel(detail.task.country) }}</span
              ><TaskStatus :task="detail.task" :preview="preview" />
            </div>
            <p class="checks-hint">
              号码总量 {{ quantity(detail.task.totalCount) }} · 未结束
              {{ quantity(detail.task.activeCount) }}
            </p>
            <small class="checks-hint">最近读取 {{ timestamp(readAt) }}</small>
          </div>
          <div class="checks-actions">
            <el-button
              :icon="Refresh"
              :loading="busy"
              :disabled="actionBusy"
              @click="refresh"
              >刷新</el-button
            >
            <TaskActions
              :task="detail.task"
              :source="source"
              hide-preview
              :blocked="actionBusy || submissionBusy || busy || !!error"
              @action="
                (action, trigger) => emit('action', detail!.task, action, trigger)
              "
            />
          </div>
        </header>
        <p v-if="error" class="checks-error" role="alert">
          当前显示上次快照，状态变更已禁用。{{ error }}
        </p>
        <p v-if="actionError" class="checks-error" role="alert">{{ actionError }}</p>
        <div v-if="notice" class="checks-notice" role="status">
          <span>{{ notice }}</span
          ><button
            class="checks-link"
            aria-label="关闭操作提示"
            @click="emit('dismissNotice')"
          >
            关闭
          </button>
        </div>
        <p v-if="stage === 'pre_review'" class="checks-hint">
          等待核对并启动。请确认应用、地区和号码数量，再通过“操作”菜单启动。
        </p>
        <section
          v-if="stage !== 'pre_review' || resultPreview.length"
          aria-label="结果预览"
        >
          <div class="checks-result-toolbar">
            <label class="checks-search"
              ><Search /><input
                v-model="query"
                aria-label="搜索查询号码"
                placeholder="筛选当前预览号码"
            /></label>
            <select v-model="filter" aria-label="筛选查询结果">
              <option v-for="(label, key) in resultLabels" :key="key" :value="key">
                {{ label }}
              </option>
            </select>
            <span class="checks-preview-count" role="status"
              >当前预览 {{ quantity(resultPreview.length) }} 条 · 预览内匹配
              {{ quantity(rows.length) }} 条</span
            >
          </div>
          <p v-if="truncated" class="checks-table-note">
            本次未完整展示，不能据此统计全部注册状态。
          </p>
          <div
            class="checks-table-scroll checks-preview-scroll"
            tabindex="0"
            aria-label="结果预览，可滚动"
          >
            <table class="checks-table checks-results-table">
              <thead>
                <tr>
                  <th>号码</th>
                  <th>查询结果</th>
                  <th>说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.messageId">
                  <td class="checks-phone">{{ row.number || "号码不可用" }}</td>
                  <td>
                    <span class="checks-answer" :class="'answer-' + resultKind(row)">{{
                      resultLabels[resultKind(row)]
                    }}</span>
                  </td>
                  <td>
                    {{
                      row.resultStatus === "failed"
                        ? "执行失败 · 无注册答案"
                        : row.contentError ||
                          (typeof row.registered === "boolean"
                            ? "执行成功"
                            : "执行成功，尚无有效业务答案")
                    }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="!rows.length" class="checks-empty">
            <h3>
              {{
                query || filter !== "all"
                  ? "当前预览中没有匹配结果"
                  : "当前暂无可预览结果"
              }}
            </h3>
            <p>预览仅覆盖本次读取的数据，不能据此判断整个任务的注册情况。</p>
          </div>
        </section>
        <details
          class="checks-auxiliary"
          :open="importOpen"
          @toggle="toggleAuxiliary('import', $event)"
        >
          <summary>导入摘要</summary>
          <p v-if="auxiliary.import.busy" role="status">正在读取导入摘要…</p>
          <p v-if="auxiliary.import.error" class="checks-error" role="alert">
            {{ auxiliary.import.error }}
            <button class="checks-link" @click="readAuxiliary('import')">重试</button>
          </p>
          <template v-if="imported"
            ><h3>{{ imported.sourceFile }}</h3>
            <ImportSummary
              :summary="imported.summary"
              :final-count="imported.summary.validCount"
          /></template>
          <p v-else-if="auxiliary.import.loaded" class="checks-hint">
            当前数据源未提供导入摘要。不会从任务数量或查询结果重建输入历史。
          </p>
        </details>
        <details
          class="checks-auxiliary"
          :open="activityOpen"
          @toggle="toggleAuxiliary('activity', $event)"
        >
          <summary>任务记录</summary>
          <p v-if="auxiliary.activity.busy" role="status">正在读取任务记录…</p>
          <p v-if="auxiliary.activity.error" class="checks-error" role="alert">
            {{ auxiliary.activity.error }}
            <button class="checks-link" @click="readAuxiliary('activity')">重试</button>
          </p>
          <template v-if="auxiliary.activity.loaded">
            <p class="checks-hint">
              {{
                source.loadActivity
                  ? "当前 Mock 会话内最近 100 条实际演示操作。"
                  : "当前 API 未提供完整操作历史，仅显示已知创建时间。"
              }}
            </p>
            <ol class="checks-timeline">
              <li v-if="!activities.some((row) => row.label === '创建任务')">
                <time>{{ timestamp(detail.task.createdAtMillis) }}</time
                ><strong>创建任务</strong>
              </li>
              <li v-for="(row, index) in activities" :key="index">
                <time>{{ timestamp(row.at) }}</time
                ><strong>{{ row.label }}</strong>
                <p v-if="row.detail">{{ row.detail }}</p>
              </li>
            </ol>
          </template>
        </details>
        <AppCheckDiagnostics :detail="detail" :busy="busy">
          <div v-if="source.mode === 'mock'" class="checks-demo-tools">
            <strong>演示控制</strong>
            <div class="checks-actions">
              <el-button
                :disabled="
                  actionBusy || submissionBusy || busy || !!error || stage !== 'running'
                "
                @click="emit('demo', 'advance')"
                >推进一批结果</el-button
              >
              <el-button
                :disabled="
                  actionBusy || submissionBusy || busy || !!error || stage !== 'running'
                "
                @click="emit('demo', 'complete')"
                >完成剩余查询</el-button
              >
              <el-button
                :disabled="
                  actionBusy ||
                  submissionBusy ||
                  busy ||
                  !!error ||
                  preview.endReason !== 'stopped' ||
                  !detail.task.activeCount
                "
                @click="emit('demo', 'late')"
                >已有执行返回结果</el-button
              >
            </div>
            <p class="checks-hint">仅本地 Mock 演示，明确操作时推进。</p>
          </div>
        </AppCheckDiagnostics>
      </template>
    </div>
  </ElDrawer>
</template>
