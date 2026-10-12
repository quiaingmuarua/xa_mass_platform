<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch
} from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ElMessageBox,
  ElMessage,
  ElInputNumber,
  ElLoading,
  type TableInstance
} from "element-plus";
import { Refresh, Plus } from "@element-plus/icons-vue";
import "element-plus/es/components/input-number/style/css";
import "element-plus/es/components/message-box/style/css";
import "element-plus/es/components/loading/style/css";
import JsonBlock from "@/components/JsonBlock.vue";
import TaskCallDebug from "@/components/TaskCallDebug.vue";
import RawTaskInput from "@/components/RawTaskInput.vue";
import {
  useRuntimeViewerConfig,
  useRuntimeViewerStore,
  useTaskManagementStore
} from "@/runtime-context";
import { createProjectSource, type ProjectTask } from "@/task-management/projects";
import { createProjectWorkspace } from "@/task-management/workspace";
import { canManageRawTask } from "@/stores/task-management";
import type { TaskRuntimePreviewEntry } from "@/runtime-viewer/types";

const config = useRuntimeViewerConfig();
const vLoading = ElLoading.directive;
const globalStore = useRuntimeViewerStore();
const operations = useTaskManagementStore();
const route = useRoute();
const router = useRouter();
const workspace = createProjectWorkspace(
  createProjectSource(config.apiBaseUrl, config.mode)
);
const globalView = computed(() => route.query.view === "global");
const selectedProject = computed(() =>
  typeof route.query.project === "string" ? route.query.project : ""
);
const taskId = computed(() =>
  typeof route.query.taskId === "string" ? route.query.taskId : ""
);
const creating = computed(() => route.query.view === "create");
const knownProject = computed(
  () =>
    workspace.directory.value?.some((p) => p.projectId === selectedProject.value) ??
    false
);
const windowState = computed(() => workspace.windows[selectedProject.value]);
const groupState = computed(() => workspace.groups[selectedProject.value]);
const defaultGroups = computed(() => workspace.groups.default?.value ?? []);
const detail = computed(() => workspace.detail.value.value);
const detailState = computed(() => workspace.detail.value);
const api = computed(() => config.mode === "api");
const freshDetail = computed(
  () => !detailState.value.loading && !detailState.value.stale && !!detail.value
);
const manage = computed(
  () => api.value && !!detail.value && canManageRawTask(detail.value, freshDetail.value)
);
const showAppend = ref(false);
const detailTab = ref("overview");
const table = ref<TableInstance>();
const filters = reactive<
  Record<string, { search: string; group: string; kind: string; scroll: number }>
>({});
const filterKey = computed(() =>
  globalView.value ? "@global" : selectedProject.value
);
watch(
  filterKey,
  (key) => {
    filters[key] ??= { search: "", group: "", kind: "", scroll: 0 };
  },
  { immediate: true }
);
const filter = computed(() => filters[filterKey.value]!);
type Row = Omit<ProjectTask, "createdAtMillis"> & { createdAtMillis?: number };
const rows = computed<Row[]>(() =>
  globalView.value
    ? globalStore.entries.map((e) => ({
        taskId: e.taskId,
        task: e.task,
        scoreBand: e.scoreBand
      }))
    : (windowState.value?.value?.tasks ?? [])
);
const filtered = computed(() =>
  rows.value.filter((row) => {
    const query = filter.value.search.trim().toLowerCase();
    return (
      (!query ||
        [row.taskId, row.task?.name ?? ""].some((v) =>
          v.toLowerCase().includes(query)
        )) &&
      (!filter.value.group || row.task?.workerGroupId === filter.value.group) &&
      (!filter.value.kind || row.task?.idleDisposition === filter.value.kind)
    );
  })
);
const groupsInRows = computed(() => [
  ...new Set(rows.value.flatMap((r) => (r.task ? [r.task.workerGroupId] : [])))
]);
const listLoading = computed(() =>
  globalView.value
    ? ["loading", "refreshing"].includes(globalStore.taskPreviewState.status)
    : windowState.value?.loading
);
const listError = computed(() =>
  globalView.value
    ? globalStore.taskPreviewState.error?.message
    : windowState.value?.error
);
const listStale = computed(() =>
  globalView.value ? globalStore.taskPreviewState.stale : windowState.value?.stale
);
const listReadAt = computed(() =>
  globalView.value
    ? globalStore.taskPreviewState.preview?.generatedAt
    : windowState.value?.readAt
);
const debugEntry = computed<TaskRuntimePreviewEntry | undefined>(() => {
  const row = detail.value;
  const group = groupState.value?.value?.find(
    (g) => g.workerGroupId === row?.task?.workerGroupId
  )?.workerGroup;
  return row?.task?.idleDisposition === "PARK_WHEN_IDLE" && row.scoreBand && group
    ? {
        taskId: row.taskId,
        task: row.task,
        scoreBand: row.scoreBand,
        workerGroup: group
      }
    : undefined;
});
let disposed = false;
let navigation = 0;
let focusReturn: HTMLElement | undefined;
function rememberFocus() {
  focusReturn =
    document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
}
function time(value?: string | number) {
  return value === undefined ? "未提供" : new Date(value).toLocaleString();
}

async function syncRoute() {
  const generation = ++navigation;
  if (!workspace.directory.value) return;
  if (!selectedProject.value && !globalView.value) {
    const project =
      workspace.directory.value.find((p) => p.projectId === "default") ??
      workspace.directory.value[0];
    if (project) {
      await router.replace({ query: { ...route.query, project: project.projectId } });
      return;
    }
  }
  if (globalView.value) {
    if (
      !globalStore.taskPreviewState.preview &&
      globalStore.taskPreviewState.status !== "loading"
    )
      void globalStore.initializeTaskView();
  } else if (knownProject.value) {
    if (!workspace.windows[selectedProject.value])
      void workspace.loadTasks(selectedProject.value);
    if (!workspace.groups[selectedProject.value])
      void workspace.loadGroups(selectedProject.value);
  }
  if (creating.value && !workspace.groups.default) void workspace.loadGroups("default");
  showAppend.value = false;
  detailTab.value = "overview";
  if (taskId.value && knownProject.value) {
    if (!workspace.groups[selectedProject.value])
      void workspace.loadGroups(selectedProject.value);
    if (workspace.detailKey.value !== selectedProject.value + "/" + taskId.value)
      void workspace.loadDetail(selectedProject.value, taskId.value);
  } else workspace.clearDetail();
  await nextTick();
  if (!disposed && generation === navigation)
    table.value?.setScrollTop(filter.value.scroll);
}
watch(
  () => [route.query.project, route.query.taskId, route.query.view],
  () => void syncRoute()
);
onMounted(async () => {
  await workspace.loadDirectory();
  if (!disposed) await syncRoute();
});
onBeforeUnmount(() => {
  disposed = true;
  navigation++;
  workspace.dispose();
});
async function refreshDirectory() {
  await workspace.loadDirectory();
  if (!disposed) await syncRoute();
}
async function refreshList() {
  if (globalView.value) await globalStore.refreshTasks();
  else if (knownProject.value)
    await Promise.allSettled([
      workspace.loadTasks(selectedProject.value),
      workspace.loadGroups(selectedProject.value)
    ]);
}
function selectProject(value: string) {
  return router.push({ query: value ? { project: value } : { view: "global" } });
}
function openTask(row: Row) {
  rememberFocus();
  if (
    !row.task ||
    !workspace.directory.value?.some((p) => p.projectId === row.task?.projectId)
  ) {
    ElMessage.warning(
      "Task 描述符或当前 Project 绑定不可用；当前行仅展示已有原始证据。"
    );
    return;
  }
  return router.push({
    query: {
      ...route.query,
      project: row.task.projectId,
      taskId: row.taskId,
      view: globalView.value ? "global" : undefined
    }
  });
}
function openCreate() {
  rememberFocus();
  return router.push({ query: { project: "default", view: "create" } });
}
async function closeDrawer() {
  const query = { ...route.query };
  delete query.taskId;
  if (query.view === "create") delete query.view;
  await router.push({ query });
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
function closeCreateDrawer(done: () => void) {
  done();
  if (creating.value) void closeDrawer();
}
function closeDetailDrawer(done: () => void) {
  done();
  if (taskId.value) void closeDrawer();
}
async function refreshDetail() {
  if (knownProject.value && taskId.value)
    await workspace.loadDetail(selectedProject.value, taskId.value);
}
async function changeTask(action: "approve" | "close") {
  if (!detail.value || operations.busy) return;
  const project = selectedProject.value;
  const id = taskId.value;
  await workspace.loadDetail(project, id);
  const current = detail.value;
  if (
    taskId.value !== id ||
    selectedProject.value !== project ||
    !current ||
    !canManageRawTask(current, freshDetail.value)
  )
    return;
  try {
    await ElMessageBox.confirm(
      "Task: " +
        current.taskId +
        "\nGroup: " +
        current.task?.workerGroupId +
        (action === "approve"
          ? "\n批准后开始调度已写入 Items。"
          : "\n关闭保留已有结果，已执行操作不会撤回。"),
      action === "approve" ? "批准原始任务" : "关闭原始任务",
      { confirmButtonText: "确认", cancelButtonText: "取消", type: "warning" }
    );
    if (taskId.value !== id || selectedProject.value !== project) return;
    await operations.mutate(current, action, freshDetail.value);
    await Promise.allSettled([refreshDetail(), refreshList()]);
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message);
  }
}
async function exportTask() {
  if (!detail.value) return;
  try {
    const result = await operations.exportTask(detail.value, freshDetail.value);
    if (!result) return;
    const url = URL.createObjectURL(result.blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = result.fileName;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导出失败");
  }
}
async function afterSubmit(id: string) {
  const receipt = operations.submission;
  const generation = navigation;
  const wasOpen = creating.value || taskId.value === id;
  await workspace.loadTasks("default");
  if (
    disposed ||
    !wasOpen ||
    generation !== navigation ||
    receipt !== operations.submission
  )
    return;
  if (receipt?.complete && receipt.taskId === id) {
    showAppend.value = false;
    await router.push({ query: { project: "default", taskId: id } });
    await workspace.loadDetail("default", id);
  }
}
function inspectSubmission(id: string) {
  return router.push({ query: { project: "default", taskId: id } });
}
function scroll(event: { scrollTop: number }) {
  filter.value.scroll = event.scrollTop;
}
</script>
<template>
  <section class="raw-tasks" data-testid="task-runtime-page">
    <header class="raw-tasks__heading">
      <h1>Runtime Tasks</h1>
      <div>
        <span class="raw-muted">最近读取：{{ time(listReadAt) }}</span>
        <el-button
          v-if="selectedProject === 'default' && !globalView"
          :icon="Plus"
          :disabled="!api || !knownProject"
          @click="openCreate"
          >创建原始任务</el-button
        >
        <el-button :icon="Refresh" :loading="listLoading" @click="refreshList"
          >刷新</el-button
        >
      </div>
    </header>
    <el-alert
      v-if="workspace.directory.error"
      :title="workspace.directory.error"
      type="error"
      :closable="false"
      ><el-button @click="refreshDirectory">重读 Project 目录</el-button></el-alert
    >
    <el-tabs
      :model-value="globalView ? '@global' : selectedProject"
      @tab-change="selectProject($event === '@global' ? '' : String($event))"
    >
      <el-tab-pane
        v-for="project in workspace.directory.value ?? []"
        :key="project.projectId"
        :name="project.projectId"
        :label="project.projectId"
      />
      <el-tab-pane name="@global" label="全局观察" />
    </el-tabs>
    <el-alert
      v-if="!globalView && workspace.directory.value && !knownProject"
      type="info"
      :closable="false"
      :title="
        selectedProject
          ? '该 Project 不在当前启动目录中，请重新选择。'
          : '尚未配置 Project，可切换到全局观察。'
      "
    />
    <p v-if="globalView" class="raw-muted">
      按 Task Score 从高到低采样，不代表创建时间或业务优先级；Running Visible
      不证明正在执行。
    </p>
    <p v-else class="raw-muted">
      当前 Project 最近 {{ rows.length }} 条，最多 100 条；筛选仅作用于当前载入集合。{{
        windowState?.value?.truncated ? "列表已截断。" : ""
      }}
    </p>
    <el-alert
      v-if="listError"
      :title="(listStale ? '读取失败，保留旧快照：' : '读取失败：') + listError"
      type="warning"
      :closable="false"
    />
    <el-alert
      v-if="operations.error"
      :title="operations.error.message"
      type="error"
      :closable="false"
    />
    <div v-if="operations.submission && !creating" class="raw-submission">
      本会话提交：{{ operations.submission.applied }} 已确认 /
      {{ operations.submission.total }}；{{
        operations.submission.error ||
        (operations.submission.complete ? "等待审核" : "未完成")
      }}
      <el-button
        v-if="operations.submission.taskId"
        link
        @click="inspectSubmission(operations.submission.taskId)"
        >查看已知任务</el-button
      >
      <el-button link @click="openCreate">返回提交与核对</el-button>
    </div>
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
        ><el-option v-for="g in groupsInRows" :key="g" :label="g" :value="g"
      /></el-select>
      <el-select
        v-model="filter.kind"
        placeholder="全部任务类型"
        clearable
        aria-label="任务类型"
      >
        <el-option label="有限任务" value="CLOSE_WHEN_IDLE" /><el-option
          label="托管任务"
          value="PARK_WHEN_IDLE"
        />
      </el-select>
      <el-input-number
        v-if="globalView"
        v-model="globalStore.taskSampleLimit"
        :min="1"
        :max="1000"
        aria-label="全局采样上限"
      />
    </div>
    <el-table
      ref="table"
      v-loading="listLoading"
      :data="filtered"
      row-key="taskId"
      height="560"
      class="raw-tasks__table"
      @scroll="scroll"
    >
      <el-table-column label="任务 / 编号" min-width="240"
        ><template #default="{ row }">
          <el-button v-if="row.task" link type="primary" @click="openTask(row)">{{
            row.task.name || row.taskId
          }}</el-button>
          <code v-else>{{ row.taskId }}</code
          ><small v-if="row.task?.name">{{ row.taskId }}</small>
          <small v-if="!row.task">Task 描述符缺失</small>
        </template></el-table-column
      >
      <el-table-column label="Project / Group" min-width="145"
        ><template #default="{ row }"
          >{{ row.task?.projectId ?? "未提供"
          }}<small>{{ row.task?.workerGroupId ?? "未提供" }}</small></template
        ></el-table-column
      >
      <el-table-column label="生命周期" min-width="165"
        ><template #default="{ row }">{{
          row.task?.idleDisposition ?? "未提供"
        }}</template></el-table-column
      >
      <el-table-column label="Score band" min-width="140"
        ><template #default="{ row }"
          ><el-tag effect="plain">{{ row.scoreBand ?? "状态不可用" }}</el-tag></template
        ></el-table-column
      >
      <el-table-column label="Pool 供给" min-width="130" show-overflow-tooltip
        ><template #default="{ row }">{{
          row.task
            ? row.task.refill
                .map(
                  (r: { poolName: string; count: number }) =>
                    r.poolName + " × " + r.count
                )
                .join("、") || "无供给"
            : "未提供"
        }}</template></el-table-column
      >
      <el-table-column label="创建时间" min-width="165"
        ><template #default="{ row }">{{
          time(row.createdAtMillis)
        }}</template></el-table-column
      >
      <el-table-column label="操作" fixed="right" width="80"
        ><template #default="{ row }"
          ><el-button link :disabled="!row.task" @click="openTask(row)"
            >查看</el-button
          ></template
        ></el-table-column
      >
      <template #empty>{{
        listError ? "读取失败，请刷新重试" : "当前载入集合没有匹配任务"
      }}</template>
    </el-table>

    <el-drawer
      :key="taskId"
      :model-value="!!taskId"
      size="760px"
      :title="detail?.task?.name || taskId"
      :before-close="closeDetailDrawer"
    >
      <el-alert
        v-if="detailState.error"
        :title="(detailState.stale ? '当前快照已过期：' : '') + detailState.error"
        type="error"
        :closable="false"
      />
      <el-button :loading="detailState.loading" :icon="Refresh" @click="refreshDetail"
        >刷新任务</el-button
      >
      <span class="raw-muted"> {{ time(detailState.readAt) }}</span>
      <template v-if="detail">
        <p>
          <code>{{ detail.taskId }}</code>
        </p>
        <p>
          {{ detail.task?.projectId }} / {{ detail.task?.workerGroupId }} ·
          {{ detail.scoreBand ?? "状态不可用" }}
        </p>
        <div class="raw-tasks__actions">
          <el-button
            v-if="manage && detail.scoreBand === 'pre_review'"
            :disabled="operations.busy"
            @click="showAppend = !showAppend"
            >追加 Items</el-button
          >
          <el-button
            v-if="manage && detail.scoreBand === 'pre_review'"
            :disabled="operations.busy"
            type="primary"
            @click="changeTask('approve')"
            >核对并批准</el-button
          >
          <el-button
            v-if="manage"
            :disabled="operations.busy"
            type="danger"
            plain
            @click="changeTask('close')"
            >关闭任务</el-button
          >
          <el-button
            v-if="
              detail.task?.idleDisposition === 'CLOSE_WHEN_IDLE' &&
              detail.scoreBand === 'terminal'
            "
            :disabled="!api || !freshDetail || operations.busy"
            @click="exportTask"
            >导出原始成功结果 JSONL</el-button
          >
        </div>
        <p v-if="detail.task?.projectId !== 'default'" class="raw-muted">
          业务任务管理请使用对应 Scenario 页面；这里展示平台原始证据。
        </p>
        <RawTaskInput
          v-if="showAppend"
          :groups="groupState?.value ?? []"
          :task="detail"
          :disabled="
            !manage ||
            detail.scoreBand !== 'pre_review' ||
            !!groupState?.stale ||
            !!groupState?.loading
          "
          @submitted="afterSubmit"
          @inspect="inspectSubmission"
        />
        <el-tabs v-model="detailTab">
          <el-tab-pane label="原始信息" name="overview"
            ><JsonBlock :value="detail" />
            <p class="raw-muted">
              未保存的输入文件和本地回执不会从 Runtime 状态重建。
            </p></el-tab-pane
          >
          <el-tab-pane
            label="Task Call Debug"
            name="debug"
            :disabled="
              !api ||
              !freshDetail ||
              !debugEntry ||
              !!groupState?.stale ||
              !!groupState?.loading
            "
          >
            <el-alert
              title="此诊断会提交真实 Item 执行请求。"
              type="warning"
              :closable="false"
            />
            <TaskCallDebug
              v-if="
                api &&
                freshDetail &&
                debugEntry &&
                !groupState?.stale &&
                !groupState?.loading
              "
              :key="debugEntry.taskId"
              :entry="debugEntry"
            />
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
    <el-drawer
      :model-value="creating"
      title="创建原始有限任务 · default"
      size="760px"
      :before-close="closeCreateDrawer"
    >
      <el-alert
        v-if="workspace.groups.default?.error"
        :title="workspace.groups.default.error"
        type="error"
        :closable="false"
        ><el-button @click="workspace.loadGroups('default')"
          >重读 Group 能力</el-button
        ></el-alert
      >
      <RawTaskInput
        :groups="defaultGroups"
        :disabled="
          !api ||
          !workspace.groups.default?.value ||
          workspace.groups.default.stale ||
          workspace.groups.default.loading
        "
        @submitted="afterSubmit"
        @inspect="inspectSubmission"
      />
    </el-drawer>
  </section>
</template>
<style scoped>
.raw-tasks {
  min-width: 0;
  padding: 24px;
}
.raw-tasks__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  gap: 16px;
}
.raw-tasks__heading h1 {
  margin: 0;
  font-size: 24px;
}
.raw-tasks__heading > div {
  display: flex;
  align-items: center;
  gap: 12px;
}
.raw-tasks__filters {
  display: flex;
  gap: 12px;
  margin: 16px 0;
}
.raw-tasks__filters .el-input {
  width: 300px;
}
.raw-tasks__filters .el-select {
  width: 200px;
}
.raw-tasks__table {
  width: 100%;
}
.raw-tasks small {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.raw-tasks__table :deep(.el-button) {
  max-width: 100%;
}
.raw-tasks__table :deep(.el-button span) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.raw-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.raw-submission {
  padding: 12px;
  background: var(--el-fill-color-light);
  margin: 12px 0;
}
.raw-tasks__actions {
  display: flex;
  gap: 8px;
  margin: 16px 0;
}
.raw-tasks__actions .el-button {
  margin-left: 0;
}
</style>
