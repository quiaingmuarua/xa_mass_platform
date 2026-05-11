<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Projects</h2>
        <p class="page-subtitle">
          Project is the control-plane scope for capability grants, principals,
          worker coverage, and task ownership. Use this page to start from the
          project boundary instead of treating project as a loose task label.
        </p>
      </div>
      <el-button @click="loadProjects">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadProjects"
    />

    <template v-else>
      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Projects</div>
          <div class="metric-value">{{ projects.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Enabled projects</div>
          <div class="metric-value">{{ enabledProjectCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Scoped principals</div>
          <div class="metric-value">{{ totalSubmitterCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Project tasks</div>
          <div class="metric-value">{{ tasks.length }}</div>
        </div>
      </section>

      <el-card class="page-card">
        <PageSectionSkeleton v-if="loading" />

        <PageEmptyState
          v-else-if="projectRows.length === 0"
          description="No project metadata is currently available."
        />

        <el-table v-else :data="projectRows" row-key="code">
          <el-table-column prop="code" label="Project" min-width="240">
            <template #default="{ row }">
              <div class="row-primary">{{ row.name }}</div>
              <div class="row-secondary mono">{{ row.code }}</div>
            </template>
          </el-table-column>
          <el-table-column label="Tenant" min-width="120">
            <template #default="{ row }">
              {{ row.tenantId || 'default' }}
            </template>
          </el-table-column>
          <el-table-column label="State" min-width="110">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? 'ENABLED' : 'DISABLED' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="Events" min-width="100">
            <template #default="{ row }">
              {{ row.eventCount }}
            </template>
          </el-table-column>
          <el-table-column label="Principals" min-width="100">
            <template #default="{ row }">
              {{ row.submitterCount }}
            </template>
          </el-table-column>
          <el-table-column label="Workers" min-width="100">
            <template #default="{ row }">
              {{ row.workerCount }}
            </template>
          </el-table-column>
          <el-table-column label="Tasks" min-width="100">
            <template #default="{ row }">
              {{ row.taskCount }}
            </template>
          </el-table-column>
          <el-table-column label="Owner" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.ownerPrincipalId || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Description" min-width="260">
            <template #default="{ row }">
              <span class="row-secondary">{{ row.description || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Actions" min-width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openProject(row.code)">
                Open detail
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {
  listProjects,
  listProjectSubmitters,
} from '@/api/projects'
import {listTasks} from '@/api/tasks'
import {listWorkers} from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {
  ProjectMetadata,
  ProjectSubmitterMetadata,
} from '@/types/projects'
import type {TaskListItem} from '@/types/tasks'
import type {WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'

interface ProjectRow extends ProjectMetadata {
  eventCount: number
  submitterCount: number
  workerCount: number
  taskCount: number
}

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const projects = ref<ProjectMetadata[]>([])
const tasks = ref<TaskListItem[]>([])
const workers = ref<WorkerListItem[]>([])
const submittersByProject = ref<Record<string, ProjectSubmitterMetadata[]>>({})

const enabledProjectCount = computed(
  () => projects.value.filter((project) => project.enabled).length,
)
const totalSubmitterCount = computed(() =>
  Object.values(submittersByProject.value).reduce(
    (sum, items) => sum + items.length,
    0,
  ),
)
const projectRows = computed<ProjectRow[]>(() =>
  projects.value
    .map((project) => ({
      ...project,
      eventCount: project.eventCodes.length,
      submitterCount: submittersForProject(project.code).length,
      workerCount: workersForProject(project).length,
      taskCount: tasks.value.filter((task) => task.project === project.code).length,
    }))
    .sort((left, right) => left.code.localeCompare(right.code)),
)

async function loadProjects(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [projectItems, workerResponse, taskResponse] = await Promise.all([
      listProjects(),
      listWorkers(),
      listTasks(),
    ])
    const submitterEntries = await Promise.all(
      projectItems.map(async (project) => [
        project.code,
        await listProjectSubmitters(project.code),
      ] as const),
    )
    projects.value = projectItems
    workers.value = workerResponse.items
    tasks.value = taskResponse.items
    submittersByProject.value = Object.fromEntries(submitterEntries)
  } catch (error) {
    projects.value = []
    workers.value = []
    tasks.value = []
    submittersByProject.value = {}
    errorMessage.value = toErrorMessage(error, 'Failed to load projects.')
  } finally {
    loading.value = false
  }
}

function submittersForProject(projectCode: string): ProjectSubmitterMetadata[] {
  return submittersByProject.value[projectCode] ?? []
}

function workersForProject(project: ProjectMetadata): WorkerListItem[] {
  const authorizedEventCodes = new Set(project.eventCodes)
  return workers.value.filter((worker) => {
    if (worker.supportedProjects.includes(project.code)) {
      return true
    }
    if (
      worker.eventBindings?.some((binding) =>
        binding.projectCodes.includes(project.code),
      )
    ) {
      return true
    }
    return worker.supportedEventCodes.some((eventCode) =>
      authorizedEventCodes.has(eventCode),
    )
  })
}

function openProject(projectCode: string): void {
  void router.push({
    name: 'project-detail',
    params: { projectCode },
  })
}

onMounted(() => {
  void loadProjects()
})
</script>
