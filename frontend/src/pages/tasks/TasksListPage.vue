<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Task List</h2>
        <p class="page-subtitle">
          Core orchestration list view. This page intentionally centers task
          state, routing, and progress instead of generic CRUD boilerplate.
        </p>
      </div>
      <el-button v-permission="'task:create'" type="primary"
        >Create task</el-button
      >
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadTasks"
    />

    <el-card v-else class="page-card">
      <div class="toolbar">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="Search by task name or id"
          @keyup.enter="loadTasks"
        />
        <el-select
          v-model="filters.status"
          clearable
          placeholder="Status"
          @change="loadTasks"
        >
          <el-option
            v-for="status in statuses"
            :key="status"
            :label="status"
            :value="status"
          />
        </el-select>
        <el-button @click="loadTasks">Search</el-button>
      </div>

      <PageSectionSkeleton v-if="loading" />

      <PageEmptyState
        v-else-if="rows.length === 0"
        description="No tasks match the current filters."
      />

      <el-table v-else :data="rows" row-key="id">
        <el-table-column prop="taskName" label="Task" min-width="260">
          <template #default="{ row }">
            <div class="row-primary">{{ row.taskName }}</div>
            <div class="row-secondary mono">{{ row.id }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="project" label="Project" min-width="130" />
        <el-table-column prop="routingCode" label="Routing" min-width="120" />
        <el-table-column prop="status" label="Status" min-width="120">
          <template #default="{ row }">
            <el-tag :type="tagForStatus(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Progress" min-width="170">
          <template #default="{ row }">
            {{ row.successCount }} / {{ row.eligibleCount }}
          </template>
        </el-table-column>
        <el-table-column prop="batchSize" label="Batch Size" min-width="110" />
        <el-table-column prop="updatedAt" label="Updated" min-width="180" />
        <el-table-column label="Actions" min-width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goToTask(row.id)"
              >View detail</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listTasks } from '@/api/tasks'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type { TaskListItem } from '@/types/tasks'
import { toErrorMessage } from '@/utils/errors'

const router = useRouter()

const loading = ref(false)
const rows = ref<TaskListItem[]>([])
const errorMessage = ref('')
const filters = reactive({
  keyword: '',
  status: '' as TaskListItem['status'] | '',
})

const statuses: TaskListItem['status'][] = [
  'NEW',
  'READY',
  'RUNNING',
  'PAUSED',
  'BLOCKED',
  'TERMINAL',
]

const tagType: Record<
  TaskListItem['status'],
  'info' | 'success' | 'warning' | 'primary' | 'danger'
> = {
  NEW: 'info',
  READY: 'success',
  RUNNING: 'primary',
  PAUSED: 'warning',
  BLOCKED: 'danger',
  TERMINAL: 'info',
}

function tagForStatus(
  status: TaskListItem['status'],
): 'info' | 'success' | 'warning' | 'primary' | 'danger' {
  return tagType[status]
}

async function loadTasks(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const response = await listTasks(filters)
    rows.value = response.items
  } catch (error) {
    rows.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load task list.')
  } finally {
    loading.value = false
  }
}

function goToTask(taskId: string): void {
  void router.push({ name: 'task-detail', params: { taskId } })
}

onMounted(() => {
  void loadTasks()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}

.toolbar :deep(.el-input) {
  width: 260px;
}

.toolbar :deep(.el-select) {
  width: 160px;
}

.row-primary {
  font-weight: 700;
  color: #122033;
}

.row-secondary {
  margin-top: 4px;
  color: #6b7a90;
  font-size: 12px;
}
</style>
