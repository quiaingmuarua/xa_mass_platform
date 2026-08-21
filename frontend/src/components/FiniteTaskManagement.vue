<script setup lang="ts">
import { computed, ref } from "vue";
import {
  CircleCheck,
  Download,
  Files,
  Plus,
  Tickets,
  Timer,
  Upload
} from "@element-plus/icons-vue";

import MetricCard from "@/components/MetricCard.vue";
import { useRuntimeViewerStore, useTaskManagementStore } from "@/runtime-context";
import { presentationLabel, presentationStatus } from "@/task-management/model";
import type {
  MockFiniteTask,
  MockFiniteTaskPresentationStatus
} from "@/task-management/types";

type DrawerStage = "create" | "seed" | "review" | "detail";

const runtimeStore = useRuntimeViewerStore();
const taskStore = useTaskManagementStore();

const drawerOpen = ref(false);
const drawerStage = ref<DrawerStage>("create");
const selectedTaskId = ref<string>();
const reviewConfirmed = ref(false);
const seedFile = ref<File>();

const createDraft = ref({
  taskId: "",
  workerGroupId: "",
  priority: 50,
  maximumCandidateWorkers: 10,
  maxRetryTimes: 3
});
const seedDraft = ref({ eventCode: "", payloadKey: "value" });

const availableGroups = computed(() =>
  runtimeStore.entries.flatMap((entry) =>
    entry.workerGroup === null ? [] : [entry.workerGroup]
  )
);
const selectedTask = computed(() =>
  taskStore.tasks.find((task) => task.taskId === selectedTaskId.value)
);
const selectedGroup = computed(() =>
  availableGroups.value.find(
    (group) => group.workerGroupId === selectedTask.value?.workerGroupId
  )
);
const drawerTitle = computed(() => {
  if (drawerStage.value === "create") return "Create finite Task";
  if (drawerStage.value === "seed") return "Upload TXT Seeds";
  if (drawerStage.value === "review") return "Review and Approve";
  return "Finite Task details";
});

function openCreate(): void {
  taskStore.clearError();
  const nextNumber = String(taskStore.tasks.length + 1).padStart(3, "0");
  createDraft.value = {
    taskId: `finite-task-${nextNumber}`,
    workerGroupId: availableGroups.value[0]?.workerGroupId ?? "",
    priority: 50,
    maximumCandidateWorkers: 10,
    maxRetryTimes: 3
  };
  selectedTaskId.value = undefined;
  drawerStage.value = "create";
  drawerOpen.value = true;
}

function createTask(): void {
  const task = taskStore.createTask({
    taskId: createDraft.value.taskId,
    workerGroupId: createDraft.value.workerGroupId,
    config: {
      priority: Number(createDraft.value.priority),
      maximumCandidateWorkers: Number(createDraft.value.maximumCandidateWorkers),
      maxRetryTimes: Number(createDraft.value.maxRetryTimes)
    }
  });
  if (task === undefined) return;
  selectedTaskId.value = task.taskId;
  resetSeedDraft(task);
  drawerStage.value = "seed";
}

function continueTask(task: MockFiniteTask): void {
  taskStore.clearError();
  selectedTaskId.value = task.taskId;
  reviewConfirmed.value = false;
  seedFile.value = undefined;
  if (task.lifecycleState !== "PRE_REVIEW") {
    drawerStage.value = "detail";
  } else if (task.seedState === "READY") {
    drawerStage.value = "review";
  } else {
    resetSeedDraft(task);
    drawerStage.value = "seed";
  }
  drawerOpen.value = true;
}

function resetSeedDraft(task: MockFiniteTask): void {
  const group = availableGroups.value.find(
    (candidate) => candidate.workerGroupId === task.workerGroupId
  );
  seedDraft.value = {
    eventCode: task.seed?.eventCode ?? group?.eventCodes[0] ?? "",
    payloadKey: task.seed?.payloadKey ?? "value"
  };
  seedFile.value = undefined;
}

function chooseSeedFile(event: Event): void {
  seedFile.value = (event.target as HTMLInputElement).files?.[0];
  taskStore.clearError();
}

async function attachSeed(): Promise<void> {
  const task = selectedTask.value;
  if (task === undefined || seedFile.value === undefined) return;
  const updated = await taskStore.attachSeed({
    taskId: task.taskId,
    eventCode: seedDraft.value.eventCode,
    payloadKey: seedDraft.value.payloadKey,
    file: seedFile.value
  });
  if (updated !== undefined) {
    reviewConfirmed.value = false;
    drawerStage.value = "review";
  }
}

function backToSeeds(): void {
  const task = selectedTask.value;
  if (task === undefined) return;
  resetSeedDraft(task);
  drawerStage.value = "seed";
}

function approveTask(): void {
  const task = selectedTask.value;
  if (task === undefined || !reviewConfirmed.value) return;
  drawerStage.value = "detail";
  void taskStore.approveTask(task.taskId);
}

function downloadTask(task: MockFiniteTask): void {
  const download = taskStore.downloadTask(task.taskId);
  if (download === undefined) return;
  const url = URL.createObjectURL(download.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = download.fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}

function tagType(
  status: MockFiniteTaskPresentationStatus
): "info" | "primary" | "warning" | "success" {
  if (status === "closed") return "success";
  if (status === "dispatch-visible") return "primary";
  if (status === "waiting-admission") return "warning";
  return "info";
}

function actionLabel(task: MockFiniteTask): string {
  if (task.lifecycleState !== "PRE_REVIEW") return "查看详情";
  return task.seedState === "READY" ? "审核并 Approve" : "继续配置";
}

function formatDate(value?: string): string {
  return value === undefined ? "—" : new Date(value).toLocaleString();
}

function formatBytes(value: number): string {
  return value < 1024 ? `${value} B` : `${(value / 1024).toFixed(1)} KiB`;
}
</script>

<template>
  <div class="finite-task-workbench" data-testid="finite-task-workbench">
    <div
      class="task-metric-grid task-metric-grid--finite"
      aria-label="有限 Task Mock 指标"
    >
      <MetricCard
        label="Created"
        :value="taskStore.tasks.length"
        hint="MOCK · session only"
        :icon="Tickets"
        tone="primary"
      />
      <MetricCard
        label="Awaiting Approval"
        :value="taskStore.awaitingApprovalCount"
        hint="MOCK · review ready"
        :icon="Files"
        tone="info"
      />
      <MetricCard
        label="Scheduling"
        :value="taskStore.schedulingCount"
        hint="MOCK · visible phases"
        :icon="Timer"
        tone="warning"
      />
      <MetricCard
        label="Closed"
        :value="taskStore.closedCount"
        hint="MOCK · results"
        :icon="CircleCheck"
        tone="success"
      />
    </div>

    <el-alert class="runtime-semantics" type="warning" :closable="false" show-icon>
      <template #title>
        MOCK 工作台：固定 PRECOMPUTED_TASK_RULE、CLOSE_WHEN_IDLE 与
        allocationRule={}。Dispatch Visible 只是调度阶段可见，不表示 Worker 正在执行。
      </template>
    </el-alert>

    <section class="worker-panel finite-task-panel" aria-label="有限 Task Mock">
      <div class="finite-task-toolbar">
        <div>
          <strong>Finite Task session</strong>
          <span>未审核 Task 可稍后继续；浏览器刷新后全部重置。</span>
        </div>
        <el-button
          type="primary"
          :icon="Plus"
          :disabled="availableGroups.length === 0"
          @click="openCreate"
        >
          创建有限 Task
        </el-button>
      </div>

      <div v-if="runtimeStore.resourceLoadStatus === 'loading'" class="panel-state">
        正在读取 WorkerGroup 与 Event 目录…
      </div>
      <div
        v-else-if="availableGroups.length === 0"
        class="panel-state panel-state--warning"
      >
        当前没有可用于 Mock Task 的 WorkerGroup 描述符。
      </div>
      <div
        v-else-if="taskStore.tasks.length === 0"
        class="panel-state finite-task-empty"
      >
        <el-icon><Tickets /></el-icon>
        <div>
          <strong>当前会话还没有有限 Task</strong>
          <p>创建 Task 后上传 TXT Seeds，再审核并明确 Approve。</p>
        </div>
      </div>
      <div v-else class="task-table-wrap">
        <el-table :data="taskStore.tasks" row-key="taskId" class="task-table">
          <el-table-column label="TASK ID" min-width="210"
            ><template #default="{ row }"
              ><code>{{ row.taskId }}</code></template
            ></el-table-column
          >
          <el-table-column label="WORKER GROUP" min-width="235"
            ><template #default="{ row }"
              ><span class="task-group-id">{{ row.workerGroupId }}</span></template
            ></el-table-column
          >
          <el-table-column label="SEED" min-width="150"
            ><template #default="{ row }">{{
              row.seed?.originalFileName ?? "Missing"
            }}</template></el-table-column
          >
          <el-table-column label="LIFECYCLE" width="165"
            ><template #default="{ row }"
              ><el-tag
                :type="tagType(presentationStatus(row))"
                effect="plain"
                size="small"
                >{{ presentationLabel(presentationStatus(row)) }}</el-tag
              ><span class="mock-inline-badge">MOCK</span></template
            ></el-table-column
          >
          <el-table-column label="ITEMS · MOCK" width="105" align="center"
            ><template #default="{ row }">{{
              row.seed?.lineCount ?? 0
            }}</template></el-table-column
          >
          <el-table-column label="RESULTS · MOCK" width="115" align="center"
            ><template #default="{ row }">{{
              row.results.length
            }}</template></el-table-column
          >
          <el-table-column label="UPDATED AT" min-width="175"
            ><template #default="{ row }">{{
              formatDate(row.updatedAt)
            }}</template></el-table-column
          >
          <el-table-column label="ACTIONS" width="195" fixed="right" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="continueTask(row)">{{
                actionLabel(row)
              }}</el-button>
              <el-button
                v-if="row.lifecycleState === 'TERMINAL'"
                link
                :icon="Download"
                @click="downloadTask(row)"
                >JSONL</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <el-drawer
      v-model="drawerOpen"
      class="worker-detail-drawer finite-task-drawer"
      size="min(620px, 100%)"
      :close-on-click-modal="false"
    >
      <template #header>
        <div class="worker-drawer-heading">
          <span>FINITE TASK · MOCK</span><strong>{{ drawerTitle }}</strong>
        </div>
      </template>

      <div class="finite-task-drawer__body">
        <el-alert
          v-if="taskStore.error"
          type="error"
          :closable="false"
          show-icon
          :title="taskStore.error"
        />

        <template v-if="drawerStage === 'create'">
          <div class="finite-task-step">
            <span>1</span>
            <div>
              <strong>Create Task</strong>
              <p>Descriptor 创建后不再编辑。</p>
            </div>
          </div>
          <div class="finite-task-form">
            <label
              ><span>Task ID</span
              ><el-input v-model="createDraft.taskId" placeholder="finite-task-001"
            /></label>
            <label
              ><span>WorkerGroup</span
              ><select v-model="createDraft.workerGroupId">
                <option
                  v-for="group in availableGroups"
                  :key="group.workerGroupId"
                  :value="group.workerGroupId"
                >
                  {{ group.workerGroupId }}
                </option>
              </select></label
            >
            <div class="finite-task-config-grid">
              <label
                ><span>Priority</span
                ><input
                  v-model.number="createDraft.priority"
                  type="number"
                  min="0"
                  max="99"
              /></label>
              <label
                ><span>Maximum candidates</span
                ><input
                  v-model.number="createDraft.maximumCandidateWorkers"
                  type="number"
                  min="1"
              /></label>
              <label
                ><span>Max retries</span
                ><input
                  v-model.number="createDraft.maxRetryTimes"
                  type="number"
                  min="0"
                  max="98"
              /></label>
            </div>
          </div>
          <div class="finite-task-fixed-contract">
            <div>
              <span>Allocation mechanism</span><code>PRECOMPUTED_TASK_RULE</code>
            </div>
            <div><span>Idle disposition</span><code>CLOSE_WHEN_IDLE</code></div>
            <div><span>Allocation rule</span><code>{}</code></div>
          </div>
          <div class="finite-task-drawer__actions">
            <el-button type="primary" @click="createTask">创建并继续</el-button>
          </div>
        </template>

        <template v-else-if="drawerStage === 'seed' && selectedTask">
          <div class="finite-task-step">
            <span>2</span>
            <div>
              <strong>Upload TXT Seeds</strong>
              <p>每个原始文本行生成一个普通 Item Payload。</p>
            </div>
          </div>
          <dl class="worker-detail__identity">
            <div>
              <dt>Task ID</dt>
              <dd>{{ selectedTask.taskId }}</dd>
            </div>
            <div>
              <dt>WorkerGroup</dt>
              <dd>{{ selectedTask.workerGroupId }}</dd>
            </div>
          </dl>
          <div class="finite-task-form">
            <label
              ><span>EventCode</span
              ><select v-model="seedDraft.eventCode">
                <option
                  v-for="eventCode in selectedGroup?.eventCodes ?? []"
                  :key="eventCode"
                  :value="eventCode"
                >
                  {{ eventCode }}
                </option></select
              ><small>目录选择不表示调度授权。</small></label
            >
            <label
              ><span>Payload Key</span
              ><el-input v-model="seedDraft.payloadKey" placeholder="value"
            /></label>
            <label class="finite-task-file"
              ><span>TXT Seeds</span
              ><input
                type="file"
                accept=".txt,text/plain"
                @change="chooseSeedFile"
              /><small>UTF-8 · 最大 1 MiB · 最多 1000 行</small></label
            >
          </div>
          <div v-if="selectedTask.seed" class="finite-task-seed-summary">
            <span>当前 Seeds</span
            ><strong>{{ selectedTask.seed.originalFileName }}</strong
            ><small
              >{{ selectedTask.seed.lineCount }} lines ·
              {{ formatBytes(selectedTask.seed.byteCount) }}</small
            >
          </div>
          <div class="finite-task-drawer__actions">
            <el-button
              type="primary"
              :disabled="!seedFile"
              :icon="Upload"
              @click="attachSeed"
              >解析并进入审核</el-button
            >
          </div>
        </template>

        <template v-else-if="drawerStage === 'review' && selectedTask?.seed">
          <div class="finite-task-step">
            <span>3</span>
            <div>
              <strong>Review and Approve</strong>
              <p>Approve 只进入 Admission，不保证立即执行。</p>
            </div>
          </div>
          <dl class="worker-detail__identity">
            <div>
              <dt>Task ID</dt>
              <dd>{{ selectedTask.taskId }}</dd>
            </div>
            <div>
              <dt>WorkerGroup</dt>
              <dd>{{ selectedTask.workerGroupId }}</dd>
            </div>
            <div>
              <dt>EventCode</dt>
              <dd>{{ selectedTask.seed.eventCode }}</dd>
            </div>
            <div>
              <dt>Payload mapping</dt>
              <dd>{ "{{ selectedTask.seed.payloadKey }}": "&lt;raw line&gt;" }</dd>
            </div>
            <div>
              <dt>Items</dt>
              <dd>{{ selectedTask.seed.lineCount }}</dd>
            </div>
          </dl>
          <section class="finite-task-preview">
            <h2>First {{ Math.min(5, selectedTask.seed.items.length) }} Items</h2>
            <pre
              v-for="item in selectedTask.seed.items.slice(0, 5)"
              :key="item.lineNumber"
              >{{ String(item.lineNumber).padStart(4, "0") }}  {{
                JSON.stringify(item.payload)
              }}</pre
            >
          </section>
          <div class="finite-task-review-contract">
            <code>PRECOMPUTED_TASK_RULE</code><code>CLOSE_WHEN_IDLE</code
            ><code>allocationRule={}</code>
          </div>
          <label class="finite-task-confirm"
            ><input v-model="reviewConfirmed" type="checkbox" /><span
              >我确认该 Mock Task 进入 Waiting Admission；Dispatch Visible
              不等于正在执行。</span
            ></label
          >
          <div class="finite-task-drawer__actions">
            <el-button @click="backToSeeds">替换 Seeds</el-button
            ><el-button type="primary" :disabled="!reviewConfirmed" @click="approveTask"
              >Approve</el-button
            >
          </div>
        </template>

        <template v-else-if="selectedTask">
          <div class="finite-task-detail-heading">
            <el-tag :type="tagType(presentationStatus(selectedTask))" effect="plain">{{
              presentationLabel(presentationStatus(selectedTask))
            }}</el-tag
            ><span>MOCK</span>
          </div>
          <section class="finite-task-timeline">
            <div class="is-complete">
              <span></span><strong>Created</strong
              ><small>{{ formatDate(selectedTask.createdAt) }}</small>
            </div>
            <div :class="{ 'is-complete': selectedTask.approvedAt }">
              <span></span><strong>Waiting Admission</strong
              ><small>{{ formatDate(selectedTask.approvedAt) }}</small>
            </div>
            <div
              :class="{
                'is-complete': ['RUNNING_VISIBLE', 'TERMINAL'].includes(
                  selectedTask.lifecycleState
                )
              }"
            >
              <span></span><strong>Dispatch Visible</strong
              ><small>不表示正在执行</small>
            </div>
            <div :class="{ 'is-complete': selectedTask.closedAt }">
              <span></span><strong>Idle Close</strong
              ><small>{{ formatDate(selectedTask.closedAt) }}</small>
            </div>
          </section>
          <dl class="worker-detail__identity">
            <div>
              <dt>Task ID</dt>
              <dd>{{ selectedTask.taskId }}</dd>
            </div>
            <div>
              <dt>WorkerGroup</dt>
              <dd>{{ selectedTask.workerGroupId }}</dd>
            </div>
            <div>
              <dt>Seed</dt>
              <dd>{{ selectedTask.seed?.originalFileName ?? "Missing" }}</dd>
            </div>
            <div>
              <dt>Items / Results</dt>
              <dd>
                {{ selectedTask.seed?.lineCount ?? 0 }} /
                {{ selectedTask.results.length }}
              </dd>
            </div>
            <div>
              <dt>Output</dt>
              <dd>{{ selectedTask.outputFile ?? "Not published" }}</dd>
            </div>
          </dl>
          <div class="finite-task-drawer__actions">
            <el-button
              v-if="selectedTask.lifecycleState === 'TERMINAL'"
              type="primary"
              :icon="Download"
              @click="downloadTask(selectedTask)"
              >下载 Mock JSONL</el-button
            >
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>
