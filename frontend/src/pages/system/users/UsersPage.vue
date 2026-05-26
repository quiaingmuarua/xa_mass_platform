<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Users</h2>
        <p class="page-subtitle">
          Server-owned operator identities. User mutation is backed by the
          server IAM owner; disabling a user also disables owned API keys.
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

      <el-row v-else :gutter="20">
        <el-col :span="7">
          <el-card class="page-card form-card">
            <template #header>
              <strong>Create user</strong>
            </template>
            <el-form label-position="top">
              <el-form-item label="User ID">
                <el-input v-model="createForm.userId" />
              </el-form-item>
              <el-form-item label="Display name">
                <el-input v-model="createForm.displayName" />
              </el-form-item>
              <el-form-item label="Email">
                <el-input v-model="createForm.email" />
              </el-form-item>
              <el-button
                type="primary"
                :disabled="!canEditUsers || !createForm.userId.trim()"
                @click="submitCreateUser"
              >
                Create user
              </el-button>
            </el-form>
          </el-card>

          <el-card class="page-card form-card">
            <template #header>
              <strong>Bind role</strong>
            </template>
            <el-form label-position="top">
              <el-form-item label="User">
                <el-select
                  v-model="roleBinding.userId"
                  filterable
                  class="full-width"
                >
                  <el-option
                    v-for="userRow in users"
                    :key="userRow.userId"
                    :label="userRow.displayName || userRow.userId"
                    :value="userRow.userId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="Role">
                <el-select
                  v-model="roleBinding.roleId"
                  filterable
                  class="full-width"
                >
                  <el-option
                    v-for="role in roles"
                    :key="role.roleId"
                    :label="role.name"
                    :value="role.roleId"
                  />
                </el-select>
              </el-form-item>
              <div class="action-row">
                <el-button
                  type="primary"
                  :disabled="!canEditUsers || !roleBinding.userId || !roleBinding.roleId"
                  @click="submitBindRole"
                >
                  Bind
                </el-button>
                <el-button
                  :disabled="!canEditUsers || !roleBinding.userId || !roleBinding.roleId"
                  @click="submitUnbindRole"
                >
                  Unbind
                </el-button>
              </div>
            </el-form>
          </el-card>
        </el-col>

        <el-col :span="17">
          <el-card class="page-card">
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
              <el-table-column prop="email" label="Email" min-width="220" />
              <el-table-column prop="status" label="Status" min-width="130">
                <template #default="{ row }">
                  <el-tag :type="userStatusTag(row.status)">
                    {{ row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="updatedAt" label="Updated" min-width="190" />
              <el-table-column label="Actions" min-width="190" fixed="right">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    :disabled="!canEditUsers || row.status === 'ACTIVE'"
                    @click="submitSetUserStatus(row, 'ACTIVE')"
                  >
                    Enable
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    :disabled="!canEditUsers || row.status !== 'ACTIVE'"
                    @click="submitSetUserStatus(row, 'DISABLED')"
                  >
                    Disable
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>
    </template>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, reactive, ref} from 'vue'
import {listRoles, type RoleRecord} from '@/api/roles'
import {
  bindUserRole,
  createUser,
  listUsers,
  unbindUserRole,
  updateUser,
  type UserRecord,
} from '@/api/users'
import {useAuth} from '@/auth/use-auth'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import {toErrorMessage} from '@/utils/errors'

const { user } = useAuth()

const loading = ref(false)
const errorMessage = ref('')
const users = ref<UserRecord[]>([])
const roles = ref<RoleRecord[]>([])

const createForm = reactive({
  userId: '',
  displayName: '',
  email: '',
})

const roleBinding = reactive({
  userId: '',
  roleId: '',
})

const activeUserCount = computed(
  () => users.value.filter((user) => user.status === 'ACTIVE').length,
)
const canEditUsers = computed(
  () => user.value?.permissions.includes('user:edit') ?? false,
)

async function loadUsers(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [userRows, roleRows] = await Promise.all([listUsers(), listRoles()])
    users.value = userRows
    roles.value = roleRows
  } catch (error) {
    users.value = []
    roles.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load users.')
  } finally {
    loading.value = false
  }
}

async function submitCreateUser(): Promise<void> {
  try {
    await createUser({
      userId: createForm.userId,
      displayName: createForm.displayName || undefined,
      email: createForm.email || null,
      status: 'ACTIVE',
    })
    createForm.userId = ''
    createForm.displayName = ''
    createForm.email = ''
    await loadUsers()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to create user.')
  }
}

async function submitSetUserStatus(
  row: UserRecord,
  status: UserRecord['status'],
): Promise<void> {
  try {
    await updateUser(row.userId, { status })
    await loadUsers()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to update user.')
  }
}

async function submitBindRole(): Promise<void> {
  try {
    await bindUserRole(roleBinding.userId, roleBinding.roleId)
    await loadUsers()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to bind role.')
  }
}

async function submitUnbindRole(): Promise<void> {
  try {
    await unbindUserRole(roleBinding.userId, roleBinding.roleId)
    await loadUsers()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to unbind role.')
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

<style scoped>
.form-card + .form-card {
  margin-top: 20px;
}

.full-width {
  width: 100%;
}

.action-row {
  display: flex;
  gap: 10px;
}
</style>
