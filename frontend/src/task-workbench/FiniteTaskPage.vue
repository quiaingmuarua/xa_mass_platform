<script setup lang="ts">
import TaskRetentionNotice from "@/components/TaskRetentionNotice.vue";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus, Refresh } from "@element-plus/icons-vue";
import "element-plus/es/components/message-box/style/css";
import JsonBlock from "@/components/JsonBlock.vue";
import RawTaskInput from "@/components/RawTaskInput.vue";
import RawTaskResults from "@/components/RawTaskResults.vue";
import RawTaskTable, { type TaskRow } from "@/components/RawTaskTable.vue";
import TaskResultExport from "@/components/TaskResultExport.vue";
import FiniteTaskActions from "./FiniteTaskActions.vue";
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
watch(
  () => [taskId.value, fresh.value],
  () => {
    if (fresh.value && workspace.resultsKey.value !== projectId + "/" + taskId.value)
      void workspace.loadResults(projectId, taskId.value);
  }
);
function refreshResults() {
  if (
    detail.value?.task?.projectId === projectId &&
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
const filtered = computed(() =>
  rows.value.filter((r) => {
    const search = filter.search.trim().toLowerCase();
    return (
      (!search ||
        [r.taskId, r.task?.name ?? ""].some((v) => v.toLowerCase().includes(search))) &&
      (!filter.group || r.task?.workerGroupId === filter.group) &&
      (!filter.status || r.scoreBand === filter.status)
    );
  })
);
const table = ref<InstanceType<typeof RawTaskTable>>();
const createHeading = ref<HTMLElement>();
let creatingPreviously = false;
let disposed = false;
let navigation = 0;
let restoringScroll = true;
let focusReturn: HTMLElement | undefined;
function time(value?: string | number | null) {
  return value == null ? "未提供" : new Date(value).toLocaleString();
}
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
      route.path === "/task-workbench" &&
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
    route.path !== "/task-workbench" ||
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
  <section class="raw-tasks" data-testid="finite-task-page">
    <section v-show="creating" aria-label="创建有限任务">
      <header class="raw-tasks__heading">
        <h2 ref="createHeading" tabindex="-1">创建有限任务</h2>
        <div>
          <el-button :loading="groups?.loading" @click="workspace.loadGroups(projectId)"
            >刷新 Group 能力</el-button
          >
          <el-button @click="close(() => {})">返回列表</el-button>
        </div>
      </header>
      <p v-if="project.workerGroupSource === 'registered'" class="raw-muted">
        default 可使用当前环境全部已注册 Group；新注册后手动刷新。每个任务绑定一个
        Group。
      </p>
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
    <div v-show="!creating">
      <header class="raw-tasks__heading">
        <h2>有限任务</h2>
        <div>
          <span class="raw-muted">最近读取：{{ time(list?.readAt) }}</span
          ><el-button :icon="Plus" :disabled="!canWrite" @click="openCreate"
            >创建任务</el-button
          ><el-button :icon="Refresh" :loading="list?.loading" @click="refresh"
            >刷新</el-button
          >
        </div>
      </header>
      <p class="raw-muted">
        当前载入 {{ list?.value?.tasks.length ?? 0 }} 条（最多 100
        条），其中有限或类型未知任务 {{ rows.length }} 条。筛选仅作用于当前集合。{{
          list?.value?.truncated ? "列表已截断。" : ""
        }}
      </p>
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
      <div v-if="operationState.submission" class="raw-submission">
        本会话提交：{{ operationState.submission.applied }} 已确认 /
        {{ operationState.submission.total }}；{{
          operationState.submission.error ||
          (operationState.submission.complete ? "本次导入完成" : "未完成")
        }}
        <el-button link @click="returnSubmission">返回提交与核对</el-button
        ><el-button
          v-if="operationState.submission.taskId"
          link
          @click="inspect(operationState.submission.taskId)"
          >查看已知任务</el-button
        >
        <el-button
          v-if="operationState.submission.taskId && !operationState.submission.complete"
          link
          :disabled="operations.busy || !canWrite"
          @click="ensureKnownDirectory"
          >核对／补齐任务目录</el-button
        >
      </div>
      <div class="raw-tasks__filters">
        <el-input
          v-model="filter.search"
          placeholder="名称 / 编号（当前集合）"
          clearable
          aria-label="有限任务搜索"
        /><el-select
          v-model="filter.group"
          placeholder="所有 Group"
          clearable
          aria-label="有限任务 Group"
          ><el-option
            v-for="g in groups?.value ?? []"
            :key="g.workerGroupId"
            :label="g.workerGroupId"
            :value="g.workerGroupId" /></el-select
        ><el-select
          v-model="filter.status"
          placeholder="全部状态"
          clearable
          aria-label="有限任务状态"
          ><el-option
            v-for="status in [
              'pre_review',
              'running-initial',
              'running_visible',
              'terminal'
            ]"
            :key="status"
            :label="status"
            :value="status"
        /></el-select>
      </div>
      <RawTaskTable
        ref="table"
        :rows="filtered"
        :loading="list?.loading"
        :error="list?.error"
        @open="open"
        @scroll="rememberScroll"
        ><template #actions="{ row }"
          ><FiniteTaskActions
            :row="row"
            :writable="
              canWrite &&
              !list?.loading &&
              !list?.stale &&
              row.task?.projectId === projectId &&
              row.task.idleDisposition === 'CLOSE_WHEN_IDLE'
            "
            :busy="operations.busy"
            @action="action(row, $event)" /></template
      ></RawTaskTable>
    </div>
    <el-drawer
      :key="taskId"
      :model-value="!!taskId"
      size="760px"
      :title="detail?.task?.name || taskId"
      :before-close="close"
    >
      <el-alert
        v-if="state.error"
        :title="(state.stale ? '当前快照已过期：' : '') + state.error"
        type="error"
        :closable="false"
      />
      <el-button
        :loading="state.loading"
        :icon="Refresh"
        @click="workspace.loadDetail(projectId, taskId)"
        >刷新任务</el-button
      ><span class="raw-muted"> {{ time(state.readAt) }}</span>
      <template v-if="detail">
        <p>
          <code>{{ detail.taskId }}</code>
        </p>
        <p>{{ detail.task?.workerGroupId }} · {{ detail.scoreBand ?? "状态不可用" }}</p>
        <p>实际 Item 数量：{{ detail.stats?.total ?? "不可用" }}</p>
        <TaskRetentionNotice :retention="detail.retention" />
        <FiniteTaskActions
          v-if="detail.task?.idleDisposition === 'CLOSE_WHEN_IDLE'"
          :row="detail"
          :writable="manage"
          :busy="operations.busy"
          @action="action(detail, $event)"
        />
        <p v-else class="raw-muted">该记录不是可管理的有限任务，或描述符不可用。</p>
        <router-link
          :to="{
            path: '/runtime/tasks',
            query: { project: projectId, taskId: detail.taskId }
          }"
          >查看 Runtime 原始观察</router-link
        >
        <TaskResultExport :key="detail.taskId" :task="detail" :fresh="fresh" />
        <RawTaskResults
          v-if="workspace.resultsKey.value === projectId + '/' + taskId"
          :key="detail.taskId"
          :state="workspace.results.value"
          @refresh="refreshResults"
        />
        <el-alert
          v-if="groups?.error"
          :title="groups.error"
          type="error"
          :closable="false"
          ><el-button @click="workspace.loadGroups(projectId)"
            >重读 Group 能力</el-button
          ></el-alert
        >
        <RawTaskInput
          v-if="appending && detail.task?.idleDisposition === 'CLOSE_WHEN_IDLE'"
          :key="detail.taskId"
          :project-id="projectId"
          :groups="groups?.value ?? []"
          :task="detail"
          :disabled="!manage || detail.scoreBand !== 'pre_review' || !groupFresh"
          @submitted="afterSubmit"
          @inspect="inspect"
        />
        <details>
          <summary>原始任务信息</summary>
          <JsonBlock :value="detail" />
        </details>
      </template>
    </el-drawer>
  </section>
</template>
