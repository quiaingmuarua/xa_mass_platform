<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Roles</h2>
        <p class="page-subtitle">
          Stable permission bundles exposed by the server IAM catalog. Role
          mutation remains backend-controlled in this slice.
        </p>
      </div>
      <el-button @click="loadRoles">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadRoles"
    />

    <template v-else>
      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Roles</div>
          <div class="metric-value">{{ roles.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Permissions</div>
          <div class="metric-value">{{ permissions.length }}</div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <template v-else>
        <el-card class="page-card">
          <template #header>
            <strong>Role catalog</strong>
          </template>
          <PageEmptyState
            v-if="roles.length === 0"
            description="No roles are currently available."
          />
          <el-table v-else :data="roles" row-key="roleId">
            <el-table-column prop="roleId" label="Role" min-width="220">
              <template #default="{ row }">
                <div class="row-primary">{{ row.name }}</div>
                <div class="row-secondary mono">{{ row.roleId }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="Description" min-width="320" />
            <el-table-column prop="systemRole" label="System" min-width="120">
              <template #default="{ row }">
                <el-tag :type="row.systemRole ? 'success' : 'info'">
                  {{ row.systemRole ? 'SYSTEM' : 'CUSTOM' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Permissions" min-width="360">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag
                    v-for="permission in row.permissions"
                    :key="permission"
                    round
                  >
                    {{ permission }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card class="page-card">
          <template #header>
            <strong>Permission catalog</strong>
          </template>
          <div class="tag-row">
            <el-tag
              v-for="permission in permissions"
              :key="permission"
              type="info"
              round
            >
              {{ permission }}
            </el-tag>
          </div>
        </el-card>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {listPermissions, listRoles, type RoleRecord} from '@/api/roles'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import {toErrorMessage} from '@/utils/errors'

const loading = ref(false)
const errorMessage = ref('')
const roles = ref<RoleRecord[]>([])
const permissions = ref<string[]>([])

async function loadRoles(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [roleRows, permissionRows] = await Promise.all([
      listRoles(),
      listPermissions(),
    ])
    roles.value = roleRows
    permissions.value = permissionRows
  } catch (error) {
    roles.value = []
    permissions.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load roles.')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadRoles()
})
</script>

<style scoped>
.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
