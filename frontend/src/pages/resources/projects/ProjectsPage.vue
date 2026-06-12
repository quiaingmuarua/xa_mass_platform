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
          <div class="metric-label">WorkerGroups</div>
          <div class="metric-value">{{ workerGroups.length }}</div>
        </div>
      </section>

      <el-card class="page-card">
        <PageSectionSkeleton v-if="loading" />

        <PageEmptyState
          v-else-if="projectRows.length === 0"
          description="No projects are currently available."
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
          <el-table-column label="WorkerGroups" min-width="130">
            <template #default="{ row }">
              {{ row.workerGroupCount }}
            </template>
          </el-table-column>
          <el-table-column label="Reachable unlocked" min-width="150">
            <template #default="{ row }">
              {{ row.reachableUnlockedBindingCount }} / {{ row.workerCount }}
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
  listWorkerGroupCapabilities,
} from '@/api/catalog'
import {
  listProjects,
} from '@/api/projects'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {ProjectDefinition} from '@/types/projects'
import type {WorkerGroupCapability} from '@/types/catalog'
import {toErrorMessage} from '@/utils/errors'

interface ProjectRow extends ProjectDefinition {
  eventCount: number
  workerGroupCount: number
  reachableUnlockedBindingCount: number
  workerCount: number
}

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const projects = ref<ProjectDefinition[]>([])
const workerGroups = ref<WorkerGroupCapability[]>([])

const enabledProjectCount = computed(
  () => projects.value.filter((project) => project.enabled).length,
)
const projectRows = computed<ProjectRow[]>(() =>
  projects.value
    .map((project) => ({
      ...project,
      eventCount: project.eventCodes.length,
      workerGroupCount: workerGroupsForProject(project).length,
      reachableUnlockedBindingCount: workerGroupsForProject(project).reduce(
        (sum, group) => sum + group.reachableUnlockedBindingCount,
        0,
      ),
      workerCount: workerGroupsForProject(project).reduce(
        (sum, group) => sum + group.workerCount,
        0,
      ),
    }))
    .sort((left, right) => left.code.localeCompare(right.code)),
)

async function loadProjects(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [projectItems, workerGroupRows] = await Promise.all([
      listProjects(),
      listWorkerGroupCapabilities(),
    ])
    projects.value = projectItems
    workerGroups.value = workerGroupRows
  } catch (error) {
    projects.value = []
    workerGroups.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load projects.')
  } finally {
    loading.value = false
  }
}

function workerGroupsForProject(project: ProjectDefinition): WorkerGroupCapability[] {
  const authorizedEventCodes = new Set(project.eventCodes)
  return workerGroups.value.filter((group) => {
    if (group.projectCodes.includes(project.code)) {
      return true
    }
    return group.eventBindings.some(
      (binding) =>
        binding.projectCodes.includes(project.code) ||
        authorizedEventCodes.has(binding.eventCode),
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
