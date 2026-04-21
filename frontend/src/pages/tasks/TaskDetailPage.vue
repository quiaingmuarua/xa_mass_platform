<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Task Detail</h2>
        <p class="page-subtitle">
          Runtime-centric detail page shaped around task aggregate, validation
          state, and logical message execution.
        </p>
      </div>
      <div class="actions">
        <el-button @click="goBack">Back</el-button>
        <el-button v-permission="'task:terminate'" type="danger" plain
          >Terminate</el-button
        >
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
              <el-descriptions-item label="Routing">{{
                detail.task.taskRoutingCode
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
              <strong>State validation</strong>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="Valid">{{
                detail.stateValidation.valid
              }}</el-descriptions-item>
              <el-descriptions-item label="Needs resolution">
                {{ detail.stateValidation.needsResolution }}
              </el-descriptions-item>
              <el-descriptions-item label="Total messages">
                {{ detail.stateValidation.totalMessages }}
              </el-descriptions-item>
              <el-descriptions-item label="Processing">
                {{ detail.stateValidation.processingMessages }}
              </el-descriptions-item>
              <el-descriptions-item label="Violations">
                {{
                  detail.stateValidation.violations.length > 0
                    ? detail.stateValidation.violations.join(', ')
                    : '-'
                }}
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
          <strong>Task messages</strong>
        </template>
        <PageEmptyState
          v-if="detail.messages.length === 0"
          description="This task does not currently have message records to display."
        />

        <el-table v-else :data="detail.messages" row-key="msgId">
          <el-table-column prop="msgId" label="Message" min-width="160" />
          <el-table-column prop="status" label="Status" min-width="120" />
          <el-table-column
            prop="latestAttemptWorkerId"
            label="Worker"
            min-width="150"
          />
          <el-table-column prop="retryCount" label="Retry" min-width="100">
            <template #default="{ row }">
              {{ row.retryCount }} / {{ row.maxRetryCount }}
            </template>
          </el-table-column>
          <el-table-column
            prop="finalReason"
            label="Final Reason"
            min-width="160"
          />
          <el-table-column label="Input" min-width="200">
            <template #default="{ row }">
              <pre class="json-inline">{{ formatJson(row.input) }}</pre>
            </template>
          </el-table-column>
          <el-table-column label="Output" min-width="200">
            <template #default="{ row }">
              <pre class="json-inline">{{ formatJson(row.output) }}</pre>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTaskDetail } from '@/api/tasks'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type { TaskDetailResponse } from '@/types/tasks'
import { toErrorMessage } from '@/utils/errors'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref<TaskDetailResponse | null>(null)
const errorMessage = ref('')

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
    detail.value = await getTaskDetail(String(route.params.taskId))
  } catch (error) {
    detail.value = null
    errorMessage.value = toErrorMessage(error, 'Failed to load task detail.')
  } finally {
    loading.value = false
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
