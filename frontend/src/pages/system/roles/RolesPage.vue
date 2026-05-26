<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Roles</h2>
        <p class="page-subtitle">
          Stable permission bundles exposed by the server IAM catalog. Custom
          roles can be created and edited; system roles remain read-only.
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
        <el-row :gutter="20">
          <el-col :span="7">
            <el-card class="page-card form-card">
              <template #header>
                <strong>Create custom role</strong>
              </template>
              <el-form label-position="top">
                <el-form-item label="Role ID">
                  <el-input v-model="roleForm.roleId" />
                </el-form-item>
                <el-form-item label="Name">
                  <el-input v-model="roleForm.name" />
                </el-form-item>
                <el-form-item label="Description">
                  <el-input v-model="roleForm.description" />
                </el-form-item>
                <el-form-item label="Permissions">
                  <el-select
                    v-model="roleForm.permissions"
                    multiple
                    filterable
                    collapse-tags
                    collapse-tags-tooltip
                    class="full-width"
                  >
                    <el-option
                      v-for="permission in permissions"
                      :key="permission"
                      :label="permission"
                      :value="permission"
                    />
                  </el-select>
                </el-form-item>
                <el-button
                  type="primary"
                  :disabled="!canEditRoles || !roleForm.roleId.trim() || !roleForm.name.trim()"
                  @click="submitCreateRole"
                >
                  Create role
                </el-button>
              </el-form>
            </el-card>
          </el-col>

          <el-col :span="17">
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
                <el-table-column prop="description" label="Description" min-width="260" />
                <el-table-column prop="systemRole" label="System" min-width="120">
                  <template #default="{ row }">
                    <el-tag :type="row.systemRole ? 'success' : 'info'">
                      {{ row.systemRole ? 'SYSTEM' : 'CUSTOM' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="Permissions" min-width="320">
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
                <el-table-column label="Actions" min-width="120" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      link
                      type="primary"
                      :disabled="row.systemRole || !canEditRoles"
                      @click="openEditRole(row)"
                    >
                      Edit
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
        </el-row>

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

    <el-dialog v-model="editDialogVisible" title="Edit role" width="680px">
      <el-form label-position="top">
        <el-form-item label="Name">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="Description">
          <el-input v-model="editForm.description" />
        </el-form-item>
        <el-form-item label="Permissions">
          <el-select
            v-model="editForm.permissions"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            class="full-width"
          >
            <el-option
              v-for="permission in permissions"
              :key="permission"
              :label="permission"
              :value="permission"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="submitUpdateRole">
          Save
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, reactive, ref} from 'vue'
import {
  createRole,
  listPermissions,
  listRoles,
  updateRole,
  type RoleRecord,
} from '@/api/roles'
import {useAuth} from '@/auth/use-auth'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import {toErrorMessage} from '@/utils/errors'

const { user } = useAuth()

const loading = ref(false)
const errorMessage = ref('')
const roles = ref<RoleRecord[]>([])
const permissions = ref<string[]>([])
const editDialogVisible = ref(false)

const roleForm = reactive({
  roleId: '',
  name: '',
  description: '',
  permissions: [] as string[],
})

const editForm = reactive({
  roleId: '',
  name: '',
  description: '',
  permissions: [] as string[],
})

const canEditRoles = computed(
  () => user.value?.permissions.includes('role:edit') ?? false,
)

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

async function submitCreateRole(): Promise<void> {
  try {
    await createRole({
      roleId: roleForm.roleId,
      name: roleForm.name,
      description: roleForm.description || null,
      permissions: roleForm.permissions,
    })
    roleForm.roleId = ''
    roleForm.name = ''
    roleForm.description = ''
    roleForm.permissions = []
    await loadRoles()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to create role.')
  }
}

function openEditRole(row: RoleRecord): void {
  editForm.roleId = row.roleId
  editForm.name = row.name
  editForm.description = row.description ?? ''
  editForm.permissions = [...row.permissions]
  editDialogVisible.value = true
}

async function submitUpdateRole(): Promise<void> {
  try {
    await updateRole(editForm.roleId, {
      name: editForm.name,
      description: editForm.description || null,
      permissions: editForm.permissions,
    })
    editDialogVisible.value = false
    await loadRoles()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to update role.')
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

.form-card {
  height: fit-content;
}

.full-width {
  width: 100%;
}
</style>
