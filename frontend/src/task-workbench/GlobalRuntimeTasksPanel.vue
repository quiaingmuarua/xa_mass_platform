<script setup lang="ts">
import TaskRetentionNotice from "@/components/TaskRetentionNotice.vue";
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElInputNumber, ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import "element-plus/es/components/input-number/style/css";
import JsonBlock from "@/components/JsonBlock.vue";
import TaskResultExport from "@/components/TaskResultExport.vue";
import WorkbenchTaskTable from "./components/WorkbenchTaskTable.vue";
import type { TaskRow } from "@/task-management/projects";
import { formatDateTime } from "./presentation";
import { useViewGuard } from "./helpers";
import { useRuntimeViewerStore } from "@/runtime-context";
import { useTaskSession } from "./session";

const session = useTaskSession();
const workspace = session.runtime;
const directory = session.directory;
const globalStore = useRuntimeViewerStore();
const route = useRoute();
const router = useRouter();

const project = computed(() =>
  typeof route.query.project === "string" ? route.query.project : ""
);
const taskId = computed(() =>
  typeof route.query.taskId === "string" ? route.query.taskId : ""
);
const knownProject = computed(() =>
  directory.value?.some((p) => p.projectId === project.value)
);
const detailState = computed(() => workspace.detail.value);
const detail = computed(() => detailState.value.value);
const fresh = computed(
  () => !!detail.value && !detailState.value.loading && !detailState.value.stale
);

session.runtimeFilters["@global"] ??= { search: "", group: "", kind: "", scroll: 0 };
const filter = computed(() => session.runtimeFilters["@global"]!);

const rows = computed<TaskRow[]>(() =>
  globalStore.entries.map((e) => ({
    taskId: e.taskId,
    task: e.task,
    scoreBand: e.scoreBand
  }))
);
const filtered = computed(() =>
  rows.value.filter((row) => {
    const search = filter.value.search.trim().toLowerCase();
    return (
      (!search ||
        [row.taskId, row.task?.name ?? "", row.task?.projectId ?? ""].some((v) =>
          v.toLowerCase().includes(search)
        )) &&
      (!filter.value.group || row.task?.workerGroupId === filter.value.group) &&
      (!filter.value.kind || row.task?.idleDisposition === filter.value.kind)
    );
  })
);
const groups = computed(() => [
  ...new Set(rows.value.flatMap((r) => (r.task ? [r.task.workerGroupId] : [])))
]);
const loading = computed(() =>
  ["loading", "refreshing"].includes(globalStore.taskPreviewState.status)
);
const error = computed(() => globalStore.taskPreviewState.error?.message);
const stale = computed(() => globalStore.taskPreviewState.stale);
const readAt = computed(() => globalStore.taskPreviewState.preview?.generatedAt);

const table = ref<InstanceType<typeof WorkbenchTaskTable>>();
const guard = useViewGuard();
let restoringScroll = true;

async function syncRoute() {
  const current = guard.next();
  const savedScroll = filter.value.scroll;
  restoringScroll = true;
  if (
    !globalStore.taskPreviewState.preview &&
    globalStore.taskPreviewState.status !== "loading"
  )
    void globalStore.initializeTaskView();
  if (taskId.value && knownProject.value) {
    if (workspace.detailKey.value !== project.value + "/" + taskId.value)
      void workspace.loadDetail(project.value, taskId.value);
  } else workspace.clearDetail();
  await nextTick();
  if (guard.isCurrent(current)) await table.value?.restoreScrollTop(savedScroll);
  if (guard.isCurrent(current)) restoringScroll = false;
}

function rememberScroll(event: { scrollTop: number }) {
  if (!restoringScroll && !guard.isDisposed()) filter.value.scroll = event.scrollTop;
}

watch(
  () => [route.fullPath, directory.value],
  () => {
    if (
      route.path === "/runtime/tasks" &&
      (route.query.tab === "global" || route.query.view === "global")
    )
      void syncRoute();
  }
);
onMounted(() => {
  void syncRoute();
});

async function refresh() {
  await globalStore.refreshTasks();
}

function open(row: TaskRow) {
  if (!row.task || !directory.value?.some((p) => p.projectId === row.task?.projectId)) {
    ElMessage.warning("描述符或 Project 绑定不可用，保留当前原始证据。");
    return;
  }
  guard.rememberFocus();
  return router.push({
    query: { tab: "global", project: row.task.projectId, taskId: row.taskId }
  });
}

async function close(done: () => void) {
  const id = taskId.value;
  done();
  if (route.path !== "/runtime/tasks" || !id) return;
  await router.push({ query: { tab: "global" } });
  const current = guard.current();
  await nextTick();
  if (guard.isCurrent(current) && !taskId.value) guard.restoreFocus();
}

function manageLink() {
  const task = detail.value?.task;
  return {
    path: "/runtime/tasks",
    query: {
      project: task?.projectId ?? undefined,
      tab: task?.idleDisposition === "PARK_WHEN_IDLE" ? "rpc" : "finite",
      taskId: detail.value?.taskId
    }
  };
}
</script>
<template>
  <section class="raw-tasks tw-global-panel" data-testid="global-runtime-tasks">
    <div class="tw-action-bar">
      <div class="tw-filters-inline">
        <el-input
          v-model="filter.search"
          placeholder="名称 / 编号 / Project（当前采样）"
          clearable
          aria-label="任务搜索"
        />
        <el-select
          v-model="filter.group"
          placeholder="所有 Group"
          clearable
          aria-label="Group 筛选"
        >
          <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
        </el-select>
        <el-select
          v-model="filter.kind"
          placeholder="全部任务类型"
          clearable
          aria-label="任务类型"
        >
          <el-option label="有限任务" value="CLOSE_WHEN_IDLE" />
          <el-option label="托管任务" value="PARK_WHEN_IDLE" />
        </el-select>
        <el-input-number
          v-model="globalStore.taskSampleLimit"
          :min="1"
          :max="1000"
          aria-label="全局采样上限"
        />
      </div>
      <div class="tw-action-bar__right">
        <span class="raw-muted">最近读取：{{ formatDateTime(readAt, "未提供") }}</span>
        <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </div>

    <p class="raw-muted tw-global-panel__note">
      按 Task Score 从高到低跨 Project 采样（当前
      {{ rows.length }} 条），不代表创建时间或业务优先级；Running Visible
      不证明正在执行。
    </p>

    <el-alert
      v-if="error"
      :title="(stale ? '读取失败，保留旧快照：' : '读取失败：') + error"
      type="warning"
      :closable="false"
    />

    <WorkbenchTaskTable
      ref="table"
      mode="global"
      :rows="filtered"
      :loading="loading"
      :error="error"
      @open="open"
      @scroll="rememberScroll"
    />

    <el-drawer
      :key="taskId"
      :model-value="!!taskId"
      size="760px"
      :title="detail?.task?.name || taskId"
      :before-close="close"
    >
      <el-alert
        v-if="detailState.error"
        :title="(detailState.stale ? '当前快照已过期：' : '') + detailState.error"
        type="error"
        :closable="false"
      />
      <div class="tw-button-row">
        <el-button
          :loading="detailState.loading"
          :icon="Refresh"
          @click="workspace.loadDetail(project, taskId)"
        >
          刷新任务
        </el-button>
        <span class="raw-muted">{{
          formatDateTime(detailState.readAt, "未提供")
        }}</span>
      </div>
      <template v-if="detail">
        <p>
          <code>{{ detail.taskId }}</code>
        </p>
        <p>
          {{ detail.task?.projectId }} / {{ detail.task?.workerGroupId }} ·
          {{ detail.scoreBand ?? "状态不可用" }}
        </p>
        <p v-if="directory.value?.some((p) => p.projectId === detail?.task?.projectId)">
          <router-link class="tw-link" :to="manageLink()">
            在 Project 视图中管理
          </router-link>
        </p>
        <p v-else class="raw-muted">
          任务归属未提供或 Project 不在当前目录中，无法切换至 Project 管理视图。
        </p>
        <TaskRetentionNotice :retention="detail.retention" />
        <TaskResultExport :key="detail.taskId" :task="detail" :fresh="fresh" />
        <JsonBlock :value="detail" />
      </template>
    </el-drawer>
  </section>
</template>
