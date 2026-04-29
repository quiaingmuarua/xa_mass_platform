<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Workers</h2>
        <p class="page-subtitle">
          Worker inventory for the orchestration runtime. This page reads the
          backend worker truth, keeps capability inspection centered on events,
          and links to a dedicated worker debug view.
        </p>
      </div>
      <el-button @click="loadWorkers">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadWorkers"
    />

    <el-card v-else class="page-card">
      <section class="metric-grid worker-metrics">
        <div class="metric-tile">
          <div class="metric-label">Workers</div>
          <div class="metric-value">{{ workers.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Online</div>
          <div class="metric-value">{{ onlineWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Locked</div>
          <div class="metric-value">{{ lockedWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Contexts</div>
          <div class="metric-value">{{ workerContexts.length }}</div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <PageEmptyState
        v-else-if="workers.length === 0"
        description="No workers are currently registered in the backend runtime."
      />

      <el-table v-else :data="workers" row-key="workerId">
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
        <el-table-column prop="agentVersion" label="Agent" min-width="120" />
        <el-table-column label="Projects" min-width="220">
          <template #default="{ row }">
            <el-tag
              v-for="project in row.supportedProjects"
              :key="project"
              class="project-tag"
              round
            >
              {{ project }}
            </el-tag>
            <span
              v-if="row.supportedProjects.length === 0"
              class="row-secondary"
            >
              none
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Events" min-width="240">
          <template #default="{ row }">
            <el-tag
              v-for="eventCode in row.supportedEventCodes"
              :key="eventCode"
              class="project-tag"
              type="primary"
              round
            >
              {{ eventCode }}
            </el-tag>
            <span
              v-if="row.supportedEventCodes.length === 0"
              class="row-secondary"
            >
              none
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Contexts" min-width="120">
          <template #default="{ row }">
            {{ contextCountByWorkerId[row.workerId] ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column label="Lock" min-width="110">
          <template #default="{ row }">
            <el-tag :type="row.locked ? 'warning' : 'info'">
              {{ row.locked ? 'LOCKED' : 'FREE' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="lastHeartbeat"
          label="Last heartbeat"
          min-width="180"
        />
        <el-table-column label="Attributes" min-width="220">
          <template #default="{ row }">
            <pre class="json-inline">{{ formatJson(row.attributes) }}</pre>
          </template>
        </el-table-column>
        <el-table-column label="Actions" min-width="260" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openWorkerDetail(row.workerId)"
            >
              Open debug view
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {listWorkerContexts, listWorkers} from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {WorkerContextListItem, WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'

const router = useRouter()

const loading = ref(false)
const workers = ref<WorkerListItem[]>([])
const workerContexts = ref<WorkerContextListItem[]>([])
const errorMessage = ref('')
const onlineWorkerCount = computed(
  () => workers.value.filter((worker) => worker.status === 'ONLINE').length,
)
const lockedWorkerCount = computed(
  () => workers.value.filter((worker) => worker.locked).length,
)
const contextCountByWorkerId = computed<Record<string, number>>(() => {
  return workerContexts.value.reduce<Record<string, number>>((acc, context) => {
    acc[context.workerId] = (acc[context.workerId] ?? 0) + 1
    return acc
  }, {})
})

async function loadWorkers(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [workerResponse, contextResponse] = await Promise.all([
      listWorkers(),
      listWorkerContexts(),
    ])
    workers.value = workerResponse.items
    workerContexts.value = contextResponse.items
  } catch (error) {
    workers.value = []
    workerContexts.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load workers.')
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

function formatJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

onMounted(() => {
  void loadWorkers()
})
</script>

<style scoped>
.worker-metrics {
  margin-bottom: 20px;
}

.project-tag {
  margin: 0 6px 6px 0;
}
</style>
