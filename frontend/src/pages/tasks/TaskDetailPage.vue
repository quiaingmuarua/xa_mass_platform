<template>
  <ConsolePage
    title="Task Detail"
    eyebrow="Task runtime"
    subtitle="Runtime-centric detail page shaped around task shell, lifecycle, aggregate state, and result rows."
    width="wide"
    tone="operator"
  >
    <template #badge>
      <StatusBadge
        v-if="detail"
        :status="detail.task.status"
        :type="taskStatusTag(detail.task.status)"
      />
    </template>
    <template #actions>
      <div class="actions">
        <el-button @click="goBack">Back</el-button>
        <el-button
          v-if="review"
          plain
          @click="handleSeedExport"
        >
          Export seeds
        </el-button>
        <el-button
          v-if="review"
          plain
          @click="handleResultExport"
        >
          Export results
        </el-button>
        <el-button
          v-if="detail && detail.task.status === 'NEW'"
          v-permission="'task:govern'"
          :loading="actionLoading === 'approve'"
          type="success"
          plain
          @click="handleAudit(true)"
        >
          Approve
        </el-button>
        <el-button
          v-if="detail && detail.task.status === 'NEW'"
          v-permission="'task:govern'"
          :loading="actionLoading === 'reject'"
          type="warning"
          plain
          @click="handleAudit(false)"
        >
          Reject
        </el-button>
        <el-button
          v-if="detail && canPause(detail.task.status)"
          v-permission="'task:control'"
          :loading="actionLoading === 'pause'"
          type="warning"
          plain
          @click="handlePause"
        >
          Pause
        </el-button>
        <el-button
          v-if="detail && canBlock(detail.task.status)"
          v-permission="'task:edit'"
          :loading="actionLoading === 'block'"
          type="warning"
          plain
          @click="handleBlock"
        >
          Block
        </el-button>
        <el-button
          v-if="detail && detail.task.status === 'PAUSED'"
          v-permission="'task:control'"
          :loading="actionLoading === 'resume'"
          type="success"
          plain
          @click="handleResume"
        >
          Resume
        </el-button>
        <el-button
          v-if="detail && canSealTask(detail.task)"
          v-permission="'task:control'"
          :loading="actionLoading === 'seal'"
          type="primary"
          plain
          @click="handleSeal"
        >
          Seal intake
        </el-button>
        <el-button
          v-if="detail && detail.task.status !== 'TERMINAL'"
          v-permission="'task:control'"
          :loading="actionLoading === 'terminate'"
          type="danger"
          plain
          @click="handleTerminate"
        >
          Terminate
        </el-button>
      </div>
    </template>

    <PageSectionSkeleton v-if="loading" />

    <PageErrorState
      v-else-if="errorMessage"
      :message="errorMessage"
      @retry="loadTaskDetail"
    />

    <PageEmptyState
      v-else-if="!detail"
      description="The requested task is not available in the current data source."
    />

    <template v-else>
      <MetricGrid :columns="4">
        <MetricCard
          label="Status"
          :value="detail.task.status"
          tone="primary"
          compact
        />
        <MetricCard
          label="Terminal reason"
          :value="detail.task.terminalReason ?? '-'"
          compact
        />
        <MetricCard
          label="Eligible / success"
          :value="`${detail.task.taskEligibleNumber} / ${detail.task.taskSuccessNumber}`"
          tone="success"
          compact
        />
        <MetricCard
          label="Peak assigned workers"
          :value="detail.task.peakAssignedWorkerCount"
        />
        <MetricCard
          v-if="review"
          label="Processing / failed"
          :value="`${review.summary.processingItems} / ${review.summary.failedItems}`"
          tone="warning"
          compact
        />
        <MetricCard
          v-if="review"
          label="Preview rows"
          :value="review.summary.previewCount"
        />
        <MetricCard
          label="Result rows"
          :value="resultPreviewRows.length"
        />
      </MetricGrid>

      <el-row :gutter="20">
        <el-col :span="12">
          <el-card class="page-card detail-card">
            <template #header>
              <strong>Task summary</strong>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="Task ID">
                <span class="mono">{{ detail.task.tid }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="Task name">{{
                detail.task.taskName
              }}</el-descriptions-item>
              <el-descriptions-item label="Project">{{
                detail.task.project
              }}</el-descriptions-item>
              <el-descriptions-item label="Operator">{{
                detail.task.user.name
              }}</el-descriptions-item>
              <el-descriptions-item label="Batch size">{{
                detail.task.batchSize
              }}</el-descriptions-item>
              <el-descriptions-item label="Intake status">{{
                detail.task.intakeStatus ?? '-'
              }}</el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card class="page-card detail-card">
            <template #header>
              <strong>Runtime summary</strong>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="Target messages">{{
                detail.task.taskTargetNumber
              }}</el-descriptions-item>
              <el-descriptions-item label="Eligible messages">
                {{ detail.task.taskEligibleNumber }}
              </el-descriptions-item>
              <el-descriptions-item label="Success messages">
                {{ detail.task.taskSuccessNumber }}
              </el-descriptions-item>
              <el-descriptions-item label="Non-success messages">
                {{ detail.task.taskNonSuccessNumber }}
              </el-descriptions-item>
              <el-descriptions-item label="Updated">
                {{ detail.task.updateTime }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
      </el-row>

      <el-card class="page-card">
        <template #header>
          <strong>Shared config</strong>
        </template>
        <pre class="json-block">{{ formatJson(detail.task.sharedConfig) }}</pre>
      </el-card>

      <el-card class="page-card">
        <template #header>
          <div class="review-header">
            <strong>Seed preview</strong>
            <span v-if="review" class="review-caption">
              {{ review.summary.totalItems }} items in task
              <template v-if="review.summary.hasMore">
                , showing first {{ review.summary.previewLimit }}
              </template>
            </span>
          </div>
        </template>
        <el-table
          v-if="review"
          :data="review.seedPreview"
          stripe
          class="review-table"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <pre class="json-block review-json">{{ formatJson(row.input) }}</pre>
            </template>
          </el-table-column>
          <el-table-column prop="messageId" label="Message" min-width="220">
            <template #default="{ row }">
              <span class="mono">{{ row.messageId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="eventCode" label="Event" min-width="180" />
          <el-table-column prop="status" label="Status" width="120" />
          <el-table-column prop="createTime" label="Created" min-width="180" />
          <el-table-column label="Seed summary" min-width="280">
            <template #default="{ row }">
              <span>{{ summarizeRecord(row.input) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card class="page-card">
        <template #header>
          <div class="review-header">
            <strong>Result preview</strong>
            <span v-if="resultWindow" class="review-caption">
              {{ resultWindow.items.length }} runtime result rows
              <template v-if="resultWindow.hasMore">
                , more available
              </template>
            </span>
          </div>
        </template>
        <el-table
          v-if="resultWindow || review"
          :data="resultPreviewRows"
          stripe
          class="review-table"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="result-expand-grid">
                <div>
                  <div class="expand-label">Output</div>
                  <ResultPayloadViewer :value="row.output" />
                </div>
                <div>
                  <div class="expand-label">Dispatch metadata</div>
                  <ResultPayloadViewer :value="{
                    workerId: row.workerId,
                    batchId: row.batchId,
                    attemptId: row.attemptId,
                    errorCode: row.errorCode,
                    errorMessage: row.errorMessage,
                    finalReason: row.finalReason,
                  }" />
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="messageId" label="Message" min-width="220">
            <template #default="{ row }">
              <span class="mono">{{ row.messageId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="Status" width="120" />
          <el-table-column prop="workerId" label="Worker" min-width="160">
            <template #default="{ row }">
              <span class="mono">{{ row.workerId || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="updateTime" label="Updated" min-width="180" />
          <el-table-column label="Result summary" min-width="280">
            <template #default="{ row }">
              <span>{{ summarizeRecord(row.output) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </ConsolePage>
</template>

<script setup lang="ts">
import {ElMessage, ElMessageBox} from 'element-plus'
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {
  auditTask,
  blockTask,
  downloadTaskResultExport,
  downloadTaskSeedExport,
  getTaskDetail,
  getTaskReview,
  getTaskResults,
  pauseTask,
  resumeTask,
  sealTask,
  terminateTask,
} from '@/api/tasks'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import MetricCard from '@/console-kit/data/MetricCard.vue'
import MetricGrid from '@/console-kit/data/MetricGrid.vue'
import StatusBadge from '@/console-kit/data/StatusBadge.vue'
import ConsolePage from '@/console-kit/layout/ConsolePage.vue'
import type {
  TaskDetailRecord,
  TaskDetailResponse,
  TaskResultPreviewItem,
  TaskResultWindowResponse,
  TaskReviewResponse,
} from '@/types/tasks'
import {toErrorMessage} from '@/utils/errors'
import ResultPayloadViewer from '@/console-kit/data/ResultPayloadViewer.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref<TaskDetailResponse | null>(null)
const review = ref<TaskReviewResponse | null>(null)
const resultWindow = ref<TaskResultWindowResponse | null>(null)
const errorMessage = ref('')
const actionLoading = ref('')
const LIVE_TASK_REFRESH_INTERVAL_MS = 2_000
let liveTaskRefreshTimer: ReturnType<typeof window.setInterval> | null = null

const resultPreviewRows = computed<TaskResultPreviewItem[]>(() => {
  const runtimeRows = resultWindow.value?.items ?? []
  if (runtimeRows.length > 0) {
    return runtimeRows
  }
  return review.value?.resultPreview ?? []
})

function formatJson(value: unknown): string {
  return JSON.stringify(value, null, 2)
}

function goBack(): void {
  void router.push({ name: 'tasks' })
}

async function loadTaskDetail(options: { silent?: boolean } = {}): Promise<void> {
  if (!options.silent) {
    loading.value = true
  }
  errorMessage.value = ''

  try {
    const [taskDetail, taskReview, taskResults] = await Promise.all([
      getTaskDetail(String(route.params.taskId)),
      getTaskReview(String(route.params.taskId)),
      getTaskResults(String(route.params.taskId)),
    ])
    detail.value = taskDetail
    review.value = taskReview
    resultWindow.value = taskResults
  } catch (error) {
    detail.value = null
    review.value = null
    resultWindow.value = null
    errorMessage.value = toErrorMessage(error, 'Failed to load task detail.')
  } finally {
    if (!options.silent) {
      loading.value = false
    }
  }
}

function shouldRefreshLiveTask(): boolean {
  const status = detail.value?.task.status
  return status === 'READY' || status === 'RUNNING'
}

function summarizeRecord(value: Record<string, unknown> | null): string {
  if (!value || Object.keys(value).length === 0) {
    return '-'
  }

  const prioritizedKeys = [
    'phoneNumber',
    'defaultRegion',
    'countryIso2',
    'requiredFingerprintProfile',
    'requiredNetworkOperatorMccMnc',
    'url',
    'hostname',
    'expectedStatus',
    'actualFixtureStatus',
    'symbol',
    'baseCurrency',
    'quoteCurrencies',
    'assets',
    'ip',
    'fixtureName',
    'schemaRef',
    'expectedOutcome',
    'traceLabel',
    'target',
    'text',
    'status',
    'value',
    'detail',
  ]
  const summaryParts: string[] = []
  for (const key of prioritizedKeys) {
    const candidate = value[key]
    if (typeof candidate === 'string' && candidate.trim().length > 0) {
      summaryParts.push(`${key}=${candidate}`)
    } else if (typeof candidate === 'number' || typeof candidate === 'boolean') {
      summaryParts.push(`${key}=${String(candidate)}`)
    } else if (Array.isArray(candidate) && candidate.length > 0) {
      summaryParts.push(`${key}=${candidate.slice(0, 3).join(',')}`)
    }
    if (summaryParts.length >= 5) {
      break
    }
  }
  if (summaryParts.length > 0) {
    return compactText(summaryParts.join(' | '))
  }

  return compactText(JSON.stringify(value))
}

function compactText(value: string): string {
  const normalized = value.replace(/\s+/g, ' ').trim()
  if (normalized.length <= 72) {
    return normalized
  }
  return `${normalized.slice(0, 69)}...`
}

function canPause(status: TaskDetailResponse['task']['status']): boolean {
  return status === 'READY' || status === 'RUNNING'
}

function canBlock(status: TaskDetailResponse['task']['status']): boolean {
  return status === 'READY' || status === 'RUNNING'
}

function canSealTask(task: TaskDetailRecord): boolean {
  return task.intakeStatus === 'OPEN' && task.status !== 'TERMINAL'
}

function taskStatusTag(
  status: TaskDetailResponse['task']['status'],
): 'success' | 'warning' | 'danger' | 'primary' | 'info' {
  if (status === 'TERMINAL') {
    return 'success'
  }
  if (status === 'READY' || status === 'RUNNING') {
    return 'primary'
  }
  if (status === 'PAUSED' || status === 'BLOCKED' || status === 'NEW') {
    return 'warning'
  }
  return 'danger'
}

async function handleAudit(approved: boolean): Promise<void> {
  if (!detail.value) {
    return
  }

  await ElMessageBox.confirm(
    approved
      ? 'Approve this task and move it to READY?'
      : 'Reject this task and move it to BLOCKED?',
    approved ? 'Approve Task' : 'Reject Task',
    {
      type: approved ? 'success' : 'warning',
    },
  )

  actionLoading.value = approved ? 'approve' : 'reject'
  try {
    const result = await auditTask(detail.value.task.tid, approved)
    ElMessage.success(result.message)
    await loadTaskDetail()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(toErrorMessage(error, 'Task audit failed.'))
    }
  } finally {
    actionLoading.value = ''
  }
}

async function handlePause(): Promise<void> {
  await runTaskAction(
    'pause',
    'Pause Task',
    'Pause this task and stop new dispatching?',
    () => pauseTask(String(route.params.taskId)),
  )
}

async function handleBlock(): Promise<void> {
  await runTaskAction(
    'block',
    'Block Task',
    'Block this task and hold further processing?',
    () => blockTask(String(route.params.taskId)),
  )
}

async function handleResume(): Promise<void> {
  await runTaskAction(
    'resume',
    'Resume Task',
    'Resume this paused task?',
    () => resumeTask(String(route.params.taskId)),
  )
}

async function handleSeal(): Promise<void> {
  await runTaskAction(
    'seal',
    'Seal Task',
    'Seal task intake and allow normal terminal convergence?',
    () => sealTask(String(route.params.taskId)),
    'info',
  )
}

async function handleTerminate(): Promise<void> {
  await runTaskAction(
    'terminate',
    'Terminate Task',
    'Terminate this task? This action moves the task to TERMINAL.',
    () => terminateTask(String(route.params.taskId)),
    'warning',
  )
}

function handleSeedExport(): void {
  downloadTaskSeedExport(String(route.params.taskId))
}

function handleResultExport(): void {
  downloadTaskResultExport(String(route.params.taskId))
}

async function runTaskAction(
  action: string,
  title: string,
  message: string,
  request: () => Promise<{ message: string }>,
  confirmType: 'success' | 'warning' | 'info' | 'error' = 'warning',
): Promise<void> {
  await ElMessageBox.confirm(message, title, {
    type: confirmType,
  })

  actionLoading.value = action
  try {
    const result = await request()
    ElMessage.success(result.message)
    await loadTaskDetail()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(toErrorMessage(error, `${title} failed.`))
    }
  } finally {
    actionLoading.value = ''
  }
}

onMounted(() => {
  void loadTaskDetail()
  liveTaskRefreshTimer = window.setInterval(() => {
    if (!shouldRefreshLiveTask() || actionLoading.value) {
      return
    }
    void loadTaskDetail({ silent: true })
  }, LIVE_TASK_REFRESH_INTERVAL_MS)
})

watch(
  () => route.params.taskId,
  () => {
    void loadTaskDetail()
  },
)

onBeforeUnmount(() => {
  if (liveTaskRefreshTimer !== null) {
    window.clearInterval(liveTaskRefreshTimer)
    liveTaskRefreshTimer = null
  }
})
</script>

<style scoped>
.actions {
  display: flex;
  gap: 12px;
}

.detail-card {
  height: 100%;
}

.review-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.review-caption {
  color: var(--color-text-subtle);
  font-size: 13px;
}

.review-table {
  width: 100%;
}

.review-json {
  padding: 12px 14px;
  border-radius: var(--radius-card);
  background: var(--color-surface-muted);
}

.result-expand-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.expand-label {
  margin-bottom: 8px;
  color: var(--color-text-subtle);
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
}

.json-block,
.json-inline {
  margin: 0;
  font-family:
    'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo,
    monospace;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-muted);
}

.json-inline {
  font-size: 12px;
}
</style>
