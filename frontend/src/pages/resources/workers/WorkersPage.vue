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
          <div class="metric-label">Reachable</div>
          <div class="metric-value">{{ reachableWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Locked</div>
          <div class="metric-value">{{ lockedWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Event bindings</div>
          <div class="metric-value">{{ eventBindingCount }}</div>
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
        <el-table-column prop="runtimeStatus" label="Runtime status" min-width="120">
          <template #default="{ row }">
            <el-tag :type="tagForWorkerStatus(row.runtimeStatus)">
              {{ row.runtimeStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Reachability" min-width="150">
          <template #default="{ row }">
            <el-tag :type="row.reachable ? 'success' : 'info'">
              {{ row.reachability || (row.reachable ? 'ONLINE' : 'OFFLINE') }}
            </el-tag>
            <div class="row-secondary">{{ row.transportHint || '-' }}</div>
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
        <el-table-column label="Lock" min-width="110">
          <template #default="{ row }">
            <el-tag :type="row.locked ? 'warning' : 'info'">
              {{ row.locked ? 'LOCKED' : 'FREE' }}
            </el-tag>
          </template>
        </el-table-column>
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
import {listWorkers} from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'

const router = useRouter()

const loading = ref(false)
const workers = ref<WorkerListItem[]>([])
const errorMessage = ref('')
const reachableWorkerCount = computed(
  () => workers.value.filter((worker) => worker.reachable).length,
)
const lockedWorkerCount = computed(
  () => workers.value.filter((worker) => worker.locked).length,
)
const eventBindingCount = computed(
  () =>
    workers.value.reduce(
      (count, worker) =>
        count +
        (worker.eventBindings?.length ?? worker.supportedEventCodes.length),
      0,
    ),
)

async function loadWorkers(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const workerResponse = await listWorkers()
    workers.value = workerResponse.items
  } catch (error) {
    workers.value = []
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
