<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Worker Detail</h2>
        <p class="page-subtitle">
          Dedicated worker debug view for targeted task submission, transport
          inspection, and runtime visibility without crowding the main worker
          list.
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
          <div class="metric-label">Runtime status</div>
          <div class="metric-value metric-text">{{ worker.runtimeStatus }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Projects</div>
          <div class="metric-value">{{ worker.supportedProjects.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Events</div>
          <div class="metric-value">{{ worker.supportedEventCodes.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Transport</div>
          <div class="metric-value metric-text">
            {{ worker.transportHint || '-' }}
          </div>
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
              <el-descriptions-item label="Runtime status">
                {{ worker.runtimeStatus }}
              </el-descriptions-item>
              <el-descriptions-item label="Reachability">
                {{ worker.reachability || (worker.reachable ? 'ONLINE' : 'OFFLINE') }}
              </el-descriptions-item>
              <el-descriptions-item label="Group">
                {{ worker.workerGroupId || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="Agent version">
                {{ worker.agentVersion || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="Transport hint">
                {{ worker.transportHint || '-' }}
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
          <strong>Targeted debug task</strong>
        </template>
        <WorkerDebugPanel :worker="worker" :project-options="projectOptions" />
      </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import {onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {listProjectCodes} from '@/api/configs'
import {listWorkers} from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import WorkerDebugPanel from '@/components/WorkerDebugPanel.vue'
import type {WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const worker = ref<WorkerListItem | null>(null)
const projectOptions = ref<string[]>([])

async function loadWorkerDetail(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [workersResponse, projects] = await Promise.all([
      listWorkers(),
      loadProjectOptionsSafe(),
    ])
    worker.value =
      workersResponse.items.find(
        (item) => item.workerId === String(route.params.workerId),
      ) ?? null
    projectOptions.value = projects
  } catch (error) {
    worker.value = null
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
