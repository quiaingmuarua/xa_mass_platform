<script setup lang="ts">
import { computed, ref } from "vue";
import { CircleCheck, Download, Files, Plus, Tickets } from "@element-plus/icons-vue";

import MetricCard from "@/components/MetricCard.vue";
import { useRuntimeViewerStore, useTaskManagementStore } from "@/runtime-context";
import { stageLabel } from "@/task-management/model";
import type { FiniteTaskSession, FiniteTaskStage } from "@/task-management/types";

const runtimeStore = useRuntimeViewerStore();
const taskStore = useTaskManagementStore();
const drawerOpen = ref(false);
const drawerMode = ref<"draft" | "task">("draft");
const selectedTaskId = ref<string>();
const reviewConfirmed = ref(false);
const inputFile = ref<File>();
const draft = ref({
  workerGroupId: "",
  eventCode: "",
  payloadKey: "value",
  priority: 50,
  maximumCandidateWorkers: 10,
  maxRetryTimes: 3
});

const availableGroups = computed(() =>
  runtimeStore.entries.flatMap((entry) =>
    entry.workerGroup === null ? [] : [entry.workerGroup]
  )
);
const draftGroup = computed(() =>
  availableGroups.value.find(
    (group) => group.workerGroupId === draft.value.workerGroupId
  )
);
const selectedTask = computed(() =>
  taskStore.tasks.find((task) => task.taskId === selectedTaskId.value)
);

function openCreate(): void {
  taskStore.clearMessages();
  const group = availableGroups.value[0];
  draft.value = {
    workerGroupId: group?.workerGroupId ?? "",
    eventCode: group?.eventCodes[0] ?? "",
    payloadKey: "value",
    priority: 50,
    maximumCandidateWorkers: 10,
    maxRetryTimes: 3
  };
  inputFile.value = undefined;
  selectedTaskId.value = undefined;
  drawerMode.value = "draft";
  drawerOpen.value = true;
}

function changeGroup(): void {
  draft.value.eventCode = draftGroup.value?.eventCodes[0] ?? "";
}

function chooseFile(event: Event): void {
  inputFile.value = (event.target as HTMLInputElement).files?.[0];
  taskStore.clearMessages();
}

async function createAndAppend(): Promise<void> {
  if (inputFile.value === undefined) return;
  const task = await taskStore.createAndAppend({
    workerGroupId: draft.value.workerGroupId,
    eventCode: draft.value.eventCode,
    payloadKey: draft.value.payloadKey,
    file: inputFile.value,
    config: {
      priority: Number(draft.value.priority),
      maximumCandidateWorkers: Number(draft.value.maximumCandidateWorkers),
      maxRetryTimes: Number(draft.value.maxRetryTimes)
    }
  });
  if (task !== undefined) {
    selectedTaskId.value = task.taskId;
    reviewConfirmed.value = false;
    drawerMode.value = "task";
  }
}

function openTask(task: FiniteTaskSession): void {
  taskStore.clearMessages();
  selectedTaskId.value = task.taskId;
  reviewConfirmed.value = false;
  drawerMode.value = "task";
  drawerOpen.value = true;
}

async function approveSelected(): Promise<void> {
  const task = selectedTask.value;
  if (task === undefined || !reviewConfirmed.value) return;
  await taskStore.approveTask(task.taskId);
}

async function exportTask(task: FiniteTaskSession): Promise<void> {
  const download = await taskStore.exportTask(task.taskId);
  if (download === undefined) return;
  const url = URL.createObjectURL(download.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = download.fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}

function tagType(stage: FiniteTaskStage): "info" | "primary" | "success" {
  if (stage === "EXPORT_READY") return "success";
  if (stage === "APPROVED") return "primary";
  return "info";
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString();
}

function formatBytes(value: number): string {
  return value < 1024 ? `${value} B` : `${(value / 1024).toFixed(1)} KiB`;
}
</script>

<template>
  <div class="finite-task-workbench" data-testid="finite-task-workbench">
    <div class="task-metric-grid task-metric-grid--finite" aria-label="有限 Task 指标">
      <MetricCard
        label="Created"
        :value="taskStore.tasks.length"
        hint="browser session"
        :icon="Tickets"
        tone="primary"
      />
      <MetricCard
        label="Items Appended"
        :value="taskStore.appendedCount"
        hint="confirmed API stage"
        :icon="Files"
        tone="info"
      />
      <MetricCard
        label="Approved"
        :value="taskStore.approvedCount"
        hint="explicit approval"
        :icon="CircleCheck"
        tone="warning"
      />
      <MetricCard
        label="Export Ready"
        :value="taskStore.exportReadyCount"
        hint="download observed"
        :icon="Download"
        tone="success"
      />
    </div>

    <el-alert class="runtime-semantics" type="info" :closable="false" show-icon>
      <template #title>
        每个 TXT 文件创建一个真实有限 Task，并按 100 条分块 Append。页面只记录已确认的
        Created、Items Appended、Approved 与 Export Ready，不推断运行或终止状态。
      </template>
    </el-alert>

    <el-alert
      v-if="!taskStore.available"
      class="runtime-semantics"
      type="warning"
      :closable="false"
      show-icon
    >
      <template #title>
        Mock 数据模式只展示 Runtime 示例；有限 Task
        操作已禁用，且不会自动回退到模拟结果。
      </template>
    </el-alert>

    <el-alert
      v-if="taskStore.error"
      class="finite-task-error-alert"
      type="error"
      :closable="false"
      show-icon
    >
      <template #title>{{ taskStore.error.title }}</template>
      <p>{{ taskStore.error.message }}</p>
      <small v-if="taskStore.error.requestId">
        Request ID: {{ taskStore.error.requestId }}
      </small>
    </el-alert>
    <el-alert
      v-if="taskStore.notice"
      class="finite-task-error-alert"
      type="warning"
      :closable="false"
      show-icon
      :title="taskStore.notice"
    />

    <section class="worker-panel finite-task-panel" aria-label="有限 Task">
      <div class="finite-task-toolbar">
        <div>
          <strong>Finite Task files</strong>
          <span>普通 Task 无列表接口；刷新页面后无法重新发现本会话创建的 Task。</span>
        </div>
        <el-button
          type="primary"
          :icon="Plus"
          :disabled="
            !taskStore.available || availableGroups.length === 0 || taskStore.isBusy
          "
          @click="openCreate"
        >
          新建文件 Task
        </el-button>
      </div>

      <div v-if="runtimeStore.resourceLoadStatus === 'loading'" class="panel-state">
        正在读取 WorkerGroup 与 Event 目录…
      </div>
      <div v-else-if="availableGroups.length === 0" class="panel-state">
        当前没有可用于有限 Task 的 WorkerGroup 描述符。
      </div>
      <div
        v-else-if="taskStore.tasks.length === 0"
        class="panel-state finite-task-empty"
      >
        <el-icon><Tickets /></el-icon>
        <div>
          <strong>当前浏览器会话还没有有限 Task</strong>
          <p>选择 TXT、WorkerGroup、EventCode 和 Payload Key 后创建并 Append。</p>
        </div>
      </div>
      <div v-else class="task-table-wrap">
        <el-table :data="taskStore.tasks" row-key="taskId" class="task-table">
          <el-table-column label="TASK ID" min-width="280">
            <template #default="{ row }"
              ><code>{{ row.taskId }}</code></template
            >
          </el-table-column>
          <el-table-column label="WORKER GROUP" min-width="230">
            <template #default="{ row }">{{ row.workerGroupId }}</template>
          </el-table-column>
          <el-table-column label="FILE" min-width="170">
            <template #default="{ row }">{{ row.originalFileName }}</template>
          </el-table-column>
          <el-table-column label="ITEMS" width="95" align="center">
            <template #default="{ row }"
              >{{ row.appendedCount }}/{{ row.lineCount }}</template
            >
          </el-table-column>
          <el-table-column label="STAGE" width="155">
            <template #default="{ row }">
              <el-tag :type="tagType(row.stage)" effect="plain" size="small">
                {{ stageLabel(row.stage) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="UPDATED" min-width="180">
            <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="ACTIONS" width="180" fixed="right" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openTask(row)">查看</el-button>
              <el-button
                v-if="row.stage === 'APPROVED' || row.stage === 'EXPORT_READY'"
                link
                :icon="Download"
                :loading="taskStore.activeTaskId === row.taskId"
                @click="exportTask(row)"
              >
                导出
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <el-drawer
      v-model="drawerOpen"
      class="worker-detail-drawer finite-task-drawer"
      size="min(560px, 100%)"
      destroy-on-close
    >
      <template #header>
        <div class="worker-drawer-heading">
          <span>{{ drawerMode === "draft" ? "DRAFT" : "FINITE TASK" }}</span>
          <strong>{{ selectedTask?.taskId ?? "Create from TXT" }}</strong>
        </div>
      </template>

      <form
        v-if="drawerMode === 'draft'"
        class="finite-task-form"
        @submit.prevent="createAndAppend"
      >
        <label>
          <span>WorkerGroup</span>
          <select
            v-model="draft.workerGroupId"
            :disabled="taskStore.isBusy"
            @change="changeGroup"
          >
            <option
              v-for="group in availableGroups"
              :key="group.workerGroupId"
              :value="group.workerGroupId"
            >
              {{ group.workerGroupId }}
            </option>
          </select>
        </label>
        <label>
          <span>EventCode</span>
          <select v-model="draft.eventCode" :disabled="taskStore.isBusy">
            <option
              v-for="eventCode in draftGroup?.eventCodes ?? []"
              :key="eventCode"
              :value="eventCode"
            >
              {{ eventCode }}
            </option>
          </select>
        </label>
        <label>
          <span>Payload Key</span>
          <input v-model="draft.payloadKey" type="text" :disabled="taskStore.isBusy" />
        </label>
        <label>
          <span>UTF-8 TXT</span>
          <input
            type="file"
            accept=".txt,text/plain"
            :disabled="taskStore.isBusy"
            @change="chooseFile"
          />
          <small>最多 1 MiB / 10,000 行；每行成为一个普通 TaskItem。</small>
        </label>
        <div class="finite-task-config-grid">
          <label
            ><span>Priority</span
            ><input v-model.number="draft.priority" type="number" min="0" max="99"
          /></label>
          <label
            ><span>Candidates</span
            ><input
              v-model.number="draft.maximumCandidateWorkers"
              type="number"
              min="1"
          /></label>
          <label
            ><span>Retries</span
            ><input v-model.number="draft.maxRetryTimes" type="number" min="0" max="98"
          /></label>
        </div>
        <el-button
          type="primary"
          native-type="submit"
          :loading="taskStore.isBusy"
          :disabled="inputFile === undefined"
        >
          Create + Append
        </el-button>
      </form>

      <div v-else-if="selectedTask" class="worker-detail">
        <section>
          <h2>Confirmed stage</h2>
          <el-tag :type="tagType(selectedTask.stage)" effect="plain">
            {{ stageLabel(selectedTask.stage) }}
          </el-tag>
        </section>
        <section>
          <h2>Input</h2>
          <dl class="worker-detail__identity">
            <div>
              <dt>WorkerGroup</dt>
              <dd>{{ selectedTask.workerGroupId }}</dd>
            </div>
            <div>
              <dt>EventCode</dt>
              <dd>{{ selectedTask.eventCode }}</dd>
            </div>
            <div>
              <dt>Payload Key</dt>
              <dd>{{ selectedTask.payloadKey }}</dd>
            </div>
            <div>
              <dt>File</dt>
              <dd>{{ selectedTask.originalFileName }}</dd>
            </div>
            <div>
              <dt>Size</dt>
              <dd>{{ formatBytes(selectedTask.byteCount) }}</dd>
            </div>
            <div>
              <dt>Items</dt>
              <dd>{{ selectedTask.appendedCount }}/{{ selectedTask.lineCount }}</dd>
            </div>
          </dl>
        </section>

        <template v-if="selectedTask.stage === 'ITEMS_APPENDED'">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="Approve 会让 Kernel 开始处理已 Append 的 Items。"
          />
          <label class="finite-task-review-confirmation">
            <input v-model="reviewConfirmed" type="checkbox" />
            我已确认 WorkerGroup、EventCode、文件行数与调度参数。
          </label>
          <el-button
            type="primary"
            :disabled="!reviewConfirmed"
            :loading="taskStore.isBusy"
            @click="approveSelected"
          >
            Approve Task
          </el-button>
        </template>
        <el-alert
          v-else-if="selectedTask.stage === 'CREATED'"
          type="error"
          :closable="false"
          show-icon
          title="Append 未完整成功；该 Task 不会被 Approve。请修正问题后新建一次文件 Task。"
        />
        <el-button
          v-else
          type="primary"
          :icon="Download"
          :loading="taskStore.isBusy"
          @click="exportTask(selectedTask)"
        >
          {{ selectedTask.stage === "EXPORT_READY" ? "再次导出 JSONL" : "导出 JSONL" }}
        </el-button>
      </div>
    </el-drawer>
  </div>
</template>
