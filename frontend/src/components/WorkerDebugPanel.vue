<template>
  <section class="worker-debug-panel">
    <div class="debug-summary">
      <div>
        <div class="debug-worker-line">
          <strong class="mono">{{ worker.workerId }}</strong>
          <el-tag :type="statusTagType">
            {{ worker.status }}
          </el-tag>
          <el-tag v-if="autoRefreshEnabled" type="success" effect="plain">
            Auto refresh
          </el-tag>
        </div>
        <div class="row-secondary">
          Manual debug messaging uses the backend worker transport and only
          targets the selected worker.
        </div>
      </div>
      <div class="summary-actions">
        <el-switch
          v-model="autoRefreshEnabled"
          inline-prompt
          active-text="Auto"
          inactive-text="Manual"
        />
        <el-button :loading="debugHistoryLoading" @click="loadDebugHistory()">
          Refresh history
        </el-button>
      </div>
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

    <el-card class="page-card debug-form-card">
      <template #header>
        <div class="card-header">
          <strong>Send message</strong>
          <span class="row-secondary">
            Current worker project defaults are preloaded, but you can override
            them.
          </span>
        </div>
      </template>

      <div class="preset-row">
        <span class="preset-label">Presets</span>
        <el-button size="small" @click="applyPreset('state')">
          State Snapshot
        </el-button>
        <el-button size="small" @click="applyPreset('delay')">
          Delay 400ms
        </el-button>
        <el-button
          size="small"
          type="danger"
          plain
          @click="applyPreset('disconnect')"
        >
          Disconnect Worker
        </el-button>
      </div>

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
    </el-card>

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
      max-height="520"
    >
      <el-table-column label="Time" min-width="170">
        <template #default="{ row }">
          {{ formatTimestamp(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="Direction" min-width="110">
        <template #default="{ row }">
          <el-tag :type="row.direction === 'OUTBOUND' ? 'primary' : 'success'">
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
      <el-table-column label="Payload" min-width="300">
        <template #default="{ row }">
          <pre class="json-inline">{{ row.payloadJson }}</pre>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getWorkerDebugHistory, sendWorkerDebugMessage } from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type { WorkerDebugMessageRecord, WorkerListItem } from '@/types/workers'
import { toErrorMessage } from '@/utils/errors'
import { hasPermission } from '@/utils/permissions'

type PresetKey = 'state' | 'delay' | 'disconnect'

const props = defineProps<{
  worker: WorkerListItem
  projectOptions?: string[]
}>()

const sendingDebugMessage = ref(false)
const debugHistoryLoading = ref(false)
const debugHistoryError = ref('')
const sendDebugError = ref('')
const debugHistory = ref<WorkerDebugMessageRecord[]>([])
const autoRefreshEnabled = ref(true)
const refreshTimer = ref<number | null>(null)
const debugForm = ref({
  project: '',
  mode: 'text',
  subMsgType: 'manual-chat',
  text: '',
  rawPayload: '{\n  "event": "mock.state.get"\n}',
})

const canSendWorkerMessages = computed(() => hasPermission('worker:edit'))
const debugHistoryItems = computed(() => [...debugHistory.value].reverse())
const debugProjectOptions = computed(() => {
  const options = new Set<string>()
  for (const project of props.projectOptions ?? []) {
    options.add(project)
  }
  for (const project of props.worker.supportedProjects ?? []) {
    options.add(project)
  }
  return [...options]
})
const statusTagType = computed(() => {
  if (props.worker.status === 'ONLINE') {
    return 'success'
  }
  if (props.worker.status === 'OFFLINE') {
    return 'info'
  }
  return 'warning'
})

watch(
  () => props.worker.workerId,
  () => {
    resetDebugForm()
    debugHistory.value = []
    debugHistoryError.value = ''
    sendDebugError.value = ''
    void loadDebugHistory()
  },
  { immediate: true },
)

watch(
  autoRefreshEnabled,
  (enabled) => {
    if (enabled) {
      scheduleAutoRefresh()
      return
    }
    clearRefreshTimer()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  clearRefreshTimer()
})

async function loadDebugHistory(): Promise<void> {
  debugHistoryLoading.value = true
  debugHistoryError.value = ''
  try {
    const response = await getWorkerDebugHistory(props.worker.workerId)
    debugHistory.value = response.items
  } catch (error) {
    debugHistory.value = []
    debugHistoryError.value = toErrorMessage(
      error,
      'Failed to load worker debug history.',
    )
  } finally {
    debugHistoryLoading.value = false
    if (autoRefreshEnabled.value) {
      scheduleAutoRefresh()
    }
  }
}

async function handleSendDebugMessage(): Promise<void> {
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
      workerId: props.worker.workerId,
      project: debugForm.value.project.trim() || undefined,
      msgType: 'CONTROL',
      subMsgType: debugForm.value.subMsgType.trim() || 'manual-chat',
      payload,
    })
    ElMessage.success(`Debug message queued: ${result.messageId}`)
    if (debugForm.value.mode === 'text') {
      debugForm.value.text = ''
    }
    await loadDebugHistory()
  } catch (error) {
    sendDebugError.value = toErrorMessage(
      error,
      'Failed to send debug message.',
    )
  } finally {
    sendingDebugMessage.value = false
  }
}

function applyPreset(preset: PresetKey): void {
  debugForm.value.mode = 'raw-json'
  debugForm.value.subMsgType = 'manual-chat'

  if (preset === 'state') {
    debugForm.value.rawPayload = JSON.stringify(
      {
        event: 'mock.state.get',
      },
      null,
      2,
    )
    return
  }

  if (preset === 'delay') {
    debugForm.value.rawPayload = JSON.stringify(
      {
        event: 'mock.delay.response',
        millis: 400,
      },
      null,
      2,
    )
    return
  }

  debugForm.value.rawPayload = JSON.stringify(
    {
      event: 'mock.disconnect',
    },
    null,
    2,
  )
}

function resetDebugForm(): void {
  debugForm.value = {
    project:
      props.worker.supportedProjects[0] ?? props.projectOptions?.[0] ?? '',
    mode: 'text',
    subMsgType: 'manual-chat',
    text: '',
    rawPayload: '{\n  "event": "mock.state.get"\n}',
  }
}

function scheduleAutoRefresh(): void {
  clearRefreshTimer()
  refreshTimer.value = window.setTimeout(() => {
    void loadDebugHistory()
  }, 3000)
}

function clearRefreshTimer(): void {
  if (refreshTimer.value !== null) {
    window.clearTimeout(refreshTimer.value)
    refreshTimer.value = null
  }
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

function formatTimestamp(value: number): string {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}
</script>

<style scoped>
.worker-debug-panel {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.debug-summary {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.debug-worker-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.summary-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.debug-alert {
  margin-bottom: 0;
}

.debug-form-card {
  overflow: hidden;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.preset-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.preset-label {
  font-size: 13px;
  font-weight: 700;
  color: #445168;
}

.debug-form {
  margin-bottom: 0;
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
}

.field-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #6b7a90;
}

.json-inline {
  margin: 0;
  font-family:
    'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo,
    monospace;
  white-space: pre-wrap;
  word-break: break-word;
  color: #445168;
  font-size: 12px;
}
</style>
