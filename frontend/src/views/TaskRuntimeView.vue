<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElInputNumber, ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import "element-plus/es/components/input-number/style/css";
import JsonBlock from "@/components/JsonBlock.vue";
import RawTaskTable, { type TaskRow } from "@/components/RawTaskTable.vue";
import TaskResultExport from "@/components/TaskResultExport.vue";
import { useRuntimeViewerStore } from "@/runtime-context";
import { useTaskSession } from "@/task-workbench/session";
import "@/task-workbench/workbench.css";

const session = useTaskSession();
const workspace = session.runtime;
const directory = session.directory;
const globalStore = useRuntimeViewerStore();
const route = useRoute();
const router = useRouter();
const globalView = computed(() => route.query.view === "global");
const project = computed(() =>
  typeof route.query.project === "string" ? route.query.project : ""
);
const taskId = computed(() =>
  typeof route.query.taskId === "string" ? route.query.taskId : ""
);
const knownProject = computed(() =>
  directory.value?.some((p) => p.projectId === project.value)
);
const list = computed(() => workspace.windows[project.value]);
const detailState = computed(() => workspace.detail.value);
const detail = computed(() => detailState.value.value);
const fresh = computed(
  () => !!detail.value && !detailState.value.loading && !detailState.value.stale
);
const filterKey = computed(() => (globalView.value ? "@global" : project.value));
watch(
  filterKey,
  (key) => {
    session.runtimeFilters[key] ??= { search: "", group: "", kind: "", scroll: 0 };
  },
  { immediate: true }
);
const filter = computed(() => session.runtimeFilters[filterKey.value]!);
const rows = computed<TaskRow[]>(() =>
  globalView.value
    ? globalStore.entries.map((e) => ({
        taskId: e.taskId,
        task: e.task,
        scoreBand: e.scoreBand
      }))
    : (list.value?.value?.tasks ?? [])
);
const filtered = computed(() =>
  rows.value.filter((row) => {
    const search = filter.value.search.trim().toLowerCase();
    return (
      (!search ||
        [row.taskId, row.task?.name ?? ""].some((v) =>
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
  globalView.value
    ? ["loading", "refreshing"].includes(globalStore.taskPreviewState.status)
    : list.value?.loading
);
const error = computed(() =>
  globalView.value ? globalStore.taskPreviewState.error?.message : list.value?.error
);
const stale = computed(() =>
  globalView.value ? globalStore.taskPreviewState.stale : list.value?.stale
);
const readAt = computed(() =>
  globalView.value
    ? globalStore.taskPreviewState.preview?.generatedAt
    : list.value?.readAt
);
const table = ref<InstanceType<typeof RawTaskTable>>();
let disposed = false;
let generation = 0;
let restoringScroll = true;
let focusReturn: HTMLElement | undefined;
function time(value?: string | number) {
  return value === undefined ? "未提供" : new Date(value).toLocaleString();
}
async function syncRoute() {
  const current = ++generation;
  const savedScroll = filter.value.scroll;
  restoringScroll = true;
  if (!directory.value) return;
  if (!project.value && !globalView.value) {
    const selected =
      directory.value.find((p) => p.projectId === "default") ?? directory.value[0];
    if (selected) {
      await router.replace({ query: { ...route.query, project: selected.projectId } });
      return;
    }
  }
  session.navigation.runtimeQuery = Object.fromEntries(
    Object.entries(route.query).filter(
      (pair): pair is [string, string] => typeof pair[1] === "string"
    )
  );
  if (globalView.value) {
    if (
      !globalStore.taskPreviewState.preview &&
      globalStore.taskPreviewState.status !== "loading"
    )
      void globalStore.initializeTaskView();
  } else if (knownProject.value && !workspace.windows[project.value])
    void workspace.loadTasks(project.value);
  if (taskId.value && knownProject.value) {
    if (workspace.detailKey.value !== project.value + "/" + taskId.value)
      void workspace.loadDetail(project.value, taskId.value);
  } else workspace.clearDetail();
  await nextTick();
  if (!disposed && current === generation)
    await table.value?.restoreScrollTop(savedScroll);
  if (!disposed && current === generation) restoringScroll = false;
}
function rememberScroll(event: { scrollTop: number }) {
  if (!restoringScroll && !disposed) filter.value.scroll = event.scrollTop;
}
watch(
  () => route.fullPath,
  () => {
    if (route.path === "/runtime/tasks") void syncRoute();
  }
);
onMounted(async () => {
  await session.ensureDirectory();
  if (disposed) return;
  if (
    !Object.keys(route.query).length &&
    Object.keys(session.navigation.runtimeQuery).length
  )
    await router.replace({ query: session.navigation.runtimeQuery });
  else await syncRoute();
});
onBeforeUnmount(() => {
  disposed = true;
  generation++;
});
async function refreshDirectory() {
  await session.loadDirectory();
  if (!disposed) await syncRoute();
}
async function refresh() {
  if (globalView.value) await globalStore.refreshTasks();
  else if (knownProject.value) await workspace.loadTasks(project.value);
}
function selectProject(value: string) {
  return router.push({ query: value ? { project: value } : { view: "global" } });
}
function open(row: TaskRow) {
  if (!row.task || !directory.value?.some((p) => p.projectId === row.task?.projectId)) {
    ElMessage.warning("描述符或 Project 绑定不可用，保留当前原始证据。");
    return;
  }
  focusReturn =
    document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
  return router.push({
    query: { ...route.query, project: row.task.projectId, taskId: row.taskId }
  });
}
async function close(done: () => void) {
  const id = taskId.value;
  done();
  if (route.path !== "/runtime/tasks" || !id) return;
  const query = { ...route.query };
  delete query.taskId;
  await router.push({ query });
  const current = generation;
  await nextTick();
  if (!disposed && current === generation && !taskId.value && focusReturn?.isConnected)
    focusReturn.focus();
}
function workbenchLink() {
  const task = detail.value?.task;
  return {
    path: "/task-workbench",
    query:
      task?.idleDisposition === "PARK_WHEN_IDLE"
        ? { tab: "rpc", group: task.workerGroupId }
        : { tab: "finite", taskId: detail.value?.taskId }
  };
}
</script>
<template>
  <section class="raw-tasks" data-testid="task-runtime-page">
    <header class="raw-tasks__heading">
      <h1>Runtime Tasks</h1>
      <div>
        <span class="raw-muted">最近读取：{{ time(readAt) }}</span
        ><el-button :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </header>
    <el-alert
      v-if="directory.error"
      :title="directory.error"
      type="error"
      :closable="false"
      ><el-button @click="refreshDirectory">重读 Project 目录</el-button></el-alert
    >
    <el-tabs
      :model-value="globalView ? '@global' : project"
      @tab-change="selectProject($event === '@global' ? '' : String($event))"
    >
      <el-tab-pane
        v-for="p in directory.value ?? []"
        :key="p.projectId"
        :name="p.projectId"
        :label="p.projectId"
      />
      <el-tab-pane name="@global" label="全局观察" />
    </el-tabs>
    <el-alert
      v-if="!globalView && directory.value && !knownProject"
      title="Project 未配置或不可用，可重新选择或切换全局观察。"
      type="info"
      :closable="false"
    />
    <p v-if="globalView" class="raw-muted">
      按 Task Score 从高到低采样，不代表创建时间或业务优先级；Running Visible
      不证明正在执行。
    </p>
    <p v-else class="raw-muted">
      当前 Project 最近 {{ rows.length }} 条，最多 100 条；筛选仅作用于当前载入集合。{{
        list?.value?.truncated ? "列表已截断。" : ""
      }}
    </p>
    <el-alert
      v-if="error"
      :title="(stale ? '读取失败，保留旧快照：' : '读取失败：') + error"
      type="warning"
      :closable="false"
    />
    <div class="raw-tasks__filters">
      <el-input
        v-model="filter.search"
        placeholder="名称 / 编号（当前集合）"
        clearable
        aria-label="任务搜索"
      />
      <el-select
        v-model="filter.group"
        placeholder="所有 Group"
        clearable
        aria-label="Group 筛选"
        ><el-option v-for="g in groups" :key="g" :label="g" :value="g"
      /></el-select>
      <el-select
        v-model="filter.kind"
        placeholder="全部任务类型"
        clearable
        aria-label="任务类型"
        ><el-option label="有限任务" value="CLOSE_WHEN_IDLE" /><el-option
          label="托管任务"
          value="PARK_WHEN_IDLE"
      /></el-select>
      <el-input-number
        v-if="globalView"
        v-model="globalStore.taskSampleLimit"
        :min="1"
        :max="1000"
        aria-label="全局采样上限"
      />
    </div>
    <RawTaskTable
      ref="table"
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
      <el-button
        :loading="detailState.loading"
        :icon="Refresh"
        @click="workspace.loadDetail(project, taskId)"
        >刷新任务</el-button
      ><span class="raw-muted"> {{ time(detailState.readAt) }}</span>
      <template v-if="detail">
        <p>
          <code>{{ detail.taskId }}</code>
        </p>
        <p>
          {{ detail.task?.projectId }} / {{ detail.task?.workerGroupId }} ·
          {{ detail.scoreBand ?? "状态不可用" }}
        </p>
        <router-link v-if="detail.task?.projectId === 'default'" :to="workbenchLink()"
          >进入任务工作台</router-link
        >
        <TaskResultExport :key="detail.taskId" :task="detail" :fresh="fresh" />
        <JsonBlock :value="detail" />
      </template>
    </el-drawer>
  </section>
</template>
