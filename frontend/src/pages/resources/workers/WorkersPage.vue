<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Workers</h2>
        <p class="page-subtitle">
          Worker inventory for the orchestration runtime. This page reads the
          backend worker truth, keeps edit scope limited to supported projects,
          and exposes lightweight manual debug messaging for a selected worker.
        </p>
      </div>
      <el-button @click="loadWorkers">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadWorkers"
    />

    <el-card v-else class="page-card">
      <section class="metric-grid worker-metrics">
        <div class="metric-tile">
          <div class="metric-label">Workers</div>
          <div class="metric-value">{{ workers.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Online</div>
          <div class="metric-value">{{ onlineWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Locked</div>
          <div class="metric-value">{{ lockedWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Contexts</div>
          <div class="metric-value">{{ workerContexts.length }}</div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <PageEmptyState
        v-else-if="workers.length === 0"
        description="No workers are currently registered in the backend runtime."
      />

      <el-table v-else :data="workers" row-key="workerId">
        <el-table-column prop="workerId" label="Worker" min-width="220">
          <template #default="{ row }">
            <div class="row-primary mono">{{ row.workerId }}</div>
            <div class="row-secondary">
              {{ row.workerGroupId || 'no group' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="Status" min-width="120">
          <template #default="{ row }">
            <el-tag :type="tagForWorkerStatus(row.status)">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="agentVersion" label="Agent" min-width="120" />
        <el-table-column label="Projects" min-width="220">
          <template #default="{ row }">
            <el-tag
              v-for="project in row.supportedProjects"
              :key="project"
              class="project-tag"
              round
            >
              {{ project }}
            </el-tag>
            <span
              v-if="row.supportedProjects.length === 0"
              class="row-secondary"
            >
              none
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Contexts" min-width="120">
          <template #default="{ row }">
            {{ contextCountByWorkerId[row.workerId] ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column label="Lock" min-width="110">
          <template #default="{ row }">
            <el-tag :type="row.locked ? 'warning' : 'info'">
              {{ row.locked ? 'LOCKED' : 'FREE' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="lastHeartbeat"
          label="Last heartbeat"
          min-width="180"
        />
        <el-table-column label="Attributes" min-width="220">
          <template #default="{ row }">
            <pre class="json-inline">{{ formatJson(row.attributes) }}</pre>
          </template>
        </el-table-column>
        <el-table-column label="Actions" min-width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDebugDialog(row)">
              Debug messages
            </el-button>
            <el-button
              v-if="canEditWorkers"
              v-permission="'worker:edit'"
              link
              type="primary"
              @click="openProjectEditor(row)"
            >
              Edit projects
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="projectDialogVisible"
      title="Edit supported projects"
      width="460px"
    >
      <p class="dialog-subtitle">
        Worker:
        <span class="mono">{{ editingWorker?.workerId }}</span>
      </p>

      <PageErrorState
        v-if="projectOptionsError"
        :message="projectOptionsError"
        @retry="loadProjectOptions"
      />

      <el-checkbox-group v-else v-model="selectedProjects">
        <el-checkbox
          v-for="project in projectOptions"
          :key="project"
          :label="project"
          :value="project"
        />
      </el-checkbox-group>

      <PageErrorState
        v-if="saveErrorMessage"
        class="save-error"
        :message="saveErrorMessage"
        :show-retry="false"
      />

      <template #footer>
        <el-button @click="projectDialogVisible = false">Cancel</el-button>
        <el-button
          type="primary"
          :loading="savingProjects"
          :disabled="projectOptionsError.length > 0"
          @click="saveSupportedProjects"
        >
          Save
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="debugDialogVisible"
      title="Worker debug messages"
      width="1040px"
      destroy-on-close
    >
      <template v-if="debuggingWorker">
        <div class="debug-summary">
          <div>
            <div class="debug-worker-line">
              <strong class="mono">{{ debuggingWorker.workerId }}</strong>
              <el-tag :type="tagForWorkerStatus(debuggingWorker.status)">
                {{ debuggingWorker.status }}
              </el-tag>
            </div>
            <div class="row-secondary">
              Manual debug messaging uses the backend worker transport and only
              targets the selected worker.
            </div>
          </div>
          <el-button :loading="debugHistoryLoading" @click="loadDebugHistory()">
            Refresh history
          </el-button>
        </div>

        <el-alert
          v-if="!canSendWorkerMessages"
          class="debug-alert"
          type="info"
          :closable="false"
          title="Read-only mode. You can inspect message history but cannot send debug messages."
        />

        <el-alert
          v-if="debugHistoryError"
          class="debug-alert"
          type="error"
          :closable="false"
          :title="debugHistoryError"
        />

        <el-alert
          v-if="sendDebugError"
          class="debug-alert"
          type="error"
          :closable="false"
          :title="sendDebugError"
        />

        <el-form label-position="top" class="debug-form">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="Project">
                <el-select
                  v-model="debugForm.project"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  placeholder="Select or type a project"
                >
                  <el-option
                    v-for="project in debugProjectOptions"
                    :key="project"
                    :label="project"
                    :value="project"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="Message mode">
                <el-select v-model="debugForm.mode">
                  <el-option label="Text chat" value="text" />
                  <el-option label="Raw JSON payload" value="raw-json" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="Sub message type">
                <el-input v-model="debugForm.subMsgType" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item v-if="debugForm.mode === 'text'" label="Message text">
            <el-input
              v-model="debugForm.text"
              type="textarea"
              :rows="4"
              placeholder="Type a manual debug message for this worker"
            />
          </el-form-item>

          <el-form-item v-else label="Payload JSON">
            <el-input
              v-model="debugForm.rawPayload"
              type="textarea"
              :rows="8"
              placeholder='{"event":"mock.state.get"}'
            />
            <div class="field-hint">
              Use raw JSON if you want to trigger a structured manual debug
              command instead of sending plain text.
            </div>
          </el-form-item>

          <div class="debug-actions">
            <el-button
              type="primary"
              :disabled="!canSendWorkerMessages"
              :loading="sendingDebugMessage"
              @click="handleSendDebugMessage"
            >
              Send to selected worker
            </el-button>
          </div>
        </el-form>

        <div class="history-header">
          <strong>Message history</strong>
          <span class="row-secondary">
            Latest records for the selected worker only.
          </span>
        </div>

        <PageSectionSkeleton v-if="debugHistoryLoading" />

        <PageEmptyState
          v-else-if="debugHistoryItems.length === 0"
          description="No debug messages have been recorded for this worker yet."
        />

        <el-table
          v-else
          :data="debugHistoryItems"
          row-key="messageId"
          max-height="420"
        >
          <el-table-column label="Time" min-width="170">
            <template #default="{ row }">
              {{ formatTimestamp(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column label="Direction" min-width="110">
            <template #default="{ row }">
              <el-tag
                :type="row.direction === 'OUTBOUND' ? 'primary' : 'success'"
              >
                {{ row.direction }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="Status" min-width="120" />
          <el-table-column prop="project" label="Project" min-width="120" />
          <el-table-column label="Type" min-width="180">
            <template #default="{ row }">
              <div>{{ row.msgType }}</div>
              <div class="row-secondary">{{ row.subMsgType }}</div>
            </template>
          </el-table-column>
          <el-table-column label="Message IDs" min-width="220">
            <template #default="{ row }">
              <div class="mono row-secondary">{{ row.messageId }}</div>
              <div v-if="row.replyToMessageId" class="mono row-secondary">
                replyTo: {{ row.replyToMessageId }}
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="Detail" min-width="220" />
          <el-table-column label="Payload" min-width="280">
            <template #default="{ row }">
              <pre class="json-inline">{{ row.payloadJson }}</pre>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { listProjectCodes } from '@/api/configs'
import {
  getWorkerDebugHistory,
  listWorkerContexts,
  listWorkers,
  sendWorkerDebugMessage,
  updateWorkerSupportedProjects,
} from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {
  WorkerContextListItem,
  WorkerDebugMessageRecord,
  WorkerListItem,
} from '@/types/workers'
import { toErrorMessage } from '@/utils/errors'
import { hasPermission } from '@/utils/permissions'

const loading = ref(false)
const savingProjects = ref(false)
const sendingDebugMessage = ref(false)
const debugHistoryLoading = ref(false)
const workers = ref<WorkerListItem[]>([])
const workerContexts = ref<WorkerContextListItem[]>([])
const errorMessage = ref('')
const saveErrorMessage = ref('')
const projectOptionsError = ref('')
const projectOptions = ref<string[]>([])
const selectedProjects = ref<string[]>([])
const editingWorker = ref<WorkerListItem | null>(null)
const projectDialogVisible = ref(false)
const debugDialogVisible = ref(false)
const debuggingWorker = ref<WorkerListItem | null>(null)
const debugHistoryError = ref('')
const sendDebugError = ref('')
const debugHistory = ref<WorkerDebugMessageRecord[]>([])
const debugRefreshTimer = ref<number | null>(null)
const debugForm = ref({
  project: '',
  mode: 'text',
  subMsgType: 'manual-chat',
  text: '',
  rawPayload: '{\n  "event": "mock.state.get"\n}',
})

const canEditWorkers = computed(() => hasPermission('worker:edit'))
const canSendWorkerMessages = computed(() => hasPermission('worker:edit'))
const onlineWorkerCount = computed(
  () => workers.value.filter((worker) => worker.status === 'ONLINE').length,
)
const lockedWorkerCount = computed(
  () => workers.value.filter((worker) => worker.locked).length,
)
const contextCountByWorkerId = computed<Record<string, number>>(() => {
  return workerContexts.value.reduce<Record<string, number>>((acc, context) => {
    acc[context.workerId] = (acc[context.workerId] ?? 0) + 1
    return acc
  }, {})
})
const debugHistoryItems = computed(() => [...debugHistory.value].reverse())
const debugProjectOptions = computed(() => {
  const options = new Set<string>()
  for (const project of projectOptions.value) {
    options.add(project)
  }
  for (const project of debuggingWorker.value?.supportedProjects ?? []) {
    options.add(project)
  }
  return [...options]
})

async function loadWorkers(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [workerResponse, contextResponse] = await Promise.all([
      listWorkers(),
      listWorkerContexts(),
    ])
    workers.value = workerResponse.items
    workerContexts.value = contextResponse.items
  } catch (error) {
    workers.value = []
    workerContexts.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load workers.')
  } finally {
    loading.value = false
  }
}

async function loadProjectOptions(): Promise<void> {
  projectOptionsError.value = ''

  try {
    projectOptions.value = await listProjectCodes()
  } catch (error) {
    projectOptions.value = []
    projectOptionsError.value = toErrorMessage(
      error,
      'Failed to load project options.',
    )
  }
}

function openProjectEditor(worker: WorkerListItem): void {
  editingWorker.value = worker
  selectedProjects.value = [...worker.supportedProjects]
  saveErrorMessage.value = ''
  projectDialogVisible.value = true

  if (projectOptions.value.length === 0) {
    void loadProjectOptions()
  }
}

async function saveSupportedProjects(): Promise<void> {
  if (!editingWorker.value) {
    return
  }

  savingProjects.value = true
  saveErrorMessage.value = ''

  try {
    await updateWorkerSupportedProjects(
      editingWorker.value.workerId,
      selectedProjects.value,
    )
    projectDialogVisible.value = false
    await loadWorkers()
  } catch (error) {
    saveErrorMessage.value = toErrorMessage(
      error,
      'Failed to update supported projects.',
    )
  } finally {
    savingProjects.value = false
  }
}

function openDebugDialog(worker: WorkerListItem): void {
  debuggingWorker.value = worker
  debugDialogVisible.value = true
  debugHistoryError.value = ''
  sendDebugError.value = ''
  debugHistory.value = []
  debugForm.value = {
    project: worker.supportedProjects[0] ?? projectOptions.value[0] ?? '',
    mode: 'text',
    subMsgType: 'manual-chat',
    text: '',
    rawPayload: '{\n  "event": "mock.state.get"\n}',
  }

  if (projectOptions.value.length === 0) {
    void loadProjectOptions()
  }
  void loadDebugHistory(worker.workerId)
}

async function loadDebugHistory(
  workerId = debuggingWorker.value?.workerId,
): Promise<void> {
  if (!workerId) {
    return
  }

  debugHistoryLoading.value = true
  debugHistoryError.value = ''
  try {
    const response = await getWorkerDebugHistory(workerId)
    debugHistory.value = response.items
  } catch (error) {
    debugHistory.value = []
    debugHistoryError.value = toErrorMessage(
      error,
      'Failed to load worker debug history.',
    )
  } finally {
    debugHistoryLoading.value = false
  }
}

async function handleSendDebugMessage(): Promise<void> {
  if (!debuggingWorker.value) {
    return
  }

  sendDebugError.value = ''

  let payload: Record<string, unknown>
  try {
    payload =
      debugForm.value.mode === 'text'
        ? buildTextPayload(debugForm.value.text)
        : parseJsonObject(debugForm.value.rawPayload, 'Payload JSON')
  } catch (error) {
    sendDebugError.value = toErrorMessage(
      error,
      'Worker debug payload is invalid.',
    )
    return
  }

  sendingDebugMessage.value = true
  try {
    const result = await sendWorkerDebugMessage({
      workerId: debuggingWorker.value.workerId,
      project: debugForm.value.project.trim() || undefined,
      msgType: 'CONTROL',
      subMsgType: debugForm.value.subMsgType.trim() || 'manual-chat',
      payload,
    })
    ElMessage.success(`Debug message queued: ${result.messageId}`)
    if (debugForm.value.mode === 'text') {
      debugForm.value.text = ''
    }
    await loadDebugHistory(debuggingWorker.value.workerId)
    scheduleDebugHistoryRefresh(debuggingWorker.value.workerId)
  } catch (error) {
    sendDebugError.value = toErrorMessage(
      error,
      'Failed to send debug message.',
    )
  } finally {
    sendingDebugMessage.value = false
  }
}

function scheduleDebugHistoryRefresh(workerId: string): void {
  if (debugRefreshTimer.value !== null) {
    window.clearTimeout(debugRefreshTimer.value)
  }
  debugRefreshTimer.value = window.setTimeout(() => {
    void loadDebugHistory(workerId)
    debugRefreshTimer.value = null
  }, 1000)
}

function buildTextPayload(text: string): Record<string, unknown> {
  const normalizedText = text.trim()
  if (!normalizedText) {
    throw new Error('Message text is required.')
  }
  return {
    text: normalizedText,
  }
}

function parseJsonObject(
  value: string,
  fieldLabel: string,
): Record<string, unknown> {
  const source = value.trim()
  if (!source) {
    throw new Error(`${fieldLabel} is required.`)
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(source)
  } catch {
    throw new Error(`${fieldLabel} must be valid JSON.`)
  }

  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error(`${fieldLabel} must be a JSON object.`)
  }

  return parsed as Record<string, unknown>
}

function tagForWorkerStatus(status: string): 'success' | 'info' | 'warning' {
  if (status === 'ONLINE') {
    return 'success'
  }
  if (status === 'OFFLINE') {
    return 'info'
  }
  return 'warning'
}

function formatJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

function formatTimestamp(value: number): string {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}

onMounted(() => {
  void loadWorkers()
})
</script>

<style scoped>
.worker-metrics {
  margin-bottom: 20px;
}

.project-tag {
  margin: 0 6px 6px 0;
}

.dialog-subtitle {
  margin: 0 0 16px;
  color: #6b7a90;
}

.save-error {
  margin-top: 16px;
}

.debug-summary {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

.debug-worker-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.debug-alert {
  margin-bottom: 16px;
}

.debug-form {
  margin-bottom: 24px;
}

.debug-actions {
  display: flex;
  justify-content: flex-end;
}

.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}

.field-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #6b7a90;
}
</style>
