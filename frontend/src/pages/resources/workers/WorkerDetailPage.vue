<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Worker Detail</h2>
        <p class="page-subtitle">
          Dedicated worker debug view for manual messaging, transport checks,
          and runtime inspection without crowding the main worker list.
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="goBack">Back to workers</el-button>
        <el-button @click="loadWorkerDetail">Refresh</el-button>
      </div>
    </header>

    <PageSectionSkeleton v-if="loading" />

    <PageErrorState
      v-else-if="errorMessage"
      :message="errorMessage"
      @retry="loadWorkerDetail"
    />

    <PageEmptyState
      v-else-if="!worker"
      description="The requested worker is not available in the current runtime."
    />

    <template v-else>
      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Worker</div>
          <div class="metric-value metric-text mono">{{ worker.workerId }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Status</div>
          <div class="metric-value metric-text">{{ worker.status }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Contexts</div>
          <div class="metric-value">{{ workerContextCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Projects</div>
          <div class="metric-value">{{ worker.supportedProjects.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Events</div>
          <div class="metric-value">{{ worker.supportedEventCodes.length }}</div>
        </div>
      </section>

      <el-row :gutter="20">
        <el-col :span="12">
          <el-card class="page-card detail-card">
            <template #header>
              <strong>Worker summary</strong>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="Worker ID">
                <span class="mono">{{ worker.workerId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="Status">
                {{ worker.status }}
              </el-descriptions-item>
              <el-descriptions-item label="Group">
                {{ worker.workerGroupId || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="Agent version">
                {{ worker.agentVersion || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="Last heartbeat">
                {{ worker.lastHeartbeat || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="Lock state">
                {{ worker.locked ? 'LOCKED' : 'FREE' }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card class="page-card detail-card">
            <template #header>
              <strong>Attributes</strong>
            </template>
            <pre class="json-block">{{ formatJson(worker.attributes) }}</pre>
          </el-card>
        </el-col>
      </el-row>

      <el-card class="page-card">
        <template #header>
          <strong>Supported projects</strong>
        </template>
        <div class="project-row">
          <el-tag
            v-for="project in worker.supportedProjects"
            :key="project"
            round
            class="project-tag"
          >
            {{ project }}
          </el-tag>
          <span
            v-if="worker.supportedProjects.length === 0"
            class="row-secondary"
          >
            none
          </span>
        </div>
      </el-card>

      <el-card class="page-card">
        <template #header>
          <strong>Supported events</strong>
        </template>
        <div class="project-row">
          <el-tag
            v-for="eventCode in worker.supportedEventCodes"
            :key="eventCode"
            round
            class="project-tag"
            type="primary"
          >
            {{ eventCode }}
          </el-tag>
          <span
            v-if="worker.supportedEventCodes.length === 0"
            class="row-secondary"
          >
            none
          </span>
        </div>
      </el-card>

      <el-card class="page-card">
        <template #header>
          <strong>Worker contexts</strong>
        </template>
        <PageEmptyState
          v-if="relatedWorkerContexts.length === 0"
          description="No worker contexts are currently associated with this worker."
        />
        <el-table
          v-else
          :data="relatedWorkerContexts"
          row-key="workerContextId"
        >
          <el-table-column
            prop="workerContextId"
            label="Context"
            min-width="200"
          />
          <el-table-column prop="status" label="Status" min-width="120" />
          <el-table-column prop="project" label="Project" min-width="140" />
          <el-table-column label="Routing tags" min-width="220">
            <template #default="{ row }">
              <el-tag
                v-for="tag in row.routingTags"
                :key="tag"
                class="project-tag"
                round
              >
                {{ tag }}
              </el-tag>
              <span v-if="row.routingTags.length === 0" class="row-secondary">
                none
              </span>
            </template>
          </el-table-column>
          <el-table-column label="Last bind task" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.lastBindTaskId || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="lastUsedTime"
            label="Last used"
            min-width="180"
          />
        </el-table>
      </el-card>

      <el-card class="page-card">
        <template #header>
          <strong>Manual debug messaging</strong>
        </template>
        <WorkerDebugPanel :worker="worker" :project-options="projectOptions" />
      </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {listProjectCodes} from '@/api/configs'
import {listWorkerContexts, listWorkers} from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import WorkerDebugPanel from '@/components/WorkerDebugPanel.vue'
import type {WorkerContextListItem, WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const worker = ref<WorkerListItem | null>(null)
const workerContexts = ref<WorkerContextListItem[]>([])
const projectOptions = ref<string[]>([])

const relatedWorkerContexts = computed(() =>
  workerContexts.value.filter(
    (context) => context.workerId === route.params.workerId,
  ),
)
const workerContextCount = computed(() => relatedWorkerContexts.value.length)

async function loadWorkerDetail(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [workersResponse, contextsResponse, projects] = await Promise.all([
      listWorkers(),
      listWorkerContexts(),
      loadProjectOptionsSafe(),
    ])
    worker.value =
      workersResponse.items.find(
        (item) => item.workerId === String(route.params.workerId),
      ) ?? null
    workerContexts.value = contextsResponse.items
    projectOptions.value = projects
  } catch (error) {
    worker.value = null
    workerContexts.value = []
    projectOptions.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load worker detail.')
  } finally {
    loading.value = false
  }
}

async function loadProjectOptionsSafe(): Promise<string[]> {
  try {
    return await listProjectCodes()
  } catch {
    return []
  }
}

function goBack(): void {
  void router.push({ name: 'workers' })
}

function formatJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

onMounted(() => {
  void loadWorkerDetail()
})

watch(
  () => route.params.workerId,
  () => {
    void loadWorkerDetail()
  },
)
</script>

<style scoped>
.header-actions {
  display: flex;
  gap: 12px;
}

.detail-card {
  height: 100%;
}

.metric-text {
  font-size: 20px;
}

.project-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.project-tag {
  margin: 0;
}

.json-block {
  margin: 0;
  font-family:
    'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo,
    monospace;
  white-space: pre-wrap;
  word-break: break-word;
  color: #445168;
}
</style>
