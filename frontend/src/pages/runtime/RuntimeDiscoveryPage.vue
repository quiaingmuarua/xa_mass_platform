<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Control-plane Discovery</h2>
        <p class="page-subtitle">
          Project directory, event capability inventory, and worker
          reachability in one control-plane view. Use it to inspect which projects
          and events were explicitly registered in the backend runtime, then
          compare them with currently reachable workers.
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
          v-model="showReachableOnly"
          inline-prompt
          active-text="Reachable"
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
        class="discovery-note"
        type="info"
        :closable="false"
        title="Project and event entries come from control-plane registration. Event capability comes from /api/v1/catalog/event-capabilities: direct runtime handlers are explicit, and task-backed worker coverage comes from supportedEventCodes."
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
          <div class="metric-label">Reachable workers</div>
          <div class="metric-value">{{ reachableWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Projects with worker coverage</div>
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
                  Confirm at least one reachable worker declares the target
                  event in its supported event list.
                </li>
                <li>
                  Use project selection as a coarse scope filter only; runtime
                  dispatch truth comes from explicit event capability plus backend
                  validation.
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
                <el-descriptions-item label="Focused event">
                  {{ selectedEventTitle }}
                </el-descriptions-item>
                <el-descriptions-item label="Events">
                  {{ selectedEventRows.length }}
                </el-descriptions-item>
                <el-descriptions-item label="Visible workers">
                  {{ visibleWorkerRows.length }}
                </el-descriptions-item>
                <el-descriptions-item label="Reachable coverage">
                  {{ selectedReachableWorkerSummary }}
                </el-descriptions-item>
                <el-descriptions-item label="Project filter">
                  {{
                    selectedProject
                      ? 'Scoped by selected project events plus optional project hints'
                      : 'All projects'
                  }}
                </el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>
        </el-row>

        <el-card class="page-card api-key-access-card">
          <template #header>
            <strong>API-key credential access</strong>
          </template>
            <el-alert
            class="api-key-access-note"
            type="info"
            :closable="false"
            title="API-key credential identity is only for credential-backed task submission through POST /api/v1/tasks. It is not the control-console login state and does not affect menu permissions."
          />
          <el-descriptions :column="1" border>
            <el-descriptions-item label="Introspection">
              <el-tag :type="apiKeyStatusType">
                {{ apiKeyStatusLabel }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="Principal">
              {{ apiKeyProfile?.principalId ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Resolved user">
              {{ apiKeyProfile?.userId ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Project scope">
              {{ apiKeyProfile?.projectScope ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Permissions">
              {{ apiKeyProfile?.permissions?.join(', ') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Project scopes">
              {{ apiKeyProfile?.projectScopes?.join(', ') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Event scopes">
              {{ apiKeyProfile?.eventScopes?.join(', ') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Attributes">
              <pre class="inline-json-block">{{ apiKeyAttributesText }}</pre>
            </el-descriptions-item>
            <el-descriptions-item label="Create route">
              Use the same task create route:
              <span class="mono">POST /api/v1/tasks</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-card class="page-card detail-card">
              <template #header>
                <strong>Selected project</strong>
              </template>
              <PageEmptyState
                v-if="!selectedProject"
                description="Select a project to inspect the catalog and start from a task draft."
              />
              <template v-else>
                <el-descriptions :column="1" border>
                  <el-descriptions-item label="Project">
                    {{ selectedProject.name }} ({{ selectedProject.code }})
                  </el-descriptions-item>
                  <el-descriptions-item label="State">
                    {{ selectedProject.enabled ? 'ENABLED' : 'DISABLED' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Events">
                    {{ selectedProject.eventCodes.join(', ') || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Workers for project events">
                    {{ selectedProjectCoverageSummary }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Description">
                    {{ selectedProject.description || '-' }}
                  </el-descriptions-item>
                </el-descriptions>
                <div class="starter-preview">
                  <div class="starter-preview-title">Starter sharedConfig</div>
                  <pre class="json-block">{{
                    selectedProjectStarterSharedConfigText
                  }}</pre>
                </div>
                <div class="starter-preview">
                  <div class="starter-preview-title">Starter inputs</div>
                  <pre class="json-block">{{ selectedProjectStarterInputsText }}</pre>
                </div>
                <div class="detail-actions">
                  <el-button type="primary" @click="openTaskDraftForProject()">
                    Start task draft
                  </el-button>
                  <el-button @click="openProjectDetail()">
                    Open project
                  </el-button>
                  <el-button @click="openTaskListForProject()">Open tasks</el-button>
                </div>
              </template>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card class="page-card detail-card">
              <template #header>
                <strong>Selected event</strong>
              </template>
              <PageEmptyState
                v-if="!selectedEventRow"
                description="Select an event to inspect task modes, payload shape, and jump into a starter task draft."
              />
              <template v-else>
                <el-descriptions :column="1" border>
                  <el-descriptions-item label="Event">
                    {{ selectedEventRow.name }} ({{ selectedEventRow.code }})
                  </el-descriptions-item>
                  <el-descriptions-item label="Task modes">
                    {{ selectedEventRow.taskModes.join(', ') || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Invocation model">
                    {{ invocationModelLabel(selectedEventRow.invocationModel) }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Payload types">
                    {{ selectedEventRow.payloadTypes.join(', ') || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Priority class">
                    {{ selectedEventRow.priorityClass }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Response mode">
                    {{ selectedEventRow.responseMode }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Target scope">
                    {{ selectedEventRow.targetScope }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Projects">
                    {{ selectedEventRow.projectCodes.join(', ') || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Reachable workers">
                    {{ selectedEventWorkerSummary }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Invocation coverage">
                    {{ selectedEventRow.hasInvocationCoverage ? 'Covered' : 'No active capability' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="Description">
                    {{ selectedEventRow.description || '-' }}
                  </el-descriptions-item>
                </el-descriptions>
                <div class="starter-preview">
                  <div class="starter-preview-title">Starter sharedConfig</div>
                  <pre class="json-block">{{
                    selectedEventStarterSharedConfigText
                  }}</pre>
                </div>
                <div class="starter-preview">
                  <div class="starter-preview-title">Starter inputs</div>
                  <pre class="json-block">{{ selectedEventStarterInputsText }}</pre>
                </div>
                <div class="detail-actions">
                  <el-button
                    type="primary"
                    :disabled="!supportsTaskDraft(selectedEventRow)"
                    @click="openTaskDraftForSelectedEvent()"
                  >
                    Start event draft
                  </el-button>
                  <el-button
                    :disabled="!draftProjectCodeForSelectedEvent"
                    @click="focusDraftProjectForSelectedEvent()"
                  >
                    Focus project
                  </el-button>
                </div>
              </template>
            </el-card>
          </el-col>
        </el-row>

        <el-card class="page-card">
          <template #header>
            <strong>Project catalog</strong>
          </template>
          <PageEmptyState
            v-if="projectRows.length === 0"
            description="No projects are currently available."
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
            <el-table-column label="Reachable workers" min-width="150">
              <template #default="{ row }">
                {{ row.reachableWorkerIds.length }}
              </template>
            </el-table-column>
            <el-table-column
              prop="description"
              label="Description"
              min-width="320"
            />
            <el-table-column label="Actions" min-width="220" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  @click="selectProject(row.code)"
                >
                  Inspect
                </el-button>
                <el-button
                  link
                  type="primary"
                  @click="openTaskDraftForProject(row.code)"
                >
                  Draft task
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card class="page-card">
          <template #header>
            <strong>Reachable workers</strong>
          </template>
          <PageEmptyState
            v-if="visibleWorkerRows.length === 0"
            description="No workers match the selected scope and availability filter."
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
            <el-table-column prop="runtimeStatus" label="Runtime status" min-width="120">
              <template #default="{ row }">
                <el-tag :type="tagForWorkerStatus(row.runtimeStatus)">
                  {{ row.runtimeStatus }}
                </el-tag>
                <div class="row-secondary">
                  {{ row.reachability || (row.reachable ? 'ONLINE' : 'OFFLINE') }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Project hints" min-width="220">
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
            <el-table-column label="Supported events" min-width="280">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag
                    v-for="eventCode in visibleItems(row.visibleSupportedEventCodes)"
                    :key="eventCode"
                    type="primary"
                    round
                  >
                    {{ eventCode }}
                  </el-tag>
                  <span
                    v-if="row.visibleSupportedEventCodes.length === 0"
                    class="row-secondary"
                  >
                    no supported events in scope
                  </span>
                  <span
                    v-if="remainingCount(row.visibleSupportedEventCodes) > 0"
                    class="row-secondary"
                  >
                    +{{ remainingCount(row.visibleSupportedEventCodes) }} more
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
            description="No events are available for the selected scope."
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
            <el-table-column label="Capability" min-width="170">
              <template #default="{ row }">
                <el-tag :type="row.hasInvocationCoverage ? 'success' : 'info'" round>
                  {{ invocationModelLabel(row.invocationModel) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Metadata" min-width="260">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag type="info" round>{{ row.priorityClass }}</el-tag>
                  <el-tag type="info" round>{{ row.responseMode }}</el-tag>
                  <el-tag type="info" round>{{ row.targetScope }}</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Projects" min-width="180">
              <template #default="{ row }">
                {{ row.projectCodes.join(', ') || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="Reachable workers" min-width="150">
              <template #default="{ row }">
                {{ row.reachableWorkerIds.length }}
              </template>
            </el-table-column>
            <el-table-column
              prop="description"
              label="Description"
              min-width="320"
            />
            <el-table-column label="Actions" min-width="240" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  @click="selectEvent(row.code)"
                >
                  Inspect
                </el-button>
                <el-button
                  link
                  type="primary"
                  :disabled="!supportsTaskDraft(row)"
                  @click="openTaskDraftForEvent(row)"
                >
                  Draft task
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
import {useRouter} from 'vue-router'
import {listEventCapabilities, listEventDefinitions} from '@/api/catalog'
import {listProjects} from '@/api/projects'
import {getCurrentApiKey} from '@/api/current-api-key'
import {listWorkers} from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {EventCapability, EventInvocationModel, EventDefinition} from '@/types/catalog'
import type {ProjectDefinition} from '@/types/projects'
import type {CurrentApiKeySnapshot} from '@/types/current-api-key'
import type {WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'
import {resolveTaskStarterDraft, stringifyStarterItems, stringifyStarterSharedConfig,} from '@/utils/task-starters'

const ALL_PROJECTS = 'ALL'
const TAG_LIMIT = 4

interface ProjectRow extends ProjectDefinition {
  resolvedEvents: EventDefinition[]
  reachableWorkerIds: string[]
}

interface WorkerDiscoveryRow extends WorkerListItem {
  matchedProjects: string[]
  visibleSupportedEventCodes: string[]
}

interface EventRow extends EventDefinition {
  invocationModel: EventInvocationModel
  projectCodes: string[]
  declaredWorkerIds: string[]
  reachableWorkerIds: string[]
  hasDirectRuntimeHandler: boolean
  hasReachableWorkerCoverage: boolean
  hasInvocationCoverage: boolean
}

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const projects = ref<ProjectDefinition[]>([])
const events = ref<EventDefinition[]>([])
const eventCapabilities = ref<EventCapability[]>([])
const workers = ref<WorkerListItem[]>([])
const apiKeySnapshot = ref<CurrentApiKeySnapshot>({
  state: 'unavailable',
  profile: null,
})
const selectedProjectCode = ref(ALL_PROJECTS)
const selectedEventCode = ref('')
const showReachableOnly = ref(true)

const sortedProjects = computed(() =>
  [...projects.value].sort((left, right) => left.code.localeCompare(right.code)),
)
const projectByCode = computed<Record<string, ProjectDefinition>>(() =>
  projects.value.reduce<Record<string, ProjectDefinition>>((acc, project) => {
    acc[project.code] = project
    return acc
  }, {}),
)
const eventByCode = computed<Record<string, EventDefinition>>(() =>
  events.value.reduce<Record<string, EventDefinition>>((acc, event) => {
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
const capabilityByEvent = computed<Record<string, EventCapability>>(() =>
  eventCapabilities.value.reduce<Record<string, EventCapability>>((acc, capability) => {
    acc[capability.eventCode] = capability
    return acc
  }, {}),
)
const selectedProject = computed(() => {
  if (selectedProjectCode.value === ALL_PROJECTS) {
    return null
  }

  return projectByCode.value[selectedProjectCode.value] ?? null
})
const selectedEventRow = computed<EventRow | null>(() => {
  if (!selectedEventCode.value) {
    return null
  }

  return (
    selectedEventRows.value.find((event) => event.code === selectedEventCode.value) ??
    null
  )
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
      .filter((event): event is EventDefinition => Boolean(event))
    const reachableWorkerIds = uniqueStrings(
      project.eventCodes.flatMap(
        (eventCode) => capabilityByEvent.value[eventCode]?.reachableWorkerIds ?? [],
      ),
    )

    return {
      ...project,
      resolvedEvents,
      reachableWorkerIds,
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
    const visibleSupportedEventCodes =
      selectedProjectCode.value === ALL_PROJECTS
        ? uniqueStrings(worker.supportedEventCodes)
        : uniqueStrings(
            worker.supportedEventCodes.filter((eventCode) =>
              (eventCodesByProject.value[selectedProjectCode.value] ?? []).includes(
                eventCode,
              ),
            ),
          )

    return {
      ...worker,
      matchedProjects,
      visibleSupportedEventCodes,
    }
  }),
)
const visibleWorkerRows = computed(() =>
  workerRows.value.filter((worker) => {
    const matchesProject =
      selectedProjectCode.value === ALL_PROJECTS ||
      worker.visibleSupportedEventCodes.length > 0 ||
      worker.matchedProjects.length > 0
    const matchesAvailability =
      !showReachableOnly.value || worker.reachable === true

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
      const capability = capabilityByEvent.value[event.code]
      const directRuntime = event.taskModes.length === 0
      const projectCodes =
        capability?.projectCodes ?? projectCodesByEvent.value[event.code] ?? []
      const reachableWorkerIds = capability?.reachableWorkerIds ?? workers.value
          .filter(
            (worker) =>
              worker.reachable === true &&
              worker.supportedEventCodes.includes(event.code),
          )
          .map((worker) => worker.workerId)

      return {
        ...event,
        invocationModel:
          capability?.invocationModel ?? (directRuntime ? 'DIRECT_RUNTIME' : 'TASK_BACKED'),
        projectCodes,
        declaredWorkerIds: capability?.declaredWorkerIds ?? reachableWorkerIds,
        reachableWorkerIds,
        hasDirectRuntimeHandler: capability?.hasDirectRuntimeHandler ?? directRuntime,
        hasReachableWorkerCoverage:
          capability?.hasReachableWorkerCoverage ?? reachableWorkerIds.length > 0,
        hasInvocationCoverage:
          capability?.hasInvocationCoverage ?? (directRuntime || reachableWorkerIds.length > 0),
      }
    }),
)
const enabledProjectCount = computed(
  () => projects.value.filter((project) => project.enabled).length,
)
const enabledEventCount = computed(
  () => events.value.filter((event) => event.enabled).length,
)
const reachableWorkerCount = computed(
  () => workers.value.filter((worker) => worker.reachable).length,
)
const coveredProjectCount = computed(
  () =>
    projectRows.value.filter(
      (project) => project.enabled && project.reachableWorkerIds.length > 0,
    ).length,
)
const apiKeyProfile = computed(() => apiKeySnapshot.value.profile)
const apiKeyStatusLabel = computed(() => {
  if (apiKeySnapshot.value.state === 'available') {
    return 'Credential resolved'
  }
  if (apiKeySnapshot.value.state === 'unauthorized') {
    return 'No API-key credential in this browser session'
  }
  return 'Endpoint unavailable or mock mode'
})
const apiKeyStatusType = computed(() => {
  if (apiKeySnapshot.value.state === 'available') {
    return 'success'
  }
  if (apiKeySnapshot.value.state === 'unauthorized') {
    return 'warning'
  }
  return 'info'
})
const apiKeyAttributesText = computed(() =>
  apiKeyProfile.value
    ? JSON.stringify(apiKeyProfile.value.attributes ?? {}, null, 2)
    : '{}',
)
const selectedScopeTitle = computed(() => {
  if (!selectedProject.value) {
    return 'All projects'
  }

  return `${selectedProject.value.name} (${selectedProject.value.code})`
})
const selectedEventTitle = computed(() => {
  if (!selectedEventRow.value) {
    return 'All events'
  }

  return `${selectedEventRow.value.name} (${selectedEventRow.value.code})`
})
const selectedReachableWorkerSummary = computed(() => {
  const ids = uniqueStrings(
    projectRows.value.flatMap((project) => project.reachableWorkerIds),
  )

  if (ids.length === 0) {
    return 'No reachable worker coverage'
  }

  return ids.join(', ')
})
const selectedProjectCoverageSummary = computed(() => {
  if (!selectedProject.value) {
    return '-'
  }

  const ids =
    projectRows.value.find((project) => project.code === selectedProject.value?.code)
      ?.reachableWorkerIds ?? []

  return ids.length > 0 ? ids.join(', ') : 'No reachable workers'
})
const selectedEventWorkerSummary = computed(() => {
  if (!selectedEventRow.value) {
    return '-'
  }

  if (selectedEventRow.value.invocationModel === 'DIRECT_RUNTIME') {
    return selectedEventRow.value.hasDirectRuntimeHandler
      ? 'Handled by SDK runtime'
      : 'No direct runtime handler'
  }

  return selectedEventRow.value.reachableWorkerIds.length > 0
    ? selectedEventRow.value.reachableWorkerIds.join(', ')
    : 'No reachable workers'
})
const selectedProjectStarter = computed(() => {
  if (!selectedProject.value) {
    return null
  }

  return resolveTaskStarterDraft({
    projectCode: selectedProject.value.code,
  })
})
const selectedEventStarter = computed(() => {
  if (!selectedEventRow.value || !supportsTaskDraft(selectedEventRow.value)) {
    return null
  }

  return resolveTaskStarterDraft({
    projectCode: draftProjectCodeForSelectedEvent.value,
    eventCode: selectedEventRow.value.code,
  })
})
const selectedProjectStarterSharedConfigText = computed(() =>
  selectedProjectStarter.value
    ? stringifyStarterSharedConfig(selectedProjectStarter.value.sharedConfig)
    : '{}',
)
const selectedProjectStarterInputsText = computed(() =>
  selectedProjectStarter.value
    ? stringifyStarterItems(selectedProjectStarter.value.items)
    : '',
)
const selectedEventStarterSharedConfigText = computed(() =>
  selectedEventStarter.value
    ? stringifyStarterSharedConfig(selectedEventStarter.value.sharedConfig)
    : '{}',
)
const selectedEventStarterInputsText = computed(() =>
  selectedEventStarter.value
    ? stringifyStarterItems(selectedEventStarter.value.items)
    : '',
)
const draftProjectCodeForSelectedEvent = computed(() => {
  if (!selectedEventRow.value || !supportsTaskDraft(selectedEventRow.value)) {
    return ''
  }

  if (
    selectedProject.value &&
    selectedEventRow.value.projectCodes.includes(selectedProject.value.code)
  ) {
    return selectedProject.value.code
  }

  return selectedEventRow.value.projectCodes[0] ?? ''
})

async function loadDiscovery(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [
      projectRowsData,
      eventRowsData,
      eventCapabilityRows,
      workerResponse,
      apiKeySnapshotData,
    ] =
      await Promise.all([
        listProjects(),
        listEventDefinitions(),
        listEventCapabilities(),
        listWorkers(),
        getCurrentApiKey(),
      ])
    projects.value = projectRowsData
    events.value = eventRowsData
    eventCapabilities.value = eventCapabilityRows
    workers.value = workerResponse.items
    apiKeySnapshot.value = apiKeySnapshotData
  } catch (error) {
    projects.value = []
    events.value = []
    eventCapabilities.value = []
    workers.value = []
    apiKeySnapshot.value = {
      state: 'unavailable',
      profile: null,
    }
    errorMessage.value = toErrorMessage(
      error,
      'Failed to load runtime discovery data.',
    )
  } finally {
    loading.value = false
  }
}

function openWorkerDetail(workerId: string): void {
  void router.push({ name: 'worker-detail', params: { workerId } })
}

function selectProject(projectCode: string): void {
  selectedProjectCode.value = projectCode
}

function selectEvent(eventCode: string): void {
  selectedEventCode.value = eventCode
}

function openTaskListForProject(projectCode?: string): void {
  const resolvedProjectCode = projectCode ?? selectedProject.value?.code ?? ''
  void router.push({
    name: 'tasks',
    query: resolvedProjectCode ? { project: resolvedProjectCode } : undefined,
  })
}

function openTaskDraftForProject(projectCode?: string): void {
  const resolvedProjectCode = projectCode ?? selectedProject.value?.code ?? ''
  if (!resolvedProjectCode) {
    return
  }

  const starter = resolveTaskStarterDraft({
    projectCode: resolvedProjectCode,
  })
  void router.push({
    name: 'tasks',
    query: {
      create: '1',
      project: starter.projectCode,
    },
  })
}

function openProjectDetail(projectCode?: string): void {
  const resolvedProjectCode = projectCode ?? selectedProject.value?.code ?? ''
  if (!resolvedProjectCode) {
    return
  }
  void router.push({
    name: 'project-detail',
    params: {
      projectCode: resolvedProjectCode,
    },
  })
}

function openTaskDraftForSelectedEvent(): void {
  if (!selectedEventRow.value || !supportsTaskDraft(selectedEventRow.value)) {
    return
  }

  openTaskDraftForEvent(selectedEventRow.value)
}

function openTaskDraftForEvent(event: EventRow): void {
  if (!supportsTaskDraft(event)) {
    return
  }
  const projectCode = resolveDraftProjectCodeForEvent(event)
  if (!projectCode) {
    return
  }
  const starter = resolveTaskStarterDraft({
    projectCode,
    eventCode: event.code,
  })

  void router.push({
    name: 'tasks',
    query: {
      create: '1',
      project: starter.projectCode,
      eventCode: starter.eventCode ?? event.code,
    },
  })
}

function focusDraftProjectForSelectedEvent(): void {
  if (!draftProjectCodeForSelectedEvent.value) {
    return
  }

  selectedProjectCode.value = draftProjectCodeForSelectedEvent.value
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

function resolveDraftProjectCodeForEvent(event: EventRow): string {
  if (!supportsTaskDraft(event)) {
    return ''
  }
  if (
    selectedProject.value &&
    event.projectCodes.includes(selectedProject.value.code)
  ) {
    return selectedProject.value.code
  }

  return event.projectCodes[0] ?? ''
}

function uniqueStrings(values: string[]): string[] {
  return Array.from(new Set(values.filter((value) => value.length > 0)))
}

function supportsTaskDraft(event: Pick<EventRow, 'invocationModel' | 'projectCodes'>): boolean {
  return event.invocationModel === 'TASK_BACKED' && event.projectCodes.length > 0
}

function invocationModelLabel(invocationModel: EventInvocationModel): string {
  return invocationModel === 'DIRECT_RUNTIME'
    ? 'Direct runtime event'
    : 'Task-backed event'
}

onMounted(() => {
  void loadDiscovery()
})

watch(
  () => selectedEventRows.value,
  (rows) => {
    if (rows.length === 0) {
      selectedEventCode.value = ''
      return
    }

    if (!rows.some((row) => row.code === selectedEventCode.value)) {
      selectedEventCode.value = rows[0].code
    }
  },
  { immediate: true },
)
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

.discovery-note {
  border-radius: 14px;
}

.quickstart-card {
  height: 100%;
}

.api-key-access-card {
  margin-top: 20px;
}

.api-key-access-note {
  margin-bottom: 16px;
  border-radius: 12px;
}

.detail-card {
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

.detail-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

.starter-preview {
  margin-top: 16px;
}

.starter-preview-title {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: #6b7a90;
}

.json-block {
  margin: 0;
  padding: 12px 14px;
  border-radius: 14px;
  background: #f5f8fc;
  border: 1px solid rgba(18, 32, 51, 0.08);
  font-family:
    'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo,
    monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #334155;
}

.inline-json-block {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
