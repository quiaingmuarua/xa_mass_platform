<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Control Overview</h2>
        <p class="page-subtitle">
          Lightweight control-plane landing page for orchestration health,
          operational posture, and current runtime visibility.
        </p>
      </div>
      <el-button @click="loadOverview">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadOverview"
    />

    <template v-else>
      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Tasks</div>
          <div class="metric-value">{{ taskCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Workers</div>
          <div class="metric-value">{{ workerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Worker contexts</div>
          <div class="metric-value">{{ contextCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Rules</div>
          <div class="metric-value">{{ ruleCount }}</div>
        </div>
      </section>

      <section class="metric-grid secondary-metrics">
        <div class="metric-tile">
          <div class="metric-label">Running tasks</div>
          <div class="metric-value">{{ runningTaskCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Online workers</div>
          <div class="metric-value">{{ onlineWorkerCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Contexts in use</div>
          <div class="metric-value">{{ inUseContextCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Integration mode</div>
          <div class="metric-value">{{ integrationMode }}</div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <el-row v-else :gutter="20">
        <el-col :span="12">
          <el-card class="page-card detail-card">
            <template #header>
              <strong>Current permission context</strong>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="User">
                {{ user?.name ?? 'Guest' }}
              </el-descriptions-item>
              <el-descriptions-item label="Roles">
                {{ user?.roles.join(', ') ?? '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="Permissions">
                {{ user?.permissions.length ?? 0 }}
              </el-descriptions-item>
              <el-descriptions-item label="Auth mode">
                {{ authMode }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card class="page-card detail-card">
            <template #header>
              <strong>Task status mix</strong>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="READY">
                {{ taskStatusCounts.READY ?? 0 }}
              </el-descriptions-item>
              <el-descriptions-item label="RUNNING">
                {{ taskStatusCounts.RUNNING ?? 0 }}
              </el-descriptions-item>
              <el-descriptions-item label="PAUSED">
                {{ taskStatusCounts.PAUSED ?? 0 }}
              </el-descriptions-item>
              <el-descriptions-item label="TERMINAL">
                {{ taskStatusCounts.TERMINAL ?? 0 }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
      </el-row>

      <el-card v-if="!loading" class="page-card">
        <template #header>
          <strong>Recent tasks</strong>
        </template>
        <PageEmptyState
          v-if="tasks.length === 0"
          description="No task records are currently available."
        />
        <el-table v-else :data="tasks.slice(0, 5)" row-key="id">
          <el-table-column prop="taskName" label="Task" min-width="240">
            <template #default="{ row }">
              <div class="row-primary">{{ row.taskName }}</div>
              <div class="row-secondary mono">{{ row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="Status" min-width="120">
            <template #default="{ row }">
              <el-tag :type="tagForTaskStatus(row.status)">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="routingCode" label="Routing" min-width="120" />
          <el-table-column label="Progress" min-width="160">
            <template #default="{ row }">
              {{ row.successCount }} / {{ row.eligibleCount }}
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="Updated" min-width="180" />
        </el-table>
      </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listRules } from '@/api/rules'
import { listTasks } from '@/api/tasks'
import { listWorkerContexts, listWorkers } from '@/api/workers'
import { getAppConfig } from '@/app/config'
import { useAuth } from '@/auth/use-auth'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type { RuleListItem } from '@/types/rules'
import type { TaskListItem } from '@/types/tasks'
import type {
  WorkerContextListItem,
  WorkerListItem,
} from '@/types/workers'
import { toErrorMessage } from '@/utils/errors'

const { user } = useAuth()

const loading = ref(false)
const errorMessage = ref('')
const tasks = ref<TaskListItem[]>([])
const workers = ref<WorkerListItem[]>([])
const workerContexts = ref<WorkerContextListItem[]>([])
const rules = ref<RuleListItem[]>([])

const taskCount = computed(() => tasks.value.length)
const workerCount = computed(() => workers.value.length)
const contextCount = computed(() => workerContexts.value.length)
const ruleCount = computed(() => rules.value.length)
const runningTaskCount = computed(
  () => tasks.value.filter((task) => task.status === 'RUNNING').length,
)
const onlineWorkerCount = computed(
  () => workers.value.filter((worker) => worker.status === 'ONLINE').length,
)
const inUseContextCount = computed(
  () =>
    workerContexts.value.filter((context) =>
      ['RESERVED', 'OCCUPIED'].includes(context.status),
    ).length,
)
const taskStatusCounts = computed<Record<string, number>>(() => {
  return tasks.value.reduce<Record<string, number>>((acc, task) => {
    acc[task.status] = (acc[task.status] ?? 0) + 1
    return acc
  }, {})
})
const integrationMode = computed(() =>
  getAppConfig().useMockApi ? 'Mock' : 'Backend',
)
const authMode = computed(() =>
  getAppConfig().useMockAuth ? 'Mock auth' : 'Backend /me',
)

async function loadOverview(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [taskResponse, workerResponse, contextResponse, ruleResponse] =
      await Promise.all([
        listTasks(),
        listWorkers(),
        listWorkerContexts(),
        listRules(),
      ])
    tasks.value = taskResponse.items
    workers.value = workerResponse.items
    workerContexts.value = contextResponse.items
    rules.value = ruleResponse.items
  } catch (error) {
    tasks.value = []
    workers.value = []
    workerContexts.value = []
    rules.value = []
    errorMessage.value = toErrorMessage(
      error,
      'Failed to load overview data.',
    )
  } finally {
    loading.value = false
  }
}

function tagForTaskStatus(
  status: TaskListItem['status'],
): 'info' | 'success' | 'warning' | 'primary' | 'danger' {
  if (status === 'READY') {
    return 'success'
  }
  if (status === 'RUNNING') {
    return 'primary'
  }
  if (status === 'PAUSED') {
    return 'warning'
  }
  if (status === 'BLOCKED') {
    return 'danger'
  }
  return 'info'
}

onMounted(() => {
  void loadOverview()
})
</script>

<style scoped>
.secondary-metrics {
  margin-top: 18px;
}

.detail-card {
  height: 100%;
}
</style>
