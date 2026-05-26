<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">API Keys</h2>
        <p class="page-subtitle">
          Scoped programmatic credentials for SDK-first task submission and
          result reads. Raw secrets are shown once after create or approval.
        </p>
      </div>
      <el-button @click="loadAll">Refresh</el-button>
    </header>

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="loadAll"
    />

    <template v-else>
      <section class="metric-grid">
        <div class="metric-tile">
          <div class="metric-label">Credentials</div>
          <div class="metric-value">{{ credentials.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Active</div>
          <div class="metric-value">{{ activeCredentialCount }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Applications</div>
          <div class="metric-value">{{ applications.length }}</div>
        </div>
        <div class="metric-tile">
          <div class="metric-label">Pending</div>
          <div class="metric-value">{{ pendingApplicationCount }}</div>
        </div>
      </section>

      <PageSectionSkeleton v-if="loading" />

      <el-tabs v-else v-model="activeTab" class="api-key-tabs">
        <el-tab-pane label="Credentials" name="credentials">
          <el-row :gutter="20">
            <el-col :span="8">
              <el-card class="page-card form-card">
                <template #header>
                  <strong>Create API key</strong>
                </template>
                <el-form label-position="top">
                  <el-form-item label="Principal ID">
                    <el-input v-model="createForm.principalId" />
                  </el-form-item>
                  <el-form-item label="Created for user">
                    <el-input v-model="createForm.createdForUserId" />
                  </el-form-item>
                  <el-form-item label="Project scopes">
                    <el-input
                      v-model="createForm.projectScopes"
                      placeholder="crawlerApp, demoApp"
                    />
                  </el-form-item>
                  <el-form-item label="Event scopes">
                    <el-input
                      v-model="createForm.eventScopes"
                      placeholder="crawler.fetch-page"
                    />
                  </el-form-item>
                  <el-form-item label="Permissions">
                    <el-select
                      v-model="createForm.permissions"
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
                  <el-form-item label="Expires at">
                    <el-date-picker
                      v-model="createForm.expiresAt"
                      type="datetime"
                      value-format="YYYY-MM-DDTHH:mm:ss[Z]"
                      placeholder="Optional expiry"
                      class="full-width"
                    />
                  </el-form-item>
                  <el-button
                    type="primary"
                    :disabled="!canApproveApiKeys"
                    @click="submitCreateCredential"
                  >
                    Create key
                  </el-button>
                </el-form>
              </el-card>
            </el-col>
            <el-col :span="16">
              <el-card class="page-card">
                <template #header>
                  <strong>Credential registry</strong>
                </template>
                <PageEmptyState
                  v-if="credentials.length === 0"
                  description="No API keys are currently available."
                />
                <el-table v-else :data="credentials" row-key="keyId">
                  <el-table-column prop="principalId" label="Principal" min-width="220">
                    <template #default="{ row }">
                      <div class="row-primary">{{ row.principalId }}</div>
                      <div class="row-secondary mono">{{ row.keyId }}</div>
                    </template>
                  </el-table-column>
                  <el-table-column prop="keyPrefix" label="Prefix" min-width="180" />
                  <el-table-column prop="status" label="Status" min-width="120">
                    <template #default="{ row }">
                      <el-tag :type="credentialStatusTag(row.status)">
                        {{ row.status }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="expiresAt" label="Expires" min-width="190">
                    <template #default="{ row }">
                      {{ row.expiresAt ?? 'never' }}
                    </template>
                  </el-table-column>
                  <el-table-column label="Scopes" min-width="260">
                    <template #default="{ row }">
                      <div class="row-secondary">
                        projects: {{ row.projectScopes.join(', ') || 'all' }}
                      </div>
                      <div class="row-secondary">
                        events: {{ row.eventScopes.join(', ') || 'all' }}
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="Permissions" min-width="260">
                    <template #default="{ row }">
                      {{ row.permissions.join(', ') || '-' }}
                    </template>
                  </el-table-column>
                  <el-table-column label="Actions" min-width="150" fixed="right">
                    <template #default="{ row }">
                      <el-button
                        link
                        type="primary"
                        @click="showCredentialDetail(row)"
                      >
                        Detail
                      </el-button>
                      <el-button
                        link
                        type="primary"
                        :disabled="!canViewUsage"
                        @click="showCredentialUsage(row)"
                      >
                        Usage
                      </el-button>
                      <el-button
                        link
                        type="danger"
                        :disabled="row.status !== 'ACTIVE' || !canRevokeApiKeys"
                        @click="submitRevokeCredential(row)"
                      >
                        Revoke
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>
        </el-tab-pane>

        <el-tab-pane label="Applications" name="applications">
          <el-row :gutter="20">
            <el-col :span="8">
              <el-card class="page-card form-card">
                <template #header>
                  <strong>Apply for API key</strong>
                </template>
                <el-form label-position="top">
                  <el-form-item label="Requested principal ID">
                    <el-input v-model="applicationForm.requestedPrincipalId" />
                  </el-form-item>
                  <el-form-item label="Requested user">
                    <el-input v-model="applicationForm.requestedUserId" />
                  </el-form-item>
                  <el-form-item label="Project scopes">
                    <el-input v-model="applicationForm.requestedProjectScopes" />
                  </el-form-item>
                  <el-form-item label="Event scopes">
                    <el-input v-model="applicationForm.requestedEventScopes" />
                  </el-form-item>
                  <el-form-item label="Requested permissions">
                    <el-select
                      v-model="applicationForm.requestedPermissions"
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
                  <el-form-item label="Purpose">
                    <el-input
                      v-model="applicationForm.purpose"
                      type="textarea"
                      :rows="3"
                    />
                  </el-form-item>
                  <el-button
                    type="primary"
                    :disabled="!canApplyApiKeys"
                    @click="submitCreateApplication"
                  >
                    Create application
                  </el-button>
                </el-form>
              </el-card>
            </el-col>
            <el-col :span="16">
              <el-card class="page-card">
                <template #header>
                  <strong>Application queue</strong>
                </template>
                <PageEmptyState
                  v-if="applications.length === 0"
                  description="No API-key applications are currently available."
                />
                <el-table v-else :data="applications" row-key="applicationId">
                  <el-table-column prop="requestedPrincipalId" label="Request" min-width="230">
                    <template #default="{ row }">
                      <div class="row-primary">
                        {{ row.requestedPrincipalId || 'auto principal' }}
                      </div>
                      <div class="row-secondary mono">{{ row.applicationId }}</div>
                    </template>
                  </el-table-column>
                  <el-table-column prop="status" label="Status" min-width="120">
                    <template #default="{ row }">
                      <el-tag :type="applicationStatusTag(row.status)">
                        {{ row.status }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="requestedUserId" label="User" min-width="160" />
                  <el-table-column prop="purpose" label="Purpose" min-width="260" />
                  <el-table-column label="Permissions" min-width="260">
                    <template #default="{ row }">
                      {{ row.requestedPermissions.join(', ') || '-' }}
                    </template>
                  </el-table-column>
                  <el-table-column label="Actions" min-width="210" fixed="right">
                    <template #default="{ row }">
                      <el-button
                        link
                        type="primary"
                        @click="showApplicationDetail(row)"
                      >
                        Detail
                      </el-button>
                      <el-button
                        link
                        type="primary"
                        :disabled="row.status !== 'PENDING' || !canApproveApiKeys"
                        @click="submitApproveApplication(row)"
                      >
                        Approve
                      </el-button>
                      <el-button
                        link
                        type="danger"
                        :disabled="row.status !== 'PENDING' || !canApproveApiKeys"
                        @click="submitRejectApplication(row)"
                      >
                        Reject
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-dialog v-model="secretDialogVisible" title="API key secret" width="640px">
      <el-alert
        type="warning"
        :closable="false"
        title="Copy this secret now. It is shown only once and is not stored in the browser."
      />
      <pre class="secret-block">{{ rawSecret }}</pre>
      <template #footer>
        <el-button type="primary" @click="closeSecretDialog">I copied it</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailDialogVisible" title="API key detail" width="720px">
      <pre class="detail-block">{{ detailPayload }}</pre>
    </el-dialog>

    <el-dialog v-model="usageDialogVisible" title="API key usage" width="860px">
      <PageEmptyState
        v-if="usageRows.length === 0"
        description="No usage rows are available for this key."
      />
      <el-table v-else :data="usageRows" row-key="usageId">
        <el-table-column prop="operation" label="Operation" min-width="180" />
        <el-table-column prop="status" label="Status" min-width="150">
          <template #default="{ row }">
            <el-tag :type="usageStatusTag(row.status)">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="project" label="Project" min-width="140" />
        <el-table-column prop="taskId" label="Task" min-width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.taskId ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="units" label="Units" min-width="90" />
        <el-table-column prop="createdAt" label="Created" min-width="220" />
      </el-table>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, reactive, ref} from 'vue'
import {
  approveApiKeyApplication,
  createApiKey,
  createApiKeyApplication,
  getApiKey,
  getApiKeyApplication,
  listApiKeyUsage,
  listApiKeyApplications,
  listApiKeys,
  rejectApiKeyApplication,
  revokeApiKey,
} from '@/api/api-keys'
import {listPermissions} from '@/api/roles'
import {useAuth} from '@/auth/use-auth'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {
  ApiKeyApplicationRecord,
  ApiKeyApplicationStatus,
  ApiUsageLedgerRecord,
  ApiUsageStatus,
  ApiKeyCredentialStatus,
  ApiKeyCredentialView,
} from '@/types/api-keys'
import {toErrorMessage} from '@/utils/errors'

const { user } = useAuth()

const loading = ref(false)
const errorMessage = ref('')
const activeTab = ref('credentials')
const credentials = ref<ApiKeyCredentialView[]>([])
const applications = ref<ApiKeyApplicationRecord[]>([])
const permissions = ref<string[]>([])
const secretDialogVisible = ref(false)
const rawSecret = ref('')
const detailDialogVisible = ref(false)
const detailPayload = ref('')
const usageDialogVisible = ref(false)
const usageRows = ref<ApiUsageLedgerRecord[]>([])

const createForm = reactive({
  principalId: 'crawler-api-key',
  createdForUserId: 'ops-admin',
  projectScopes: 'crawlerApp',
  eventScopes: 'crawler.fetch-page',
  permissions: ['task:create', 'task:view'],
  expiresAt: '',
})

const applicationForm = reactive({
  requestedPrincipalId: 'requested-crawler-key',
  requestedUserId: 'ops-admin',
  requestedProjectScopes: 'crawlerApp',
  requestedEventScopes: 'crawler.fetch-page',
  requestedPermissions: ['task:create', 'task:view'],
  purpose: 'SDK integration key',
})

const activeCredentialCount = computed(
  () => credentials.value.filter((credential) => credential.status === 'ACTIVE').length,
)
const pendingApplicationCount = computed(
  () => applications.value.filter((application) => application.status === 'PENDING').length,
)
const canApplyApiKeys = computed(() => hasPermission('api-key:apply'))
const canApproveApiKeys = computed(() => hasPermission('api-key:approve'))
const canRevokeApiKeys = computed(() => hasPermission('api-key:revoke'))
const canViewUsage = computed(() => hasPermission('api-usage:view'))

async function loadAll(): Promise<void> {
  loading.value = true
  errorMessage.value = ''

  try {
    const [credentialRows, applicationRows, permissionRows] = await Promise.all([
      listApiKeys(),
      listApiKeyApplications(),
      listPermissions(),
    ])
    credentials.value = credentialRows
    applications.value = applicationRows
    permissions.value = permissionRows
  } catch (error) {
    credentials.value = []
    applications.value = []
    permissions.value = []
    errorMessage.value = toErrorMessage(error, 'Failed to load API keys.')
  } finally {
    loading.value = false
  }
}

async function submitCreateCredential(): Promise<void> {
  try {
    const created = await createApiKey({
      principalId: createForm.principalId,
      createdForUserId: createForm.createdForUserId,
      projectScopes: csv(createForm.projectScopes),
      eventScopes: csv(createForm.eventScopes),
      permissions: createForm.permissions,
      expiresAt: createForm.expiresAt || null,
    })
    showRawSecret(created.rawSecret)
    await loadAll()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to create API key.')
  }
}

async function showCredentialUsage(row: ApiKeyCredentialView): Promise<void> {
  try {
    const usage = await listApiKeyUsage(row.keyId)
    usageRows.value = usage.items
    usageDialogVisible.value = true
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to load API-key usage.')
  }
}

async function submitRevokeCredential(row: ApiKeyCredentialView): Promise<void> {
  try {
    await revokeApiKey(row.keyId, 'revoked from console')
    await loadAll()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to revoke API key.')
  }
}

async function showCredentialDetail(row: ApiKeyCredentialView): Promise<void> {
  try {
    const detail = await getApiKey(row.keyId)
    showDetail(detail)
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to load API-key detail.')
  }
}

async function submitCreateApplication(): Promise<void> {
  try {
    await createApiKeyApplication({
      requestedPrincipalId: applicationForm.requestedPrincipalId || null,
      requestedUserId: applicationForm.requestedUserId,
      requestedProjectScopes: csv(applicationForm.requestedProjectScopes),
      requestedEventScopes: csv(applicationForm.requestedEventScopes),
      requestedPermissions: applicationForm.requestedPermissions,
      purpose: applicationForm.purpose,
    })
    await loadAll()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to create application.')
  }
}

async function showApplicationDetail(
  row: ApiKeyApplicationRecord,
): Promise<void> {
  try {
    const detail = await getApiKeyApplication(row.applicationId)
    showDetail(detail)
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to load application detail.')
  }
}

async function submitApproveApplication(
  row: ApiKeyApplicationRecord,
): Promise<void> {
  try {
    const approved = await approveApiKeyApplication(row.applicationId, 'approved from console')
    showRawSecret(approved.rawSecret)
    await loadAll()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to approve application.')
  }
}

async function submitRejectApplication(
  row: ApiKeyApplicationRecord,
): Promise<void> {
  try {
    await rejectApiKeyApplication(row.applicationId, 'rejected from console')
    await loadAll()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to reject application.')
  }
}

function showRawSecret(secret: string): void {
  rawSecret.value = secret
  secretDialogVisible.value = true
}

function closeSecretDialog(): void {
  secretDialogVisible.value = false
  rawSecret.value = ''
}

function showDetail(detail: ApiKeyCredentialView | ApiKeyApplicationRecord): void {
  detailPayload.value = JSON.stringify(detail, null, 2)
  detailDialogVisible.value = true
}

function hasPermission(permission: string): boolean {
  return user.value?.permissions.includes(permission) ?? false
}

function csv(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function credentialStatusTag(
  status: ApiKeyCredentialStatus,
): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'ACTIVE') {
    return 'success'
  }
  if (status === 'REVOKED') {
    return 'danger'
  }
  if (status === 'DISABLED') {
    return 'warning'
  }
  return 'info'
}

function applicationStatusTag(
  status: ApiKeyApplicationStatus,
): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'PENDING') {
    return 'warning'
  }
  if (status === 'APPROVED') {
    return 'success'
  }
  if (status === 'REJECTED') {
    return 'danger'
  }
  return 'info'
}

function usageStatusTag(status: ApiUsageStatus): 'success' | 'warning' | 'danger' {
  if (status === 'ACCEPTED') {
    return 'success'
  }
  if (status === 'FAILED_AFTER_ACCEPT') {
    return 'warning'
  }
  return 'danger'
}

onMounted(() => {
  void loadAll()
})
</script>

<style scoped>
.api-key-tabs {
  margin-top: 20px;
}

.form-card {
  height: 100%;
}

.full-width {
  width: 100%;
}

.secret-block {
  margin: 16px 0 0;
  padding: 14px;
  border-radius: 14px;
  background: #101828;
  color: #ecfdf3;
  white-space: pre-wrap;
  word-break: break-all;
}

.detail-block {
  margin: 0;
  padding: 14px;
  max-height: 520px;
  overflow: auto;
  border-radius: 14px;
  background: #f8fafc;
  color: #344054;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
