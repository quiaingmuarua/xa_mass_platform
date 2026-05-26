<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Users</h2>
        <p class="page-subtitle">
          Server-owned operator identities. This page is read-only in the
          current IAM slice; role mutation stays out of this frontend pass.
        </p>
      </div>
      <el-button @click="loadUsers">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadUsers"
    />

    <template v-else>
      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Users</div>
          <div class="metric-value">{{ users.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Active</div>
          <div class="metric-value">{{ activeUserCount }}</div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <el-card v-else class="page-card">
        <template #header>
          <strong>User directory</strong>
        </template>
        <PageEmptyState
          v-if="users.length === 0"
          description="No users are currently available."
        />
        <el-table v-else :data="users" row-key="userId">
          <el-table-column prop="userId" label="User" min-width="220">
            <template #default="{ row }">
              <div class="row-primary">{{ row.displayName || row.userId }}</div>
              <div class="row-secondary mono">{{ row.userId }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="email" label="Email" min-width="260" />
          <el-table-column prop="status" label="Status" min-width="130">
            <template #default="{ row }">
              <el-tag :type="userStatusTag(row.status)">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="Created" min-width="220" />
          <el-table-column prop="updatedAt" label="Updated" min-width="220" />
        </el-table>
      </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {listUsers, type UserRecord} from '@/api/users'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import {toErrorMessage} from '@/utils/errors'

const loading = ref(false)
const errorMessage = ref('')
const users = ref<UserRecord[]>([])

const activeUserCount = computed(
  () => users.value.filter((user) => user.status === 'ACTIVE').length,
)

async function loadUsers(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    users.value = await listUsers()
  } catch (error) {
    users.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load users.')
  } finally {
    loading.value = false
  }
}

function userStatusTag(status: UserRecord['status']): 'success' | 'info' | 'danger' {
  if (status === 'ACTIVE') {
    return 'success'
  }
  if (status === 'DISABLED') {
    return 'info'
  }
  return 'danger'
}

onMounted(() => {
  void loadUsers()
})
</script>
