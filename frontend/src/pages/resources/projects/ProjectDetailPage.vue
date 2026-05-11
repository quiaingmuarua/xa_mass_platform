<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">{{ pageTitle }}</h2>
        <p class="page-subtitle">
          Project-scoped control-plane view. This page gathers the authorized
          event set, scoped principals, worker coverage, and task inventory
          under one owner boundary.
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="loadProject">Refresh</el-button>
        <el-button type="primary" @click="openTaskDraft">
          Create task shell
        </el-button>
      </div>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadProject"
    />

    <template v-else>
      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Authorized events</div>
          <div class="metric-value">{{ events.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Scoped principals</div>
          <div class="metric-value">{{ submitters.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Worker coverage</div>
          <div class="metric-value">{{ projectWorkers.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Tasks</div>
          <div class="metric-value">{{ tasks.length }}</div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <template v-else-if="project">
        <el-row :gutter="20">
          <el-col :span="10">
            <el-card class="page-card">
              <template #header>
                <strong>Project summary</strong>
              </template>
              <el-descriptions :column="1" border>
                <el-descriptions-item label="Code">
                  <span class="mono">{{ project.code }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="Tenant">
                  {{ project.tenantId || 'default' }}
                </el-descriptions-item>
                <el-descriptions-item label="State">
                  {{ project.enabled ? 'ENABLED' : 'DISABLED' }}
                </el-descriptions-item>
                <el-descriptions-item label="Owner">
                  <span class="mono">{{ project.ownerPrincipalId || '-' }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="Description">
                  {{ project.description || '-' }}
                </el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>
          <el-col :span="14">
            <el-card class="page-card">
              <template #header>
                <strong>Scope flow</strong>
              </template>
              <ol class="scope-list">
                <li>Pick the project as the coarse owner boundary.</li>
                <li>Choose an authorized event capability for item ingest.</li>
                <li>Use a scoped principal or API key bound to the project.</li>
                <li>Confirm worker coverage before approving active workload.</li>
                <li>Track resulting tasks under the same project scope.</li>
              </ol>
            </el-card>
          </el-col>
        </el-row>

        <el-card class="page-card">
          <template #header>
            <strong>Authorized events</strong>
          </template>
          <PageEmptyState
            v-if="events.length === 0"
            description="This project has no registered authorized events."
          />
          <el-table v-else :data="events" row-key="code">
            <el-table-column prop="code" label="Event" min-width="220">
              <template #default="{ row }">
                <div class="row-primary">{{ row.name }}</div>
                <div class="row-secondary mono">{{ row.code }}</div>
              </template>
            </el-table-column>
            <el-table-column label="Task modes" min-width="160">
              <template #default="{ row }">
                {{ row.taskModes.join(', ') || 'DIRECT_RUNTIME' }}
              </template>
            </el-table-column>
            <el-table-column label="Payload types" min-width="140">
              <template #default="{ row }">
                {{ row.payloadTypes.join(', ') || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="Online workers" min-width="120">
              <template #default="{ row }">
                {{ onlineWorkersForEvent(row.code) }}
              </template>
            </el-table-column>
            <el-table-column label="Actions" min-width="180" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  @click="openTaskDraft(row.code)"
                >
                  Start draft
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-card class="page-card">
              <template #header>
                <strong>Scoped principals</strong>
              </template>
              <PageEmptyState
                v-if="submitters.length === 0"
                description="No project-scoped principals are currently visible."
              />
              <el-table v-else :data="submitters" row-key="principalId">
                <el-table-column prop="principalId" label="Principal" min-width="220">
                  <template #default="{ row }">
                    <div class="row-primary mono">{{ row.principalId }}</div>
                    <div class="row-secondary">{{ row.userId || 'no user binding' }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="Key prefix" min-width="120">
                  <template #default="{ row }">
                    <span class="mono">{{ row.keyPrefix || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="Event scopes" min-width="220">
                  <template #default="{ row }">
                    {{ row.eventScopes.join(', ') || '*' }}
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card class="page-card">
              <template #header>
                <strong>Worker coverage</strong>
              </template>
              <PageEmptyState
                v-if="projectWorkers.length === 0"
                description="No workers currently advertise project-compatible coverage."
              />
              <el-table v-else :data="projectWorkers" row-key="workerId">
                <el-table-column prop="workerId" label="Worker" min-width="220">
                  <template #default="{ row }">
                    <div class="row-primary mono">{{ row.workerId }}</div>
                    <div class="row-secondary">{{ row.workerGroupId || 'no group' }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="Status" min-width="120">
                  <template #default="{ row }">
                    <el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'">
                      {{ row.status }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="Events" min-width="220">
                  <template #default="{ row }">
                    {{ matchingEventsForWorker(row).join(', ') || '-' }}
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
        </el-row>

        <el-card class="page-card">
          <template #header>
            <strong>Project tasks</strong>
          </template>
          <PageEmptyState
            v-if="tasks.length === 0"
            description="No tasks currently exist for this project."
          />
          <el-table v-else :data="tasks" row-key="id">
            <el-table-column prop="taskName" label="Task" min-width="260">
              <template #default="{ row }">
                <div class="row-primary">{{ row.taskName }}</div>
                <div class="row-secondary mono">{{ row.id }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="Status" min-width="120">
              <template #default="{ row }">
                <el-tag :type="tagForTaskStatus(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Progress" min-width="140">
              <template #default="{ row }">
                {{ row.successCount }} / {{ row.eligibleCount }}
              </template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="Updated" min-width="180" />
            <el-table-column label="Actions" min-width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openTask(row.id)">
                  View detail
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {
  getProject,
  listProjectEventDefinitions,
  listProjectSubmitters,
} from '@/api/projects'
import {listTasks} from '@/api/tasks'
import {listWorkers} from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {
  SdkEventDefinition,
} from '@/types/metadata'
import type {
  ProjectMetadata,
  ProjectSubmitterMetadata,
} from '@/types/projects'
import type {TaskListItem} from '@/types/tasks'
import type {WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const project = ref<ProjectMetadata | null>(null)
const events = ref<SdkEventDefinition[]>([])
const submitters = ref<ProjectSubmitterMetadata[]>([])
const workers = ref<WorkerListItem[]>([])
const tasks = ref<TaskListItem[]>([])

const projectCode = computed(() => String(route.params.projectCode ?? '').trim())
const pageTitle = computed(() =>
  project.value ? `${project.value.name} (${project.value.code})` : 'Project detail',
)
const projectWorkers = computed(() => {
  if (!project.value) {
    return []
  }
  const authorizedEventCodes = new Set(project.value.eventCodes)
  return workers.value.filter((worker) => {
    if (worker.supportedProjects.includes(project.value!.code)) {
      return true
    }
    if (
      worker.eventBindings?.some((binding) =>
        binding.projectCodes.includes(project.value!.code),
      )
    ) {
      return true
    }
    return worker.supportedEventCodes.some((eventCode) =>
      authorizedEventCodes.has(eventCode),
    )
  })
})

async function loadProject(): Promise<void> {
  if (!projectCode.value) {
    errorMessage.value = 'Missing project code.'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const [projectMetadata, eventRows, submitterRows, workerResponse, taskResponse] =
      await Promise.all([
        getProject(projectCode.value),
        listProjectEventDefinitions(projectCode.value),
        listProjectSubmitters(projectCode.value),
        listWorkers(),
        listTasks({ project: projectCode.value }),
      ])
    project.value = projectMetadata
    events.value = eventRows
    submitters.value = submitterRows
    workers.value = workerResponse.items
    tasks.value = taskResponse.items
  } catch (error) {
    project.value = null
    events.value = []
    submitters.value = []
    workers.value = []
    tasks.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load project detail.')
  } finally {
    loading.value = false
  }
}

function matchingEventsForWorker(worker: WorkerListItem): string[] {
  if (!project.value) {
    return []
  }
  const projectEventCodes = new Set(project.value.eventCodes)
  return worker.supportedEventCodes.filter((eventCode) =>
    projectEventCodes.has(eventCode),
  )
}

function onlineWorkersForEvent(eventCode: string): number {
  return projectWorkers.value.filter(
    (worker) =>
      worker.status === 'ONLINE' &&
      worker.supportedEventCodes.includes(eventCode),
  ).length
}

function openTaskDraft(eventCode?: string): void {
  void router.push({
    name: 'tasks',
    query: {
      create: '1',
      project: projectCode.value,
      ...(eventCode ? { eventCode } : {}),
    },
  })
}

function openTask(taskId: string): void {
  void router.push({
    name: 'task-detail',
    params: { taskId },
  })
}

function tagForTaskStatus(status: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'RUNNING' || status === 'TERMINAL') {
    return 'success'
  }
  if (status === 'NEW') {
    return 'info'
  }
  if (status === 'PAUSED') {
    return 'warning'
  }
  return 'danger'
}

onMounted(() => {
  void loadProject()
})

watch(
  () => route.params.projectCode,
  () => {
    void loadProject()
  },
)
</script>

<style scoped>
.header-actions {
  display: flex;
  gap: 12px;
}

.scope-list {
  margin: 0;
  padding-left: 18px;
  color: #56647a;
}

.scope-list li + li {
  margin-top: 8px;
}
</style>
