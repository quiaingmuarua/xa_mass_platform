<template>
  <ConsolePage
    tone="operator"
    width="wide"
    eyebrow="Operator cockpit"
    title="Control Overview"
    subtitle="Lightweight control-plane landing page for orchestration health, operational posture, and current runtime visibility."
  >
    <template #badge>
      <el-tag effect="plain" type="primary">{{ integrationMode }}</el-tag>
    </template>
    <template #actions>
      <el-button @click="loadOverview">Refresh</el-button>
    </template>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadOverview"
    />

    <template v-else>
      <MetricGrid class="overview-metrics" :columns="4">
        <MetricCard label="Tasks" :value="taskCount" tone="primary" />
        <MetricCard label="Workers" :value="workerCount" />
        <MetricCard label="Capabilities" :value="capabilityCount" />
        <MetricCard label="Rules" :value="ruleCount" />
      </MetricGrid>

      <MetricGrid :columns="4">
        <MetricCard label="Running tasks" :value="runningTaskCount" />
        <MetricCard label="Reachable workers" :value="reachableWorkerCount" tone="success" />
        <MetricCard label="Locked workers" :value="lockedWorkerCount" tone="warning" />
        <MetricCard label="Integration mode" :value="integrationMode" compact />
      </MetricGrid>

      <PageSectionSkeleton v-if="loading" />

      <el-row v-else :gutter="20">
        <el-col :span="12">
          <el-card class="page-card detail-card context-card">
            <template #header>
              <div class="card-header">
                <strong>Current permission context</strong>
                <span>{{ authMode }}</span>
              </div>
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

      <el-card v-if="!loading" class="page-card recent-card">
        <template #header>
          <div class="card-header">
            <strong>Recent tasks</strong>
            <span>Latest 5 visible records</span>
          </div>
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
              <StatusBadge
                :status="row.status"
                :type="tagForTaskStatus(row.status)"
              />
            </template>
          </el-table-column>
          <el-table-column label="Progress" min-width="160">
            <template #default="{ row }">
              {{ row.successCount }} / {{ row.eligibleCount }}
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="Updated" min-width="180" />
        </el-table>
      </el-card>
    </template>
  </ConsolePage>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {listRules} from '@/api/rules'
import {listTasks} from '@/api/tasks'
import {listWorkers} from '@/api/workers'
import {getAppConfig} from '@/app/config'
import {useAuth} from '@/auth/use-auth'
import ConsolePage from '@/console-kit/layout/ConsolePage.vue'
import MetricCard from '@/console-kit/data/MetricCard.vue'
import MetricGrid from '@/console-kit/data/MetricGrid.vue'
import StatusBadge from '@/console-kit/data/StatusBadge.vue'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {RuleListItem} from '@/types/rules'
import type {TaskListItem} from '@/types/tasks'
import type {WorkerListItem} from '@/types/workers'
import {toErrorMessage} from '@/utils/errors'

const { user } = useAuth()

const loading = ref(false)
const errorMessage = ref('')
const tasks = ref<TaskListItem[]>([])
const workers = ref<WorkerListItem[]>([])
const rules = ref<RuleListItem[]>([])

const taskCount = computed(() => tasks.value.length)
const workerCount = computed(() => workers.value.length)
const ruleCount = computed(() => rules.value.length)
const capabilityCount = computed(
  () =>
    new Set(
      workers.value.flatMap((worker) =>
        worker.eventBindings?.length
          ? worker.eventBindings.map((binding) => binding.eventCode)
          : worker.supportedEventCodes,
      ),
    ).size,
)
const runningTaskCount = computed(
  () => tasks.value.filter((task) => task.status === 'RUNNING').length,
)
const reachableWorkerCount = computed(
  () => workers.value.filter((worker) => worker.reachable).length,
)
const lockedWorkerCount = computed(
  () => workers.value.filter((worker) => worker.locked).length,
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
    const [taskResponse, workerResponse, ruleResponse] =
      await Promise.all([
        listTasks(),
        listWorkers(),
        listRules(),
      ])
    tasks.value = taskResponse.items
    workers.value = workerResponse.items
    rules.value = ruleResponse.items
  } catch (error) {
    tasks.value = []
    workers.value = []
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
.detail-card {
  height: 100%;
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.card-header span {
  color: #667085;
  font-size: 13px;
}

.recent-card {
  overflow: hidden;
}

</style>
