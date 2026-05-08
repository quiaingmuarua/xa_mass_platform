<template>
  <section class="worker-debug-panel">
    <div class="debug-summary">
      <div>
        <div class="debug-worker-line">
          <strong class="mono">{{ worker.workerId }}</strong>
          <el-tag :type="statusTagType">
            {{ worker.status }}
          </el-tag>
        </div>
        <div class="row-secondary">
          Debug actions are submitted as normal tasks and routed through the
          engine with a fixed
          <span class="mono">sharedConfig.targetWorkerId</span>.
        </div>
      </div>
      <div class="summary-meta">
        <div class="row-secondary">
          Operator:
          <span class="mono">{{ currentOperatorId || '-' }}</span>
        </div>
        <div class="row-secondary">
          Target project default:
          <span class="mono">{{ defaultProject || '-' }}</span>
        </div>
      </div>
    </div>

    <el-alert
      v-if="!canCreateDebugTask"
      class="debug-alert"
      type="info"
      :closable="false"
      title="Read-only mode. This panel now submits targeted tasks, so task:create permission is required."
    />

    <el-alert
      v-if="sendDebugError"
      class="debug-alert"
      type="error"
      :closable="false"
      :title="sendDebugError"
    />

    <el-alert
      v-if="lastSubmission"
      class="debug-alert"
      type="success"
      :closable="false"
      :title="`Debug task created: ${lastSubmission.taskId}`"
    >
      <div class="row-secondary">
        Event:
        <span class="mono">{{ lastSubmission.eventCode }}</span>
      </div>
      <div class="row-secondary">
        Project:
        <span class="mono">{{ lastSubmission.project }}</span>
      </div>
    </el-alert>

    <el-card class="page-card debug-form-card">
      <template #header>
        <div class="card-header">
          <strong>Create debug task</strong>
          <span class="row-secondary">
            The UI stays on worker detail, but execution now goes through normal
            task scheduling.
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
            <el-form-item label="Payload mode">
              <el-select v-model="debugForm.mode">
                <el-option label="Text to JSON" value="text" />
                <el-option label="Raw JSON payload" value="raw-json" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="Event Code">
              <el-input
                v-model="debugForm.eventCode"
                placeholder="mock.state.get"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item v-if="debugForm.mode === 'text'" label="Text payload">
          <el-input
            v-model="debugForm.text"
            type="textarea"
            :rows="4"
            placeholder="Type text that will be wrapped into a JSON payload object"
          />
          <div class="field-hint">
            Text mode becomes
            <span class="mono">{ "text": "..." }</span>
            so the debug task stays on the JSON task contract.
          </div>
        </el-form-item>

        <el-form-item v-else label="Payload JSON">
          <el-input
            v-model="debugForm.rawPayload"
            type="textarea"
            :rows="8"
            placeholder='{"millis":400}'
          />
          <div class="field-hint">
            Use raw JSON when the target event expects structured payload data.
          </div>
        </el-form-item>

        <div class="target-hint">
          Target worker:
          <span class="mono">{{ worker.workerId }}</span>
        </div>

        <div class="debug-actions">
          <el-button
            type="primary"
            :disabled="!canCreateDebugTask"
            :loading="sendingDebugMessage"
            @click="handleCreateDebugTask"
          >
            Create targeted task
          </el-button>
        </div>
      </el-form>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import {ElMessage} from 'element-plus'
import {computed, ref, watch} from 'vue'
import {useAuth} from '@/auth/use-auth'
import {invokeSyncTaskDebug} from '@/api/tasks'
import type {TaskDebugSyncRequest} from '@/types/tasks'
import type {WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'
import {hasPermission} from '@/utils/permissions'

type PresetKey = 'state' | 'delay' | 'disconnect'

const TARGET_WORKER_ID = 'targetWorkerId'

const props = defineProps<{
  worker: WorkerListItem
  projectOptions?: string[]
}>()

const { user } = useAuth()

const sendingDebugMessage = ref(false)
const sendDebugError = ref('')
const lastSubmission = ref<{
  taskId: string
  project: string
  eventCode: string
} | null>(null)
const debugForm = ref({
  project: '',
  mode: 'raw-json',
  eventCode: 'mock.state.get',
  text: '',
  rawPayload: '{\n  "includeRuntime": true\n}',
})

const canCreateDebugTask = computed(() => hasPermission('task:create'))
const currentOperatorId = computed(() => user.value?.id?.trim() ?? '')
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
const defaultProject = computed(
  () => props.worker.supportedProjects[0] ?? props.projectOptions?.[0] ?? '',
)
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
    sendDebugError.value = ''
    lastSubmission.value = null
  },
  { immediate: true },
)

async function handleCreateDebugTask(): Promise<void> {
  sendDebugError.value = ''

  let payload: Record<string, unknown>
  let eventCode: string
  try {
    eventCode = debugForm.value.eventCode.trim()
    if (!eventCode) {
      throw new Error('Event code is required.')
    }
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

  const project = debugForm.value.project.trim() || defaultProject.value
  if (!project) {
    sendDebugError.value = 'Project is required.'
    return
  }

  if (!currentOperatorId.value) {
    sendDebugError.value = 'Authenticated operator id is required.'
    return
  }

  const request: TaskDebugSyncRequest = {
    userId: currentOperatorId.value,
    project,
    taskName: `worker-debug:${eventCode}`,
    eventCode,
    payloadType: 'JSON',
    sharedConfig: {
      [TARGET_WORKER_ID]: props.worker.workerId,
    },
    inputs: [payload],
    maxRuntimeSeconds: 60,
  }

  sendingDebugMessage.value = true
  try {
    const result = await invokeSyncTaskDebug(request)
    lastSubmission.value = {
      taskId: result.taskId,
      project,
      eventCode,
    }
    ElMessage.success(`Debug task created: ${result.taskId}`)
    if (debugForm.value.mode === 'text') {
      debugForm.value.text = ''
    }
  } catch (error) {
    sendDebugError.value = toErrorMessage(
      error,
      'Failed to create targeted debug task.',
    )
  } finally {
    sendingDebugMessage.value = false
  }
}

function applyPreset(preset: PresetKey): void {
  debugForm.value.mode = 'raw-json'

  if (preset === 'state') {
    debugForm.value.eventCode = 'mock.state.get'
    debugForm.value.rawPayload = JSON.stringify(
      {
        includeRuntime: true,
      },
      null,
      2,
    )
    return
  }

  if (preset === 'delay') {
    debugForm.value.eventCode = 'mock.delay.response'
    debugForm.value.rawPayload = JSON.stringify(
      {
        millis: 400,
      },
      null,
      2,
    )
    return
  }

  debugForm.value.eventCode = 'mock.disconnect'
  debugForm.value.rawPayload = JSON.stringify({}, null, 2)
}

function resetDebugForm(): void {
  debugForm.value = {
    project: defaultProject.value,
    mode: 'raw-json',
    eventCode: 'mock.state.get',
    text: '',
    rawPayload: '{\n  "includeRuntime": true\n}',
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

.summary-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-end;
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

.target-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7a90;
}

.debug-actions {
  display: flex;
  justify-content: flex-end;
}

.field-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #6b7a90;
}
</style>
