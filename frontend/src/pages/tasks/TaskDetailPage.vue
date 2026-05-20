<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Task Detail</h2>
        <p class="page-subtitle">
          Runtime-centric detail page shaped around task shell and aggregate
          state.
        </p>
      </div>
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
          v-permission="'task:approve'"
          :loading="actionLoading === 'approve'"
          type="success"
          plain
          @click="handleAudit(true)"
        >
          Approve
        </el-button>
        <el-button
          v-if="detail && detail.task.status === 'NEW'"
          v-permission="'task:approve'"
          :loading="actionLoading === 'reject'"
          type="warning"
          plain
          @click="handleAudit(false)"
        >
          Reject
        </el-button>
        <el-button
          v-if="detail && canPause(detail.task.status)"
          v-permission="'task:pause'"
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
          v-permission="'task:resume'"
          :loading="actionLoading === 'resume'"
          type="success"
          plain
          @click="handleResume"
        >
          Resume
        </el-button>
        <el-button
          v-if="detail && detail.task.status !== 'TERMINAL'"
          v-permission="'task:terminate'"
          :loading="actionLoading === 'terminate'"
          type="danger"
          plain
          @click="handleTerminate"
        >
          Terminate
        </el-button>
      </div>
    </header>

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
      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Status</div>
          <div class="metric-value">{{ detail.task.status }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Terminal reason</div>
          <div class="metric-value">
            {{ detail.task.terminalReason ?? '-' }}
          </div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Eligible / success</div>
          <div class="metric-value">
            {{ detail.task.taskEligibleNumber }} /
            {{ detail.task.taskSuccessNumber }}
          </div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Peak assigned workers</div>
          <div class="metric-value">
            {{ detail.task.peakAssignedWorkerCount }}
          </div>
        </div>
        <div v-if="review" class="metric-tile">
          <div class="metric-label">Processing / failed</div>
          <div class="metric-value">
            {{ review.summary.processingItems }} / {{ review.summary.failedItems }}
          </div>
        </div>
        <div v-if="review" class="metric-tile">
          <div class="metric-label">Preview rows</div>
          <div class="metric-value">
            {{ review.summary.previewCount }}
          </div>
        </div>
      </section>

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
            <span v-if="review" class="review-caption">
              worker attribution and result output from the latest visible attempt
            </span>
          </div>
        </template>
        <el-table
          v-if="review"
          :data="review.resultPreview"
          stripe
          class="review-table"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="result-expand-grid">
                <div>
                  <div class="expand-label">Output</div>
                  <pre class="json-block review-json">{{ formatJson(row.output) }}</pre>
                </div>
                <div>
                  <div class="expand-label">Result residue</div>
                  <pre class="json-block review-json">{{ formatJson({
                    workerId: row.workerId,
                    workerContextId: row.workerContextId,
                    batchId: row.batchId,
                    attemptId: row.attemptId,
                    errorCode: row.errorCode,
                    errorMessage: row.errorMessage,
                    finalReason: row.finalReason,
                  }) }}</pre>
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
          <el-table-column
            prop="workerContextId"
            label="Legacy context"
            min-width="180"
          >
            <template #default="{ row }">
              <span class="mono">{{ row.workerContextId || '-' }}</span>
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
  </section>
</template>

<script setup lang="ts">
import {ElMessage, ElMessageBox} from 'element-plus'
import {onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {
  auditTask,
  blockTask,
  downloadTaskResultExport,
  downloadTaskSeedExport,
  getTaskDetail,
  getTaskReview,
  pauseTask,
  resumeTask,
  terminateTask,
} from '@/api/tasks'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {TaskDetailResponse, TaskReviewResponse} from '@/types/tasks'
import {toErrorMessage} from '@/utils/errors'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref<TaskDetailResponse | null>(null)
const review = ref<TaskReviewResponse | null>(null)
const errorMessage = ref('')
const actionLoading = ref('')

function formatJson(value: unknown): string {
  return JSON.stringify(value, null, 2)
}

function goBack(): void {
  void router.push({ name: 'tasks' })
}

async function loadTaskDetail(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [taskDetail, taskReview] = await Promise.all([
      getTaskDetail(String(route.params.taskId)),
      getTaskReview(String(route.params.taskId)),
    ])
    detail.value = taskDetail
    review.value = taskReview
  } catch (error) {
    detail.value = null
    review.value = null
    errorMessage.value = toErrorMessage(error, 'Failed to load task detail.')
  } finally {
    loading.value = false
  }
}

function summarizeRecord(value: Record<string, unknown> | null): string {
  if (!value || Object.keys(value).length === 0) {
    return '-'
  }

  const prioritizedKeys = ['target', 'url', 'text', 'status', 'value', 'detail']
  for (const key of prioritizedKeys) {
    const candidate = value[key]
    if (typeof candidate === 'string' && candidate.trim().length > 0) {
      return compactText(candidate)
    }
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
})

watch(
  () => route.params.taskId,
  () => {
    void loadTaskDetail()
  },
)
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
  color: #6b7a90;
  font-size: 13px;
}

.review-table {
  width: 100%;
}

.review-json {
  padding: 12px 14px;
  border-radius: 10px;
  background: #f7f9fc;
}

.result-expand-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.expand-label {
  margin-bottom: 8px;
  color: #6b7a90;
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
  color: #445168;
}

.json-inline {
  font-size: 12px;
}
</style>
