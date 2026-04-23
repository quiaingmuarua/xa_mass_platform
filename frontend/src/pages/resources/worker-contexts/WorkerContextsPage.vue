<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Worker Contexts</h2>
        <p class="page-subtitle">
          Runtime context pool visibility. This page keeps the context lifecycle
          separate from worker online status so allocation truth stays explicit.
        </p>
      </div>
      <el-button @click="loadContexts">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadContexts"
    />

    <el-card v-else class="page-card">
      <section class="metric-grid context-metrics">
        <div class="metric-tile">
          <div class="metric-label">Contexts</div>
          <div class="metric-value">{{ contexts.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Available</div>
          <div class="metric-value">{{ availableContextCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">In use</div>
          <div class="metric-value">{{ inUseContextCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Workers represented</div>
          <div class="metric-value">{{ representedWorkerCount }}</div>
        </div>
      </section>

      <div class="toolbar">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="Search context, worker, project, tag, task"
        />
        <el-select v-model="filters.status" clearable placeholder="Status">
          <el-option
            v-for="status in contextStatuses"
            :key="status"
            :label="status"
            :value="status"
          />
        </el-select>
      </div>

      <PageSectionSkeleton v-if="loading" />

      <PageEmptyState
        v-else-if="filteredContexts.length === 0"
        description="No worker contexts match the current filters."
      />

      <el-table v-else :data="filteredContexts" row-key="workerContextId">
        <el-table-column prop="workerContextId" label="Context" min-width="220">
          <template #default="{ row }">
            <div class="row-primary mono">{{ row.workerContextId }}</div>
            <div class="row-secondary">
              {{ row.project || 'no project' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="workerId" label="Worker" min-width="220">
          <template #default="{ row }">
            <div class="row-primary mono">{{ row.workerId }}</div>
            <div class="row-secondary">
              {{ workerStatusById[row.workerId] ?? 'worker unknown' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="Status" min-width="130">
          <template #default="{ row }">
            <el-tag :type="tagForContextStatus(row.status)">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Routing tags" min-width="220">
          <template #default="{ row }">
            <el-tag
              v-for="tag in row.routingTags"
              :key="tag"
              class="routing-tag"
              round
            >
              {{ tag }}
            </el-tag>
            <span v-if="row.routingTags.length === 0" class="row-secondary">
              none
            </span>
          </template>
        </el-table-column>
        <el-table-column
          prop="lastBindTaskId"
          label="Last bound task"
          min-width="220"
        >
          <template #default="{ row }">
            <span v-if="row.lastBindTaskId" class="mono">
              {{ row.lastBindTaskId }}
            </span>
            <span v-else class="row-secondary">none</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastUsedTime" label="Last used" min-width="180">
          <template #default="{ row }">
            {{ row.lastUsedTime || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="Updated" min-width="180">
          <template #default="{ row }">
            {{ row.updateTime || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="Attributes" min-width="260">
          <template #default="{ row }">
            <pre class="json-inline">{{ formatJson(row.attributes) }}</pre>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { listWorkerContexts, listWorkers } from '@/api/workers'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {
  WorkerContextListItem,
  WorkerListItem,
} from '@/types/workers'
import { toErrorMessage } from '@/utils/errors'

const loading = ref(false)
const errorMessage = ref('')
const contexts = ref<WorkerContextListItem[]>([])
const workers = ref<WorkerListItem[]>([])
const filters = reactive({
  keyword: '',
  status: '',
})

const contextStatuses = computed(() =>
  Array.from(new Set(contexts.value.map((context) => context.status))).sort(),
)
const workerStatusById = computed<Record<string, string>>(() => {
  return workers.value.reduce<Record<string, string>>((acc, worker) => {
    acc[worker.workerId] = worker.status
    return acc
  }, {})
})
const availableContextCount = computed(
  () =>
    contexts.value.filter((context) =>
      ['IDLE', 'AVAILABLE'].includes(context.status),
    ).length,
)
const inUseContextCount = computed(
  () =>
    contexts.value.filter((context) =>
      ['RESERVED', 'OCCUPIED'].includes(context.status),
    ).length,
)
const representedWorkerCount = computed(
  () => new Set(contexts.value.map((context) => context.workerId)).size,
)
const filteredContexts = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase()

  return contexts.value.filter((context) => {
    const matchesStatus =
      !filters.status || context.status === filters.status
    const matchesKeyword =
      keyword.length === 0 ||
      [
        context.workerContextId,
        context.workerId,
        context.project,
        ...context.routingTags,
        context.lastBindTaskId,
      ].some((value) => value?.toLowerCase().includes(keyword))

    return matchesStatus && matchesKeyword
  })
})

async function loadContexts(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [contextResponse, workerResponse] = await Promise.all([
      listWorkerContexts(),
      listWorkers(),
    ])
    contexts.value = contextResponse.items
    workers.value = workerResponse.items
  } catch (error) {
    contexts.value = []
    workers.value = []
    errorMessage.value = toErrorMessage(
      error,
      'Failed to load worker contexts.',
    )
  } finally {
    loading.value = false
  }
}

function tagForContextStatus(
  status: string,
): 'success' | 'warning' | 'danger' | 'info' {
  if (['IDLE', 'AVAILABLE'].includes(status)) {
    return 'success'
  }
  if (['RESERVED', 'OCCUPIED'].includes(status)) {
    return 'warning'
  }
  if (['BLOCKED', 'INVALID'].includes(status)) {
    return 'danger'
  }
  return 'info'
}

function formatJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

onMounted(() => {
  void loadContexts()
})
</script>

<style scoped>
.context-metrics {
  margin-bottom: 20px;
}

.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}

.toolbar :deep(.el-input) {
  width: 320px;
}

.toolbar :deep(.el-select) {
  width: 180px;
}

.routing-tag {
  margin: 0 6px 6px 0;
}
</style>
