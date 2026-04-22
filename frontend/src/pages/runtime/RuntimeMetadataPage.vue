<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Metadata & Discovery</h2>
        <p class="page-subtitle">
          Runtime onboarding view built from backend SDK metadata and live
          worker inventory. Use it to understand which projects, events, and
          currently available workers can participate in dispatch.
        </p>
      </div>
      <div class="header-actions">
        <el-select
          v-model="selectedProjectCode"
          class="project-select"
          placeholder="Project"
        >
          <el-option label="All projects" value="ALL" />
          <el-option
            v-for="project in sortedProjects"
            :key="project.code"
            :label="`${project.name} (${project.code})`"
            :value="project.code"
          />
        </el-select>
        <el-switch
          v-model="showOnlineOnly"
          inline-prompt
          active-text="Online"
          inactive-text="All"
        />
        <el-button @click="loadDiscovery">Refresh</el-button>
      </div>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadDiscovery"
    />

    <template v-else>
      <el-alert
        class="metadata-note"
        type="info"
        :closable="false"
        title="Event support is derived from Worker.supportedProjects plus /sdk/meta project event mappings. Backend APIs remain the source of truth."
      />

      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Enabled projects</div>
          <div class="metric-value">{{ enabledProjectCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Enabled events</div>
          <div class="metric-value">{{ enabledEventCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Online workers</div>
          <div class="metric-value">{{ onlineWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Covered projects</div>
          <div class="metric-value">
            {{ coveredProjectCount }} / {{ enabledProjectCount }}
          </div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <template v-else>
        <el-row :gutter="20">
          <el-col :span="10">
            <el-card class="page-card quickstart-card">
              <template #header>
                <strong>Quick start path</strong>
              </template>
              <ol class="quickstart-list">
                <li>
                  Pick a project from the catalog and confirm it is enabled.
                </li>
                <li>
                  Check the event list for supported payload types and task
                  modes.
                </li>
                <li>
                  Confirm at least one online worker advertises that project.
                </li>
                <li>
                  Create or inspect tasks using the project code and
                  domain-specific input payloads.
                </li>
              </ol>
            </el-card>
          </el-col>
          <el-col :span="14">
            <el-card class="page-card quickstart-card">
              <template #header>
                <strong>Selected scope</strong>
              </template>
              <el-descriptions :column="1" border>
                <el-descriptions-item label="Project">
                  {{ selectedScopeTitle }}
                </el-descriptions-item>
                <el-descriptions-item label="Events">
                  {{ selectedEventRows.length }}
                </el-descriptions-item>
                <el-descriptions-item label="Visible workers">
                  {{ visibleWorkerRows.length }}
                </el-descriptions-item>
                <el-descriptions-item label="Online coverage">
                  {{ selectedOnlineWorkerSummary }}
                </el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>
        </el-row>

        <el-card class="page-card">
          <template #header>
            <strong>Project catalog</strong>
          </template>
          <PageEmptyState
            v-if="projectRows.length === 0"
            description="No SDK project metadata is currently available."
          />
          <el-table v-else :data="projectRows" row-key="code">
            <el-table-column prop="code" label="Project" min-width="220">
              <template #default="{ row }">
                <div class="row-primary">{{ row.name }}</div>
                <div class="row-secondary mono">{{ row.code }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="enabled" label="State" min-width="110">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">
                  {{ row.enabled ? 'ENABLED' : 'DISABLED' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Events" min-width="120">
              <template #default="{ row }">
                {{ row.resolvedEvents.length }}
              </template>
            </el-table-column>
            <el-table-column label="Online workers" min-width="150">
              <template #default="{ row }">
                {{ row.onlineWorkerIds.length }}
              </template>
            </el-table-column>
            <el-table-column
              prop="description"
              label="Description"
              min-width="320"
            />
          </el-table>
        </el-card>

        <el-card class="page-card">
          <template #header>
            <strong>Available workers</strong>
          </template>
          <PageEmptyState
            v-if="visibleWorkerRows.length === 0"
            description="No workers match the selected project and availability filter."
          />
          <el-table v-else :data="visibleWorkerRows" row-key="workerId">
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
            <el-table-column label="Projects" min-width="220">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag
                    v-for="project in visibleItems(row.matchedProjects)"
                    :key="project"
                    :type="projectTagType(project)"
                    round
                  >
                    {{ project }}
                  </el-tag>
                  <span
                    v-if="remainingCount(row.matchedProjects) > 0"
                    class="row-secondary"
                  >
                    +{{ remainingCount(row.matchedProjects) }} more
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Derived events" min-width="280">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag
                    v-for="eventCode in visibleItems(row.derivedEventCodes)"
                    :key="eventCode"
                    type="primary"
                    round
                  >
                    {{ eventCode }}
                  </el-tag>
                  <span
                    v-if="row.derivedEventCodes.length === 0"
                    class="row-secondary"
                  >
                    no metadata match
                  </span>
                  <span
                    v-if="remainingCount(row.derivedEventCodes) > 0"
                    class="row-secondary"
                  >
                    +{{ remainingCount(row.derivedEventCodes) }} more
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column
              prop="lastHeartbeat"
              label="Last heartbeat"
              min-width="180"
            />
            <el-table-column label="Actions" min-width="150" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  @click="openWorkerDetail(row.workerId)"
                >
                  Open worker
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card class="page-card">
          <template #header>
            <strong>Event catalog</strong>
          </template>
          <PageEmptyState
            v-if="selectedEventRows.length === 0"
            description="No event metadata is available for the selected scope."
          />
          <el-table v-else :data="selectedEventRows" row-key="code">
            <el-table-column prop="code" label="Event" min-width="260">
              <template #default="{ row }">
                <div class="row-primary">{{ row.name }}</div>
                <div class="row-secondary mono">{{ row.code }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="enabled" label="State" min-width="110">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">
                  {{ row.enabled ? 'ENABLED' : 'DISABLED' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Payload" min-width="150">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag
                    v-for="payloadType in row.payloadTypes"
                    :key="payloadType"
                    round
                  >
                    {{ payloadType }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Task modes" min-width="170">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag
                    v-for="taskMode in row.taskModes"
                    :key="taskMode"
                    type="warning"
                    round
                  >
                    {{ taskMode }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Projects" min-width="180">
              <template #default="{ row }">
                {{ row.projectCodes.join(', ') || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="Online workers" min-width="150">
              <template #default="{ row }">
                {{ row.onlineWorkerIds.length }}
              </template>
            </el-table-column>
            <el-table-column
              prop="description"
              label="Description"
              min-width="320"
            />
          </el-table>
        </el-card>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listEventMetadata, listProjectMetadata } from '@/api/metadata'
import { listWorkers } from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type { EventMetadata, ProjectMetadata } from '@/types/metadata'
import type { WorkerListItem } from '@/types/workers'
import { toErrorMessage } from '@/utils/errors'

const ALL_PROJECTS = 'ALL'
const TAG_LIMIT = 4

interface ProjectRow extends ProjectMetadata {
  resolvedEvents: EventMetadata[]
  onlineWorkerIds: string[]
}

interface WorkerDiscoveryRow extends WorkerListItem {
  matchedProjects: string[]
  derivedEventCodes: string[]
}

interface EventRow extends EventMetadata {
  projectCodes: string[]
  onlineWorkerIds: string[]
}

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const projects = ref<ProjectMetadata[]>([])
const events = ref<EventMetadata[]>([])
const workers = ref<WorkerListItem[]>([])
const selectedProjectCode = ref(ALL_PROJECTS)
const showOnlineOnly = ref(true)

const sortedProjects = computed(() =>
  [...projects.value].sort((left, right) => left.code.localeCompare(right.code)),
)
const projectByCode = computed<Record<string, ProjectMetadata>>(() =>
  projects.value.reduce<Record<string, ProjectMetadata>>((acc, project) => {
    acc[project.code] = project
    return acc
  }, {}),
)
const eventByCode = computed<Record<string, EventMetadata>>(() =>
  events.value.reduce<Record<string, EventMetadata>>((acc, event) => {
    acc[event.code] = event
    return acc
  }, {}),
)
const eventCodesByProject = computed<Record<string, string[]>>(() =>
  projects.value.reduce<Record<string, string[]>>((acc, project) => {
    acc[project.code] = uniqueStrings(project.eventCodes)
    return acc
  }, {}),
)
const projectCodesByEvent = computed<Record<string, string[]>>(() => {
  return projects.value.reduce<Record<string, string[]>>((acc, project) => {
    project.eventCodes.forEach((eventCode) => {
      acc[eventCode] = [...(acc[eventCode] ?? []), project.code]
    })
    return acc
  }, {})
})
const selectedProject = computed(() => {
  if (selectedProjectCode.value === ALL_PROJECTS) {
    return null
  }

  return projectByCode.value[selectedProjectCode.value] ?? null
})
const displayedProjects = computed(() => {
  if (selectedProject.value) {
    return [selectedProject.value]
  }

  return sortedProjects.value
})
const projectRows = computed<ProjectRow[]>(() =>
  displayedProjects.value.map((project) => {
    const resolvedEvents = project.eventCodes
      .map((eventCode) => eventByCode.value[eventCode])
      .filter((event): event is EventMetadata => Boolean(event))
    const onlineWorkerIds = workers.value
      .filter(
        (worker) =>
          worker.status === 'ONLINE' &&
          worker.supportedProjects.includes(project.code),
      )
      .map((worker) => worker.workerId)

    return {
      ...project,
      resolvedEvents,
      onlineWorkerIds,
    }
  }),
)
const workerRows = computed<WorkerDiscoveryRow[]>(() =>
  workers.value.map((worker) => {
    const matchedProjects =
      selectedProjectCode.value === ALL_PROJECTS
        ? worker.supportedProjects
        : worker.supportedProjects.filter(
            (projectCode) => projectCode === selectedProjectCode.value,
          )
    const derivedEventCodes = uniqueStrings(
      matchedProjects.flatMap(
        (projectCode) => eventCodesByProject.value[projectCode] ?? [],
      ),
    )

    return {
      ...worker,
      matchedProjects,
      derivedEventCodes,
    }
  }),
)
const visibleWorkerRows = computed(() =>
  workerRows.value.filter((worker) => {
    const matchesProject =
      selectedProjectCode.value === ALL_PROJECTS ||
      worker.matchedProjects.length > 0
    const matchesAvailability =
      !showOnlineOnly.value || worker.status === 'ONLINE'

    return matchesProject && matchesAvailability
  }),
)
const selectedEventRows = computed<EventRow[]>(() =>
  events.value
    .filter((event) => {
      if (selectedProjectCode.value === ALL_PROJECTS) {
        return true
      }

      return projectCodesByEvent.value[event.code]?.includes(
        selectedProjectCode.value,
      )
    })
    .sort((left, right) => left.code.localeCompare(right.code))
    .map((event) => {
      const projectCodes = projectCodesByEvent.value[event.code] ?? []
      const onlineWorkerIds = workers.value
        .filter(
          (worker) =>
            worker.status === 'ONLINE' &&
            worker.supportedProjects.some((projectCode) =>
              projectCodes.includes(projectCode),
            ),
        )
        .map((worker) => worker.workerId)

      return {
        ...event,
        projectCodes,
        onlineWorkerIds,
      }
    }),
)
const enabledProjectCount = computed(
  () => projects.value.filter((project) => project.enabled).length,
)
const enabledEventCount = computed(
  () => events.value.filter((event) => event.enabled).length,
)
const onlineWorkerCount = computed(
  () => workers.value.filter((worker) => worker.status === 'ONLINE').length,
)
const coveredProjectCount = computed(
  () =>
    projects.value.filter(
      (project) =>
        project.enabled &&
        workers.value.some(
          (worker) =>
            worker.status === 'ONLINE' &&
            worker.supportedProjects.includes(project.code),
        ),
    ).length,
)
const selectedScopeTitle = computed(() => {
  if (!selectedProject.value) {
    return 'All projects'
  }

  return `${selectedProject.value.name} (${selectedProject.value.code})`
})
const selectedOnlineWorkerSummary = computed(() => {
  const ids = uniqueStrings(
    projectRows.value.flatMap((project) => project.onlineWorkerIds),
  )

  if (ids.length === 0) {
    return 'No online worker coverage'
  }

  return ids.join(', ')
})

async function loadDiscovery(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [projectMetadata, eventMetadata, workerResponse] = await Promise.all([
      listProjectMetadata(),
      listEventMetadata(),
      listWorkers(),
    ])
    projects.value = projectMetadata
    events.value = eventMetadata
    workers.value = workerResponse.items
  } catch (error) {
    projects.value = []
    events.value = []
    workers.value = []
    errorMessage.value = toErrorMessage(
      error,
      'Failed to load runtime metadata discovery data.',
    )
  } finally {
    loading.value = false
  }
}

function openWorkerDetail(workerId: string): void {
  void router.push({ name: 'worker-detail', params: { workerId } })
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

function projectTagType(projectCode: string): 'success' | 'warning' {
  return projectByCode.value[projectCode] ? 'success' : 'warning'
}

function visibleItems(items: string[]): string[] {
  return items.slice(0, TAG_LIMIT)
}

function remainingCount(items: string[]): number {
  return Math.max(0, items.length - TAG_LIMIT)
}

function uniqueStrings(values: string[]): string[] {
  return Array.from(new Set(values.filter((value) => value.length > 0)))
}

onMounted(() => {
  void loadDiscovery()
})
</script>

<style scoped>
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.project-select {
  width: 300px;
}

.metadata-note {
  border-radius: 14px;
}

.quickstart-card {
  height: 100%;
}

.quickstart-list {
  margin: 0;
  padding-left: 20px;
  color: #445168;
}

.quickstart-list li + li {
  margin-top: 10px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
</style>
