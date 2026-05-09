<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Task List</h2>
        <p class="page-subtitle">
          Core orchestration list view. This page intentionally centers task
          state, project binding, and progress instead of generic CRUD
          boilerplate.
        </p>
      </div>
      <el-button
        v-permission="'task:create'"
        type="primary"
        @click="openCreateDialog"
      >
        Create task shell
      </el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadTasks"
    />

    <el-card v-else class="page-card">
      <div class="toolbar">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="Search by task name or id"
          @keyup.enter="loadTasks"
        />
        <el-select
          v-model="filters.status"
          clearable
          placeholder="Status"
          @change="loadTasks"
        >
          <el-option
            v-for="status in statuses"
            :key="status"
            :label="status"
            :value="status"
          />
        </el-select>
        <el-button @click="loadTasks">Search</el-button>
      </div>

      <PageSectionSkeleton v-if="loading" />

      <PageEmptyState
        v-else-if="rows.length === 0"
        description="No tasks match the current filters."
      />

      <el-table v-else :data="rows" row-key="id">
        <el-table-column prop="taskName" label="Task" min-width="260">
          <template #default="{ row }">
            <div class="row-primary">{{ row.taskName }}</div>
            <div class="row-secondary mono">{{ row.id }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="project" label="Project" min-width="130" />
        <el-table-column prop="status" label="Status" min-width="120">
          <template #default="{ row }">
            <el-tag :type="tagForStatus(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Progress" min-width="170">
          <template #default="{ row }">
            {{ row.successCount }} / {{ row.eligibleCount }}
          </template>
        </el-table-column>
        <el-table-column prop="batchSize" label="Batch Size" min-width="110" />
        <el-table-column prop="updatedAt" label="Updated" min-width="180" />
        <el-table-column label="Actions" min-width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goToTask(row.id)">
              View detail
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="createDialogVisible"
      title="Create task shell"
      width="760px"
      destroy-on-close
    >
      <div class="dialog-intro">
        <p class="dialog-subtitle">
          Minimal real create flow for the orchestration control plane. The task
          will be created in `NEW` state and can be approved from detail view.
        </p>
        <div class="dialog-meta">
          Operator:
          <span class="mono">{{ currentOperatorId }}</span>
        </div>
      </div>

      <el-alert
        v-if="projectOptionsError"
        class="dialog-alert"
        type="warning"
        :closable="false"
        :title="projectOptionsError"
      />

      <el-alert
        v-if="starterEventCode"
        class="dialog-alert"
        type="info"
        :closable="false"
        :title="`Metadata starter context: ${starterEventCode}`"
        description="Task shell create stays event-agnostic. This starter only pre-fills the append capability eventCode and payload examples."
      />

      <el-alert
        v-if="starterGuidance.length > 0"
        class="dialog-alert"
        type="info"
        :closable="false"
        title="Starter guidance"
      >
        <ul class="starter-guidance-list">
          <li v-for="item in starterGuidance" :key="item">
            {{ item }}
          </li>
        </ul>
      </el-alert>

      <el-alert
        v-if="createErrorMessage"
        class="dialog-alert"
        type="error"
        :closable="false"
        :title="createErrorMessage"
      />

      <el-form label-position="top">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Project" required>
              <el-select
                v-model="createForm.project"
                filterable
                allow-create
                default-first-option
                clearable
                placeholder="Select or type a project"
                :loading="projectOptionsLoading"
              >
                <el-option
                  v-for="project in projectOptions"
                  :key="project"
                  :label="project"
                  :value="project"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Append event code" required>
              <el-input
                v-model="createForm.eventCode"
                placeholder="demo.dispatch"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Batch size">
              <el-input-number
                v-model="createForm.batchSize"
                :min="1"
                :step="1"
                controls-position="right"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Max retry count">
              <el-input-number
                v-model="createForm.defaultMsgMaxRetryCount"
                :min="0"
                :step="1"
                controls-position="right"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Max runtime seconds">
              <el-input-number
                v-model="createForm.maxRuntimeSeconds"
                :min="0"
                :step="60"
                controls-position="right"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Intake mode">
              <el-switch
                v-model="createForm.openEnded"
                inline-prompt
                active-text="Open"
                inactive-text="Sealed"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="Items" required>
          <el-input
            v-model="createForm.itemsText"
            type="textarea"
            :rows="8"
            placeholder='One JSON object per line, for example:
{"target":"alpha"}
{"target":"beta"}'
          />
          <div class="field-hint">
            One work item per line. Each line must be a JSON object and will be
            sent through the item ingest API.
          </div>
        </el-form-item>

        <el-form-item label="Shared config">
          <el-input
            v-model="createForm.sharedConfigText"
            type="textarea"
            :rows="6"
            placeholder='Optional JSON object, for example:
{"textContent":"hello","objective":"smoke"}'
          />
          <div class="field-hint">
            Optional task-level config object. Keep scenario-specific labels
            here only when the backend/runtime contract requires them.
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createDialogVisible = false">Cancel</el-button>
        <el-button type="primary" :loading="creatingTask" @click="handleCreate">
          Create shell
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {ElMessage} from 'element-plus'
import {computed, onActivated, onMounted, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {listProjectCodes} from '@/api/configs'
import {appendTaskItems, createTaskShell, listTasks, sealTask} from '@/api/tasks'
import {useAuth} from '@/auth/use-auth'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {
  TaskItemBatchAppendRequest,
  TaskListItem,
  TaskShellCreateRequest,
} from '@/types/tasks'
import {toErrorMessage} from '@/utils/errors'
import {resolveTaskStarterDraft, stringifyStarterItems, stringifyStarterSharedConfig,} from '@/utils/task-starters'

const router = useRouter()
const route = useRoute()
const { user } = useAuth()

const loading = ref(false)
const creatingTask = ref(false)
const createDialogVisible = ref(false)
const rows = ref<TaskListItem[]>([])
const errorMessage = ref('')
const createErrorMessage = ref('')
const projectOptionsError = ref('')
const projectOptionsLoading = ref(false)
const projectOptions = ref<string[]>([])
const handledDraftSignature = ref('')
const starterGuidance = ref<string[]>([])
const filters = reactive({
  keyword: '',
  status: '' as TaskListItem['status'] | '',
})
const createForm = reactive({
  project: '',
  eventCode: '',
  batchSize: 1,
  defaultMsgMaxRetryCount: 3,
  openEnded: false,
  maxRuntimeSeconds: 0,
  itemsText: '{"target":"alpha"}\n{"target":"beta"}',
  sharedConfigText: '{}',
})

const currentOperatorId = computed(
  () => user.value?.id || user.value?.name || 'unknown',
)
const starterEventCode = computed(() => {
  const value = route.query.eventCode
  return typeof value === 'string' ? value : ''
})

const statuses: TaskListItem['status'][] = [
  'NEW',
  'READY',
  'RUNNING',
  'PAUSED',
  'BLOCKED',
  'TERMINAL',
]

const tagType: Record<
  TaskListItem['status'],
  'info' | 'success' | 'warning' | 'primary' | 'danger'
> = {
  NEW: 'info',
  READY: 'success',
  RUNNING: 'primary',
  PAUSED: 'warning',
  BLOCKED: 'danger',
  TERMINAL: 'info',
}

function tagForStatus(
  status: TaskListItem['status'],
): 'info' | 'success' | 'warning' | 'primary' | 'danger' {
  return tagType[status]
}

async function loadTasks(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const response = await listTasks(filters)
    rows.value = response.items
  } catch (error) {
    rows.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load task list.')
  } finally {
    loading.value = false
  }
}

async function loadProjectOptions(): Promise<void> {
  projectOptionsLoading.value = true
  projectOptionsError.value = ''

  try {
    projectOptions.value = await listProjectCodes()
    if (!createForm.project && projectOptions.value.length > 0) {
      createForm.project = projectOptions.value[0]
    }
  } catch (error) {
    projectOptionsError.value = toErrorMessage(
      error,
      'Failed to load project options. You can still type a project code manually.',
    )
  } finally {
    projectOptionsLoading.value = false
  }
}

function openCreateDialog(): void {
  resetCreateForm()
  applyCreateDraftFromQuery()
  createErrorMessage.value = ''
  createDialogVisible.value = true

  if (projectOptions.value.length === 0 && !projectOptionsLoading.value) {
    void loadProjectOptions()
  }
}

function resetCreateForm(): void {
  createForm.project = projectOptions.value[0] ?? ''
  createForm.eventCode = starterEventCode.value || ''
  createForm.batchSize = 1
  createForm.defaultMsgMaxRetryCount = 3
  createForm.openEnded = false
  createForm.maxRuntimeSeconds = 0
  createForm.itemsText = '{"target":"alpha"}\n{"target":"beta"}'
  createForm.sharedConfigText = '{}'
  starterGuidance.value = []
}

function maybeOpenCreateDialogFromQuery(): void {
  if (route.query.create !== '1') {
    return
  }

  const signature = JSON.stringify({
    create: route.query.create,
    project: route.query.project,
    eventCode: route.query.eventCode,
  })

  if (handledDraftSignature.value === signature) {
    return
  }

  handledDraftSignature.value = signature
  openCreateDialog()
}

function applyCreateDraftFromQuery(): void {
  const projectCode =
    typeof route.query.project === 'string' ? route.query.project : ''
  const eventCode =
    typeof route.query.eventCode === 'string' ? route.query.eventCode : undefined

  if (!projectCode && !eventCode) {
    starterGuidance.value = []
    return
  }

  const starter = resolveTaskStarterDraft({
    projectCode,
    eventCode,
  })

  createForm.project = starter.projectCode
  createForm.eventCode = starter.eventCode || ''
  createForm.batchSize = starter.batchSize
  createForm.defaultMsgMaxRetryCount = starter.defaultMsgMaxRetryCount
  createForm.openEnded = starter.openEnded
  createForm.maxRuntimeSeconds = starter.maxRuntimeSeconds
  createForm.itemsText = stringifyStarterItems(starter.items)
  createForm.sharedConfigText = stringifyStarterSharedConfig(
    starter.sharedConfig,
  )
  starterGuidance.value = starter.guidance
}

async function handleCreate(): Promise<void> {
  createErrorMessage.value = ''

  let shellRequest: TaskShellCreateRequest
  let appendRequest: TaskItemBatchAppendRequest
  let openEnded: boolean
  try {
    const draft = buildCreateDraft()
    shellRequest = draft.shellRequest
    appendRequest = draft.appendRequest
    openEnded = draft.openEnded
  } catch (error) {
    createErrorMessage.value = toErrorMessage(error, 'Task request is invalid.')
    return
  }

  creatingTask.value = true
  try {
    const result = await createTaskShell(shellRequest)
    await appendTaskItems(result.taskId, appendRequest)
    if (!openEnded) {
      await sealTask(result.taskId)
    }
    ElMessage.success(result.message)
    createDialogVisible.value = false
    await loadTasks()
    await router.push({
      name: 'task-detail',
      params: { taskId: result.taskId },
    })
  } catch (error) {
    createErrorMessage.value = toErrorMessage(error, 'Failed to create task.')
  } finally {
    creatingTask.value = false
  }
}

function buildCreateDraft(): {
  shellRequest: TaskShellCreateRequest
  appendRequest: TaskItemBatchAppendRequest
  openEnded: boolean
} {
  const project = createForm.project.trim()
  const eventCode = createForm.eventCode.trim()

  if (!project) {
    throw new Error('Project is required.')
  }
  if (!eventCode) {
    throw new Error('Append event code is required.')
  }

  const items = parseItemLines(createForm.itemsText)
  const sharedConfig = parseJsonObject(
    createForm.sharedConfigText,
    'Shared config',
  )
  return {
    shellRequest: {
      userId: currentOperatorId.value,
      project,
      sharedConfig,
      executionSpec: {
        batchSize: Math.max(1, Number(createForm.batchSize) || 1),
        maxRuntimeSeconds: Math.max(0, Number(createForm.maxRuntimeSeconds) || 0),
      },
    },
    appendRequest: {
      eventCode,
      items,
      defaultMsgMaxRetryCount: Math.max(
        0,
        Number(createForm.defaultMsgMaxRetryCount) || 0,
      ),
    },
    openEnded: createForm.openEnded,
  }
}

function parseItemLines(value: string): Array<Record<string, unknown>> {
  const lines = value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)

  if (lines.length === 0) {
    throw new Error('Items must contain at least one JSON object line.')
  }

  return lines.map((line, index) => {
    let parsed: unknown
    try {
      parsed = JSON.parse(line)
    } catch {
      throw new Error(`Item line ${index + 1} is not valid JSON.`)
    }

    if (!isPlainRecord(parsed)) {
      throw new Error(`Item line ${index + 1} must be a JSON object.`)
    }

    return parsed
  })
}

function parseJsonObject(
  value: string,
  fieldLabel: string,
): Record<string, unknown> {
  const source = value.trim() || '{}'

  let parsed: unknown
  try {
    parsed = JSON.parse(source)
  } catch {
    throw new Error(`${fieldLabel} must be a valid JSON object.`)
  }

  if (!isPlainRecord(parsed)) {
    throw new Error(`${fieldLabel} must be a JSON object.`)
  }

  return parsed
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function goToTask(taskId: string): void {
  void router.push({ name: 'task-detail', params: { taskId } })
}

onMounted(() => {
  void loadTasks()
  maybeOpenCreateDialogFromQuery()
})

onActivated(() => {
  maybeOpenCreateDialogFromQuery()
})

watch(
  () => route.query,
  () => {
    maybeOpenCreateDialogFromQuery()
  },
)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}

.toolbar :deep(.el-input) {
  width: 260px;
}

.toolbar :deep(.el-select) {
  width: 160px;
}

.row-primary {
  font-weight: 700;
  color: #122033;
}

.row-secondary {
  margin-top: 4px;
  color: #6b7a90;
  font-size: 12px;
}

.dialog-intro {
  margin-bottom: 16px;
}

.dialog-subtitle {
  margin: 0;
  color: #56647a;
}

.dialog-meta {
  margin-top: 8px;
  color: #6b7a90;
  font-size: 13px;
}

.dialog-alert {
  margin-bottom: 16px;
}

.field-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #6b7a90;
}

.starter-guidance-list {
  margin: 0;
  padding-left: 18px;
}

.starter-guidance-list li + li {
  margin-top: 6px;
}
</style>
