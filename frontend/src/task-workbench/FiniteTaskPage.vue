<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus, Refresh } from "@element-plus/icons-vue";
import "element-plus/es/components/message-box/style/css";
import JsonBlock from "@/components/JsonBlock.vue";
import RawTaskInput from "@/components/RawTaskInput.vue";
import RawTaskResults from "@/components/RawTaskResults.vue";
import type { TaskRow } from "@/components/RawTaskTable.vue";
import TaskResultExport from "@/components/TaskResultExport.vue";
import FiniteTaskActions from "./FiniteTaskActions.vue";
import WorkbenchTaskTable from "./components/WorkbenchTaskTable.vue";
import TaskStatusBadge from "./components/TaskStatusBadge.vue";
import TaskStatsCell from "./components/TaskStatsCell.vue";
import TaskDescriptorPanel from "./components/TaskDescriptorPanel.vue";
import {
  formatCompactTime,
  formatDateTime,
  formatDuration,
  isRunningBand,
  presentFiniteStatus
} from "./presentation";
import { useRuntimeViewerConfig, useTaskManagementStore } from "@/runtime-context";
import { canManageRawTask } from "@/stores/task-management";
import type { Project } from "@/task-management/projects";
import { useTaskSession } from "./session";

const props = defineProps<{ project: Project }>();
const projectId = props.project.projectId;
const session = useTaskSession();
const workspace = session.finite;
const filter = session.project(projectId).finiteFilter;
const config = useRuntimeViewerConfig();
const operations = useTaskManagementStore();
const operationState = operations.project(projectId);
const route = useRoute();
const router = useRouter();

const taskId = computed(() =>
  typeof route.query.taskId === "string" ? route.query.taskId : ""
);
const creating = computed(() => route.query.view === "create");
const appending = computed(() => route.query.view === "append");
const list = computed(() => workspace.windows[projectId]);
const groups = computed(() => workspace.groups[projectId]);
const state = computed(() => workspace.detail.value);
const detail = computed(() => state.value.value);
const fresh = computed(
  () =>
    !!detail.value &&
    detail.value.taskId === taskId.value &&
    detail.value.task?.projectId === projectId &&
    !state.value.loading &&
    !state.value.stale
);

type DrawerTab = "overview" | "results" | "append" | "raw";
type ListSegment = "all" | "pre_review" | "succeeded" | "attention";
const drawerTab = ref<DrawerTab>("overview");
const listSegment = ref<ListSegment>("all");

watch(
  () => [taskId.value, appending.value] as const,
  ([id, isAppend], previous) => {
    if (!id) return;
    if (isAppend) drawerTab.value = "append";
    else if (previous?.[0] !== id || previous?.[1]) drawerTab.value = "overview";
  },
  { immediate: true }
);

watch(
  () => [taskId.value, fresh.value],
  () => {
    if (
      fresh.value &&
      detail.value?.retention?.phase !== "RELEASED" &&
      workspace.resultsKey.value !== projectId + "/" + taskId.value
    )
      void workspace.loadResults(projectId, taskId.value);
  }
);

function refreshResults() {
  if (
    detail.value?.task?.projectId === projectId &&
    detail.value?.retention?.phase !== "RELEASED" &&
    detail.value.taskId === taskId.value
  )
    void workspace.loadResults(projectId, taskId.value);
}

const directoryFresh = computed(
  () => !session.directory.stale && !session.directory.loading
);
const api = computed(() => config.mode === "api");
const canWrite = computed(() => api.value && directoryFresh.value);

async function ensureKnownDirectory() {
  if (!canWrite.value || operations.busy) return;
  if (await operations.ensureDirectory(projectId)) await workspace.loadTasks(projectId);
}

const manage = computed(
  () =>
    canWrite.value &&
    !!detail.value &&
    canManageRawTask(detail.value, projectId, fresh.value)
);
const groupFresh = computed(
  () => !!groups.value?.value && !groups.value.stale && !groups.value.loading
);

const rows = computed(() =>
  (list.value?.value?.tasks ?? [])
    .filter((r) => r.task?.idleDisposition !== "PARK_WHEN_IDLE")
    .sort((a, b) => (b.createdAtMillis ?? -1) - (a.createdAtMillis ?? -1))
);

function matchesBaseFilters(r: TaskRow) {
  const search = filter.search.trim().toLowerCase();
  return (
    (!search ||
      [r.taskId, r.task?.name ?? "", r.task?.metadata.userId ?? ""].some((v) =>
        v.toLowerCase().includes(search)
      )) &&
    (!filter.group || r.task?.workerGroupId === filter.group)
  );
}

const runningRows = computed(() =>
  rows.value.filter((r) => isRunningBand(r.scoreBand) && matchesBaseFilters(r))
);

const queuedOrEndedRows = computed(() =>
  rows.value.filter((r) => !isRunningBand(r.scoreBand) && matchesBaseFilters(r))
);

const listCounts = computed(() => {
  const counts = {
    all: queuedOrEndedRows.value.length,
    pre_review: 0,
    succeeded: 0,
    attention: 0
  };
  for (const row of queuedOrEndedRows.value) {
    const category = presentFiniteStatus(row).category;
    if (category === "pre_review") counts.pre_review++;
    else if (category === "succeeded") counts.succeeded++;
    else if (category === "attention") counts.attention++;
  }
  return counts;
});

const tableRows = computed(() => {
  if (listSegment.value === "all") return queuedOrEndedRows.value;
  return queuedOrEndedRows.value.filter(
    (r) => presentFiniteStatus(r).category === listSegment.value
  );
});

const detailDuration = computed(() =>
  formatDuration(
    detail.value?.createdAtMillis,
    detail.value?.retention?.terminatedAtMillis
  )
);

const table = ref<InstanceType<typeof WorkbenchTaskTable>>();
const createHeading = ref<HTMLElement>();
let creatingPreviously = false;
let disposed = false;
let navigation = 0;
let restoringScroll = true;
let focusReturn: HTMLElement | undefined;

function rememberFocus() {
  focusReturn =
    document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
}

async function sync() {
  const generation = ++navigation;
  const savedScroll = filter.scroll;
  restoringScroll = true;
  if (!list.value) void workspace.loadTasks(projectId);
  if (!groups.value) void workspace.loadGroups(projectId);
  if (taskId.value) {
    if (workspace.detailKey.value !== projectId + "/" + taskId.value)
      void workspace.loadDetail(projectId, taskId.value);
  } else workspace.clearDetail();
  await nextTick();
  if (!disposed && generation === navigation) {
    if (creating.value && !creatingPreviously) createHeading.value?.focus();
    creatingPreviously = creating.value;
  }
  if (savedScroll !== 0 && !disposed && generation === navigation)
    await table.value?.restoreScrollTop(savedScroll);
  if (!disposed && generation === navigation) restoringScroll = false;
}

function rememberScroll(event: { scrollTop: number }) {
  if (!restoringScroll && !disposed) filter.scroll = event.scrollTop;
}

watch(
  () => route.fullPath,
  () => {
    if (
      route.path === "/runtime/tasks" &&
      route.query.tab === "finite" &&
      (!route.query.project || route.query.project === projectId)
    )
      void sync();
  }
);
onMounted(sync);
onBeforeUnmount(() => {
  disposed = true;
  navigation++;
});

async function refresh() {
  await Promise.allSettled([
    workspace.loadTasks(projectId),
    workspace.loadGroups(projectId),
    ...(session.directory.stale ? [session.loadDirectory()] : [])
  ]);
}

function open(row: TaskRow) {
  rememberFocus();
  return router.push({
    query: { project: projectId, tab: "finite", taskId: row.taskId }
  });
}

function openCreate() {
  rememberFocus();
  return router.push({ query: { project: projectId, tab: "finite", view: "create" } });
}

async function close(done: () => void) {
  done();
  if (
    disposed ||
    route.path !== "/runtime/tasks" ||
    route.query.tab !== "finite" ||
    (route.query.project && route.query.project !== projectId)
  )
    return;
  await router.push({ query: { project: projectId, tab: "finite" } });
  const generation = navigation;
  await nextTick();
  if (
    !disposed &&
    generation === navigation &&
    !taskId.value &&
    !creating.value &&
    focusReturn?.isConnected
  )
    focusReturn.focus();
}

async function changeTask(action: "approve" | "close", id = taskId.value) {
  if (operations.busy || !canWrite.value) return;
  const generation = navigation;
  await workspace.loadDetail(projectId, id);
  const current = detail.value;
  if (
    disposed ||
    generation !== navigation ||
    !current ||
    current.taskId !== id ||
    !canManageRawTask(current, projectId, fresh.value)
  )
    return;
  try {
    await ElMessageBox.confirm(
      `Project: ${projectId}\nTask: ${id}\nGroup: ${current.task?.workerGroupId}\nItem 数量: ${current.stats?.total ?? "不可用"}\n${action === "approve" ? "批准后开始调度已写入 Items。" : "关闭保留已有结果，已执行操作不会撤回。"}`,
      action === "approve" ? "批准原始任务" : "关闭原始任务",
      { confirmButtonText: "确认", cancelButtonText: "取消", type: "warning" }
    );
    if (
      disposed ||
      generation !== navigation ||
      !canWrite.value ||
      !fresh.value ||
      detail.value !== current
    )
      return;
    await operations.mutate(projectId, current, action, fresh.value);
    await workspace.loadTasks(projectId);
    if (!disposed && generation === navigation)
      await workspace.loadDetail(projectId, id);
  } catch (cause) {
    if (cause instanceof Error && !disposed) ElMessage.error(cause.message);
  }
}

async function action(row: TaskRow, value: string) {
  if (value === "approve" || value === "close") {
    // Approval and close act only on the open, fresh Task: open the row first.
    if (taskId.value !== row.taskId) {
      rememberFocus();
      await router.push({
        query: { project: projectId, tab: "finite", taskId: row.taskId }
      });
      await nextTick();
      if (disposed || taskId.value !== row.taskId) return;
    }
    await changeTask(value, row.taskId);
    return;
  }
  rememberFocus();
  await router.push({
    query: {
      project: projectId,
      tab: "finite",
      taskId: row.taskId,
      view: value === "append" ? "append" : undefined
    }
  });
  if (value === "export") drawerTab.value = "overview";
}

async function afterSubmit(id: string) {
  if (disposed) return;
  const receipt = operationState.submission;
  const generation = navigation;
  const wasOpen = creating.value || taskId.value === id;
  await workspace.loadTasks(projectId);
  if (
    disposed ||
    !wasOpen ||
    generation !== navigation ||
    receipt !== operationState.submission
  )
    return;
  if (receipt?.complete && receipt.taskId === id) {
    await router.push({ query: { project: projectId, tab: "finite", taskId: id } });
    if (!disposed && taskId.value === id) await workspace.loadDetail(projectId, id);
  }
}

function inspect(id: string) {
  return router.push({ query: { project: projectId, tab: "finite", taskId: id } });
}

function returnSubmission() {
  return operationState.submissionKey === "create"
    ? openCreate()
    : router.push({
        query: {
          project: projectId,
          tab: "finite",
          taskId: operationState.submissionKey,
          view: "append"
        }
      });
}
</script>
<template>
  <section class="raw-tasks tw-page" data-testid="finite-task-page">
    <section
      v-show="creating"
      class="tw-panel tw-panel--padded"
      aria-label="创建有限任务"
    >
      <header class="tw-section-header">
        <div>
          <h2 ref="createHeading" tabindex="-1">创建有限任务</h2>
          <p v-if="project.workerGroupSource === 'registered'" class="raw-muted">
            default 可使用当前环境全部已注册 Group；新注册后手动刷新。每个任务绑定一个
            Group。
          </p>
        </div>
        <div class="tw-button-row">
          <el-button
            :loading="groups?.loading"
            @click="workspace.loadGroups(projectId)"
          >
            刷新 Group 能力
          </el-button>
          <el-button @click="close(() => {})">返回列表</el-button>
        </div>
      </header>
      <el-alert
        v-if="groups?.error"
        :title="'Group 能力读取失败：' + groups.error"
        type="error"
        :closable="false"
      />
      <RawTaskInput
        v-if="creating"
        key="create"
        :project-id="projectId"
        :groups="groups?.value ?? []"
        :disabled="!canWrite || !groupFresh"
        :disabled-reason="!api ? 'Mock 仅演示输入与读取，不提交原始任务。' : undefined"
        @submitted="afterSubmit"
        @inspect="inspect"
      />
    </section>

    <div v-show="!creating" class="tw-finite-stack">
      <div class="tw-action-bar">
        <div class="tw-filters-inline">
          <el-input
            v-model="filter.search"
            placeholder="搜索任务名称 / Task ID / 创建者"
            clearable
            aria-label="有限任务搜索"
          />
          <el-select
            v-model="filter.group"
            placeholder="全部 Group"
            clearable
            aria-label="有限任务 Group"
          >
            <el-option
              v-for="g in groups?.value ?? []"
              :key="g.workerGroupId"
              :label="g.workerGroupId"
              :value="g.workerGroupId"
            />
          </el-select>
        </div>
        <div class="tw-button-row">
          <span class="tw-read-at">
            Redis 快照 {{ rows.length }} / 100{{
              list?.value?.truncated ? "（已截断）" : ""
            }}
            · {{ formatCompactTime(list?.readAt, "尚未读取") }}
          </span>
          <el-button
            type="primary"
            :icon="Plus"
            :disabled="!canWrite"
            @click="openCreate"
          >
            创建任务
          </el-button>
          <el-button :icon="Refresh" :loading="list?.loading" @click="refresh">
            刷新
          </el-button>
        </div>
      </div>

      <div
        v-if="list?.error || operationState.error || operationState.submission"
        class="tw-alerts"
      >
        <el-alert
          v-if="list?.error"
          :title="(list.stale ? '读取失败，保留旧快照：' : '读取失败：') + list.error"
          type="error"
          :closable="false"
        />
        <el-alert
          v-if="operationState.error"
          :title="operationState.error.message"
          type="error"
          :closable="false"
        />
        <div v-if="operationState.submission" class="raw-submission tw-submission-bar">
          <span>
            本会话提交：{{ operationState.submission.applied }} 已确认 /
            {{ operationState.submission.total }}；{{
              operationState.submission.error ||
              (operationState.submission.complete ? "本次导入完成" : "未完成")
            }}
          </span>
          <div class="tw-button-row">
            <el-button link @click="returnSubmission">返回提交与核对</el-button>
            <el-button
              v-if="operationState.submission.taskId"
              link
              @click="inspect(operationState.submission.taskId)"
            >
              查看已知任务
            </el-button>
            <el-button
              v-if="
                operationState.submission.taskId && !operationState.submission.complete
              "
              link
              :disabled="operations.busy || !canWrite"
              @click="ensureKnownDirectory"
            >
              核对／补齐任务目录
            </el-button>
          </div>
        </div>
      </div>

      <section
        class="tw-board"
        data-testid="finite-running-board"
        aria-label="执行中任务看板"
      >
        <header class="tw-board__header">
          <div class="tw-board__title">
            <h2>执行中</h2>
            <span class="tw-board__count">{{ runningRows.length }}</span>
          </div>
          <span class="raw-muted">手动刷新更新进度</span>
        </header>

        <div v-if="runningRows.length" class="tw-board__grid">
          <article v-for="row in runningRows" :key="row.taskId" class="tw-run-card">
            <div class="tw-run-card__head">
              <div class="tw-run-card__identity">
                <el-button link type="primary" @click="open(row)">
                  {{ row.task?.name || row.taskId }}
                </el-button>
                <code v-if="row.task?.name" class="tw-cell-id">{{ row.taskId }}</code>
              </div>
              <TaskStatusBadge :row="row" mode="finite" />
            </div>

            <TaskStatsCell :stats="row.stats" />

            <footer class="tw-run-card__foot">
              <div class="tw-run-card__meta">
                <code>{{ row.task?.workerGroupId ?? "—" }}</code>
                <span :title="formatDateTime(row.createdAtMillis)">
                  {{ formatCompactTime(row.createdAtMillis) }}
                </span>
              </div>
              <FiniteTaskActions
                :row="row"
                :writable="
                  canWrite &&
                  !list?.loading &&
                  !list?.stale &&
                  row.task?.projectId === projectId &&
                  row.task.idleDisposition === 'CLOSE_WHEN_IDLE'
                "
                :busy="operations.busy"
                @action="action(row, $event)"
              />
            </footer>
          </article>
        </div>
        <p v-else class="tw-board__empty raw-muted">当前批次没有执行中的任务。</p>
      </section>

      <section class="tw-panel" aria-label="待审核与已结束任务">
        <header class="tw-list-bar">
          <div class="tw-segment" role="group" aria-label="任务状态筛选">
            <button
              type="button"
              :class="{ 'is-active': listSegment === 'all' }"
              @click="listSegment = 'all'"
            >
              全部 <span>{{ listCounts.all }}</span>
            </button>
            <button
              type="button"
              :class="{ 'is-active': listSegment === 'pre_review' }"
              @click="listSegment = 'pre_review'"
            >
              待审核 <span>{{ listCounts.pre_review }}</span>
            </button>
            <button
              type="button"
              :class="{ 'is-active': listSegment === 'succeeded' }"
              @click="listSegment = 'succeeded'"
            >
              已完成 <span>{{ listCounts.succeeded }}</span>
            </button>
            <button
              type="button"
              :class="{ 'is-active': listSegment === 'attention' }"
              @click="listSegment = 'attention'"
            >
              异常与终止 <span>{{ listCounts.attention }}</span>
            </button>
          </div>
          <span class="raw-muted">共 {{ tableRows.length }} 条</span>
        </header>

        <WorkbenchTaskTable
          ref="table"
          mode="finite"
          :rows="tableRows"
          :loading="list?.loading"
          :error="list?.error"
          @open="open"
          @scroll="rememberScroll"
        >
          <template #actions="{ row }">
            <FiniteTaskActions
              :row="row"
              :writable="
                canWrite &&
                !list?.loading &&
                !list?.stale &&
                row.task?.projectId === projectId &&
                row.task.idleDisposition === 'CLOSE_WHEN_IDLE'
              "
              :busy="operations.busy"
              @action="action(row, $event)"
            />
          </template>
        </WorkbenchTaskTable>
      </section>
    </div>

    <el-drawer
      :key="taskId"
      :model-value="!!taskId"
      size="760px"
      :title="detail?.task?.name || taskId"
      :before-close="close"
    >
      <div class="tw-drawer-body">
        <el-alert
          v-if="state.error"
          :title="(state.stale ? '当前快照已过期：' : '') + state.error"
          type="error"
          :closable="false"
        />

        <header class="tw-drawer-summary">
          <div class="tw-drawer-summary__top">
            <TaskStatusBadge v-if="detail" :row="detail" mode="finite" show-raw-band />
            <span v-else class="raw-muted">正在读取任务详情…</span>
            <div class="tw-button-row">
              <FiniteTaskActions
                v-if="detail && detail.task?.idleDisposition === 'CLOSE_WHEN_IDLE'"
                :row="detail"
                :writable="manage"
                :busy="operations.busy"
                @action="action(detail, $event)"
              />
              <el-button
                size="small"
                :loading="state.loading"
                :icon="Refresh"
                @click="workspace.loadDetail(projectId, taskId)"
              >
                刷新任务
              </el-button>
            </div>
          </div>

          <template v-if="detail">
            <TaskStatsCell :stats="detail.stats" detailed />
            <div class="tw-drawer-summary__times">
              <span>创建 {{ formatDateTime(detail.createdAtMillis, "未提供") }}</span>
              <span>
                结束
                {{
                  detail.retention?.terminatedAtMillis != null
                    ? formatDateTime(detail.retention.terminatedAtMillis)
                    : detail.scoreBand === "terminal"
                      ? "未记录"
                      : "尚未结束"
                }}
              </span>
              <span v-if="detailDuration" title="包含待审核等待与实际调度时长">
                总跨度 {{ detailDuration }}
              </span>
              <span class="tw-read-at">
                快照读取 {{ formatDateTime(state.readAt, "尚未读取") }}
              </span>
            </div>
            <p
              v-if="detail.task?.idleDisposition !== 'CLOSE_WHEN_IDLE'"
              class="raw-muted"
            >
              该记录不是可管理的有限任务，或描述符不可用。
            </p>
          </template>
        </header>

        <template v-if="detail">
          <el-tabs v-model="drawerTab" class="tw-drawer-tabs">
            <el-tab-pane label="概览与配置" name="overview">
              <div class="tw-drawer-pane">
                <div class="tw-drawer-links">
                  <TaskResultExport
                    :key="detail.taskId"
                    :task="detail"
                    :fresh="fresh"
                  />
                  <router-link
                    class="tw-link"
                    :to="{
                      path: '/runtime/tasks',
                      query: { project: projectId, taskId: detail.taskId }
                    }"
                  >
                    查看 Runtime 原始观察
                  </router-link>
                </div>
                <TaskDescriptorPanel :task="detail" mode="finite" />
              </div>
            </el-tab-pane>

            <el-tab-pane label="结果预览" name="results">
              <div class="tw-drawer-pane">
                <el-alert
                  v-if="detail.retention?.phase === 'RELEASED'"
                  title="该任务的输入与结果已归档释放，请前往历史任务入口查看完整记录。"
                  type="info"
                  :closable="false"
                />
                <RawTaskResults
                  v-else-if="workspace.resultsKey.value === projectId + '/' + taskId"
                  :key="detail.taskId"
                  :state="workspace.results.value"
                  @refresh="refreshResults"
                />
                <p v-else class="raw-muted">正在准备结果预览…</p>
              </div>
            </el-tab-pane>

            <el-tab-pane
              v-if="detail.task?.idleDisposition === 'CLOSE_WHEN_IDLE'"
              label="追加 Items"
              name="append"
            >
              <div class="tw-drawer-pane">
                <el-alert
                  v-if="groups?.error"
                  :title="groups.error"
                  type="error"
                  :closable="false"
                >
                  <el-button @click="workspace.loadGroups(projectId)">
                    重读 Group 能力
                  </el-button>
                </el-alert>
                <el-alert
                  v-if="detail.scoreBand !== 'pre_review'"
                  title="仅待审核（pre_review）的原始有限任务支持追加 Items。"
                  type="info"
                  :closable="false"
                />
                <RawTaskInput
                  v-if="appending || drawerTab === 'append'"
                  :key="detail.taskId"
                  :project-id="projectId"
                  :groups="groups?.value ?? []"
                  :task="detail"
                  :disabled="
                    !manage || detail.scoreBand !== 'pre_review' || !groupFresh
                  "
                  @submitted="afterSubmit"
                  @inspect="inspect"
                />
              </div>
            </el-tab-pane>

            <el-tab-pane label="原始任务信息" name="raw">
              <div class="tw-drawer-pane">
                <JsonBlock :value="detail" />
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-drawer>
  </section>
</template>
