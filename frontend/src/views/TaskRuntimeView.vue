<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import {
  InfoFilled,
  Refresh,
  Search,
  Tickets,
  Tools,
  Warning
} from "@element-plus/icons-vue";

import FiniteTaskManagement from "@/components/FiniteTaskManagement.vue";
import JsonBlock from "@/components/JsonBlock.vue";
import TaskCallDebug from "@/components/TaskCallDebug.vue";
import {
  useRuntimeViewerConfig,
  useRuntimeViewerStore,
  useTaskManagementStore
} from "@/runtime-context";
import type { TaskRuntimePreviewEntry, TaskScoreBand } from "@/runtime-viewer/types";
import {
  taskCallDebugAvailability,
  type TaskCallDebugAvailability
} from "@/task-call-debug/model";

type TaskDetailTab = "overview" | "debug";
type FiniteTaskWorkbench = { openTaskById(taskId: string): void };

const store = useRuntimeViewerStore();
const taskManagement = useTaskManagementStore();
const config = useRuntimeViewerConfig();
const searchText = ref("");
const selectedEntry = ref<TaskRuntimePreviewEntry>();
const detailsOpen = ref(false);
const detailTab = ref<TaskDetailTab>("overview");
const workbenchOpen = ref(false);
const finiteWorkbench = ref<FiniteTaskWorkbench>();

const filteredEntries = computed(() => {
  const query = searchText.value.trim().toLocaleLowerCase();
  if (query.length === 0) return store.entries;
  return store.entries.filter((entry) =>
    [
      entry.taskId,
      entry.scoreBand,
      entry.task?.workerGroupId,
      entry.task?.workerAllocationMechanism,
      entry.task?.idleDisposition
    ]
      .filter((value): value is string => value !== undefined)
      .some((value) => value.toLocaleLowerCase().includes(query))
  );
});

onMounted(() => void store.initializeTaskView());

function openDetails(
  entry: TaskRuntimePreviewEntry,
  tab: TaskDetailTab = "overview"
): void {
  selectedEntry.value = entry;
  detailTab.value = tab;
  detailsOpen.value = true;
}

function closeDetails(): void {
  selectedEntry.value = undefined;
  detailTab.value = "overview";
}

async function openWorkbench(taskId?: string): Promise<void> {
  workbenchOpen.value = true;
  await store.initializeWorkerGroups();
  if (taskId !== undefined) {
    await nextTick();
    finiteWorkbench.value?.openTaskById(taskId);
  }
}

function refreshAfterFiniteTaskChange(): void {
  void store.refreshTasks();
}

function debugAvailability(entry: TaskRuntimePreviewEntry): TaskCallDebugAvailability {
  return taskCallDebugAvailability(config.mode, entry);
}

function finiteTaskKnown(taskId: string): boolean {
  return taskManagement.tasks.some((task) => task.taskId === taskId);
}

function workerGroupId(entry: TaskRuntimePreviewEntry): string {
  return entry.task?.workerGroupId ?? "—";
}

function scoreBandLabel(band: TaskScoreBand): string {
  return {
    pre_review: "Awaiting Review",
    admission_visible: "Admission Visible",
    running_visible: "Running Visible",
    terminal: "Closed"
  }[band];
}

function scoreBandType(
  band: TaskScoreBand
): "warning" | "primary" | "success" | "info" {
  return {
    pre_review: "warning",
    admission_visible: "primary",
    running_visible: "success",
    terminal: "info"
  }[band] as "warning" | "primary" | "success" | "info";
}

function descriptorLabel(entry: TaskRuntimePreviewEntry): string {
  if (entry.task === null) return "Task Missing";
  if (entry.workerGroup === null) return "Group Missing";
  return "Available";
}

function descriptorType(entry: TaskRuntimePreviewEntry): "success" | "warning" {
  return entry.task !== null && entry.workerGroup !== null ? "success" : "warning";
}
</script>

<template>
  <section class="task-page" data-testid="task-runtime-page">
    <header class="worker-page__heading task-preview-heading">
      <div>
        <p class="worker-page__eyebrow">RUNTIME / TASKS</p>
        <h1>Task Runtime Preview</h1>
        <p>Task Score 高位窗口的只读投影，以及通过 Kernel Scheduling 的 Task 调试</p>
      </div>
      <div class="task-preview-heading__actions">
        <el-button :icon="Tools" @click="openWorkbench()">
          Finite Task Workbench
        </el-button>
        <el-button
          type="primary"
          :icon="Refresh"
          :loading="store.taskPreviewState.status === 'refreshing'"
          @click="store.refreshTasks"
        >
          刷新
        </el-button>
      </div>
    </header>

    <el-alert class="runtime-semantics" type="info" :closable="false" show-icon>
      <template #title>
        当前窗口由 Task Score 从高到低读取，最多 100
        条；它不代表业务优先级，也不承诺全量、分页或稳定成员。 Running Visible
        只表示进入调度可见 Band，不证明正在执行。
      </template>
    </el-alert>

    <el-alert
      v-if="store.taskPreviewState.stale"
      class="runtime-semantics"
      type="warning"
      :closable="false"
      show-icon
    >
      <template #title>
        当前仍显示上一次成功读取的 Task 窗口；最近一次刷新失败。
        <template v-if="store.taskPreviewState.error">
          {{ store.taskPreviewState.error.message }}
          <span v-if="store.taskPreviewState.error.requestId">
            · Request ID: {{ store.taskPreviewState.error.requestId }}
          </span>
        </template>
      </template>
    </el-alert>

    <section class="worker-panel" aria-label="Task Runtime Preview">
      <div class="finite-task-toolbar task-preview-toolbar">
        <div>
          <strong>Task Score Window</strong>
          <span>
            {{ store.entries.length }} /
            {{ store.taskPreviewState.preview?.sampleLimit ?? 100 }}
            observed
            <template v-if="store.taskPreviewState.preview">
              ·
              {{
                new Date(store.taskPreviewState.preview.generatedAt).toLocaleString()
              }}
            </template>
          </span>
        </div>
        <el-input
          v-model="searchText"
          class="task-preview-search"
          :prefix-icon="Search"
          clearable
          placeholder="筛选当前 Task 窗口"
          aria-label="筛选当前 Task 窗口"
        />
      </div>

      <div
        v-if="
          (store.taskPreviewState.status === 'loading' ||
            store.taskPreviewState.status === 'refreshing') &&
          store.entries.length === 0
        "
        class="panel-state"
        data-testid="task-loading"
      >
        <el-icon class="is-loading"><Tickets /></el-icon>
        正在读取 Task Score 窗口…
      </div>

      <div
        v-else-if="
          store.taskPreviewState.status === 'error' && store.entries.length === 0
        "
        class="panel-state panel-state--error"
        data-testid="task-error"
      >
        <el-icon><Warning /></el-icon>
        <div>
          <strong>{{ store.taskPreviewState.error?.title }}</strong>
          <p>{{ store.taskPreviewState.error?.message }}</p>
          <small v-if="store.taskPreviewState.error?.requestId">
            Request ID: {{ store.taskPreviewState.error.requestId }}
          </small>
        </div>
        <el-button @click="store.refreshTasks">重新读取</el-button>
      </div>

      <div
        v-else-if="store.entries.length === 0"
        class="panel-state"
        data-testid="empty-tasks"
      >
        <el-icon><InfoFilled /></el-icon>
        <div>
          <strong>当前 Task Score 窗口为空</strong>
          <p>Owner 当前没有返回可展示的 Task Score 成员。</p>
        </div>
      </div>

      <div v-else class="task-table-wrap">
        <el-table :data="filteredEntries" row-key="taskId" class="task-table">
          <el-table-column label="TASK ID" min-width="280">
            <template #default="{ row }"
              ><code>{{ row.taskId }}</code></template
            >
          </el-table-column>
          <el-table-column label="SCORE BAND" min-width="155">
            <template #default="{ row }">
              <el-tag :type="scoreBandType(row.scoreBand)" effect="plain" size="small">
                {{ scoreBandLabel(row.scoreBand) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="WORKER GROUP" min-width="230">
            <template #default="{ row }">
              <span class="task-group-id">{{ workerGroupId(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="ALLOCATION" min-width="190">
            <template #default="{ row }">
              {{ row.task?.workerAllocationMechanism ?? "—" }}
            </template>
          </el-table-column>
          <el-table-column label="IDLE DISPOSITION" min-width="170">
            <template #default="{ row }">
              <code>{{ row.task?.idleDisposition ?? "—" }}</code>
            </template>
          </el-table-column>
          <el-table-column label="DESCRIPTOR" width="145">
            <template #default="{ row }">
              <el-tag :type="descriptorType(row)" effect="plain" size="small">
                {{ descriptorLabel(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="ACTIONS" width="190" fixed="right" align="right">
            <template #default="{ row }">
              <div class="task-row-actions">
                <el-button link type="primary" @click="openDetails(row)"
                  >详情</el-button
                >
                <span :title="debugAvailability(row).reason">
                  <el-button
                    link
                    type="warning"
                    :disabled="!debugAvailability(row).enabled"
                    @click="openDetails(row, 'debug')"
                  >
                    调试
                  </el-button>
                </span>
                <el-button
                  v-if="finiteTaskKnown(row.taskId)"
                  link
                  type="success"
                  @click="openWorkbench(row.taskId)"
                >
                  继续
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="filteredEntries.length === 0" class="panel-state task-filter-empty">
          当前窗口没有匹配的 Task。
        </div>
      </div>
    </section>

    <el-drawer
      v-model="detailsOpen"
      class="worker-detail-drawer"
      size="min(680px, 100%)"
      destroy-on-close
      @closed="closeDetails"
    >
      <template #header>
        <div v-if="selectedEntry" class="worker-drawer-heading">
          <span>TASK RUNTIME PREVIEW</span>
          <strong>{{ selectedEntry.taskId }}</strong>
        </div>
      </template>

      <el-tabs
        v-if="selectedEntry"
        v-model="detailTab"
        class="worker-detail-tabs"
        data-testid="task-detail"
      >
        <el-tab-pane label="Overview" name="overview">
          <div class="worker-detail">
            <section class="task-descriptor-heading">
              <h2>Runtime projection</h2>
              <el-tag effect="plain" type="info" size="small">READ-ONLY</el-tag>
            </section>
            <section>
              <dl class="worker-detail__identity">
                <div>
                  <dt>Task ID</dt>
                  <dd>{{ selectedEntry.taskId }}</dd>
                </div>
                <div>
                  <dt>Score Band</dt>
                  <dd>{{ scoreBandLabel(selectedEntry.scoreBand) }}</dd>
                </div>
                <div>
                  <dt>WorkerGroup</dt>
                  <dd>{{ workerGroupId(selectedEntry) }}</dd>
                </div>
                <div>
                  <dt>Worker allocation</dt>
                  <dd>
                    {{ selectedEntry.task?.workerAllocationMechanism ?? "描述符缺失" }}
                  </dd>
                </div>
                <div>
                  <dt>Idle disposition</dt>
                  <dd>{{ selectedEntry.task?.idleDisposition ?? "描述符缺失" }}</dd>
                </div>
              </dl>
            </section>

            <template v-if="selectedEntry.task">
              <section>
                <h2>Task-level allocation rule</h2>
                <JsonBlock :value="selectedEntry.task.allocationRule" />
              </section>
              <section>
                <h2>Config</h2>
                <JsonBlock :value="selectedEntry.task.config" />
              </section>
            </template>

            <el-alert
              v-if="selectedEntry.task === null"
              type="warning"
              :closable="false"
              show-icon
            >
              <template #title>
                Score 成员存在，但 Task Descriptor 缺失。Runtime Preview
                只暴露漂移，不会推导或修复资源。
              </template>
            </el-alert>
            <el-alert
              v-else-if="selectedEntry.workerGroup === null"
              type="warning"
              :closable="false"
              show-icon
            >
              <template #title>
                Task Descriptor 存在，但 WorkerGroup Descriptor
                缺失。查询不会自动修复资源。
              </template>
            </el-alert>
          </div>
        </el-tab-pane>
        <el-tab-pane
          label="Task Call Debug"
          name="debug"
          :disabled="!debugAvailability(selectedEntry).enabled"
        >
          <TaskCallDebug
            v-if="debugAvailability(selectedEntry).enabled"
            :entry="selectedEntry"
          />
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <el-drawer
      v-model="workbenchOpen"
      class="worker-detail-drawer finite-task-workbench-drawer"
      size="min(1180px, 100%)"
      title="Finite Task Workbench"
    >
      <FiniteTaskManagement
        ref="finiteWorkbench"
        @task-changed="refreshAfterFiniteTaskChange"
      />
    </el-drawer>
  </section>
</template>
