<template>
  <section class="app-page submitter-viewer-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Submitter Viewer</h2>
        <p class="page-subtitle">
          API-key backed browser session for owner-scoped task, result, archive,
          and usage reads. It is separate from operator console auth.
        </p>
      </div>
      <el-button :disabled="!sessionCredential" @click="refreshSession">
        Refresh
      </el-button>
    </header>

    <el-alert
      v-if="useMockApi"
      class="page-alert"
      type="warning"
      :closable="false"
      title="Backend API mode is required for submitter viewer sessions."
    />

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="refreshSession"
    />

    <section class="viewer-grid">
      <el-card class="page-card credential-card">
        <template #header>
          <strong>Create or attach session</strong>
        </template>
        <el-form label-position="top">
          <el-form-item label="Source API key">
            <el-input
              v-model="sourceApiKey"
              type="password"
              show-password
              placeholder="mass_sk_..."
              autocomplete="off"
            />
          </el-form-item>
          <el-button
            type="primary"
            :disabled="!sourceApiKey.trim()"
            @click="createSession"
          >
            Create viewer session
          </el-button>
        </el-form>

        <el-divider />

        <el-form label-position="top">
          <el-form-item label="Existing session token">
            <el-input
              v-model="sessionCredentialInput"
              type="password"
              show-password
              placeholder="mass_sess_..."
              autocomplete="off"
            />
          </el-form-item>
          <div class="action-row">
            <el-button
              :disabled="!sessionCredentialInput.trim()"
              @click="attachSession"
            >
              Attach
            </el-button>
            <el-button
              type="danger"
              plain
              :disabled="!sessionCredential"
              @click="logoutSession"
            >
              Logout
            </el-button>
          </div>
        </el-form>
      </el-card>

      <el-card class="page-card">
        <template #header>
          <strong>Current submitter session</strong>
        </template>
        <PageEmptyState
          v-if="!session"
          description="Create or attach a viewer session to inspect submitter-owned resources."
        />
        <el-descriptions v-else :column="1" border>
          <el-descriptions-item label="Session">
            <span class="mono">{{ session.sessionId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="Principal">
            {{ session.principalId }}
          </el-descriptions-item>
          <el-descriptions-item label="Source key">
            <span class="mono">{{ session.keyId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="Permissions">
            {{ session.permissions.join(', ') || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="Projects">
            {{ session.projectScopes.join(', ') || 'all' }}
          </el-descriptions-item>
          <el-descriptions-item label="Expires">
            {{ session.expiresAt }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
    </section>

    <section class="metric-grid">
      <div class="metric-tile">
        <div class="metric-label">Usage rows</div>
        <div class="metric-value">{{ usage?.total ?? 0 }}</div>
      </div>
      <div class="metric-tile">
        <div class="metric-label">Principal</div>
        <div class="metric-value compact-value">
          {{ profile?.principalId ?? '-' }}
        </div>
      </div>
      <div class="metric-tile">
        <div class="metric-label">Key</div>
        <div class="metric-value compact-value">
          {{ usage?.keyId ?? session?.keyId ?? '-' }}
        </div>
      </div>
    </section>

    <el-card class="page-card">
      <template #header>
        <strong>Recent usage</strong>
      </template>
      <PageSectionSkeleton v-if="loading" />
      <PageEmptyState
        v-else-if="!usage || usage.items.length === 0"
        description="No usage rows are visible for this submitter credential."
      />
      <el-table v-else :data="usage.items" row-key="usageId">
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
        <el-table-column prop="units" label="Units" min-width="100" />
        <el-table-column prop="createdAt" label="Created" min-width="220" />
      </el-table>
    </el-card>

    <el-dialog
      v-model="createdSecretVisible"
      title="Viewer session token"
      width="640px"
    >
      <el-alert
        type="warning"
        :closable="false"
        title="Copy this session token now. It is stored only in this browser session."
      />
      <pre class="secret-block">{{ createdSecret }}</pre>
      <template #footer>
        <el-button type="primary" @click="closeCreatedSecret">
          I copied it
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {
  createSubmitterViewerSession,
  getCurrentSubmitterViewerSession,
  getSubmitterProfileWithCredential,
  getSubmitterUsageWithCredential,
  logoutSubmitterViewerSession,
} from '@/api/submitter-sessions'
import {getAppConfig} from '@/app/config'
import PageEmptyState from '@/components/PageEmptyState.vue'
import PageErrorState from '@/components/PageErrorState.vue'
import PageSectionSkeleton from '@/components/PageSectionSkeleton.vue'
import type {
  ApiUsageStatus,
  CurrentSubmitterUsageResponse,
  SubmitterViewerSessionView,
} from '@/types/api-keys'
import type {CurrentSubmitterProfile} from '@/types/current-submitter'
import {toErrorMessage} from '@/utils/errors'

const SESSION_STORAGE_KEY = 'xa.mass.submitterViewerSession'

const sourceApiKey = ref('')
const sessionCredentialInput = ref('')
const sessionCredential = ref('')
const session = ref<SubmitterViewerSessionView | null>(null)
const profile = ref<CurrentSubmitterProfile | null>(null)
const usage = ref<CurrentSubmitterUsageResponse | null>(null)
const errorMessage = ref('')
const loading = ref(false)
const createdSecretVisible = ref(false)
const createdSecret = ref('')

const useMockApi = computed(() => getAppConfig().useMockApi)

async function createSession(): Promise<void> {
  errorMessage.value = ''
  try {
    const created = await createSubmitterViewerSession(sourceApiKey.value)
    sourceApiKey.value = ''
    createdSecret.value = created.rawSecret
    createdSecretVisible.value = true
    setSessionCredential(created.rawSecret)
    await refreshSession()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to create session.')
  }
}

async function attachSession(): Promise<void> {
  setSessionCredential(sessionCredentialInput.value)
  sessionCredentialInput.value = ''
  await refreshSession()
}

async function refreshSession(): Promise<void> {
  if (!sessionCredential.value) {
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const [sessionRow, profileRow, usageRow] = await Promise.all([
      getCurrentSubmitterViewerSession(sessionCredential.value),
      getSubmitterProfileWithCredential(sessionCredential.value),
      getSubmitterUsageWithCredential(sessionCredential.value),
    ])
    session.value = sessionRow
    profile.value = profileRow
    usage.value = usageRow
  } catch (error) {
    clearSession()
    errorMessage.value = toErrorMessage(error, 'Failed to load submitter session.')
  } finally {
    loading.value = false
  }
}

async function logoutSession(): Promise<void> {
  if (!sessionCredential.value) {
    return
  }
  try {
    await logoutSubmitterViewerSession(sessionCredential.value)
  } catch {
    // Local cleanup is still correct if the server already invalidated it.
  } finally {
    clearSession()
  }
}

function setSessionCredential(credential: string): void {
  const normalized = credential.trim()
  sessionCredential.value = normalized
  if (normalized) {
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, normalized)
  }
}

function clearSession(): void {
  sessionCredential.value = ''
  session.value = null
  profile.value = null
  usage.value = null
  window.sessionStorage.removeItem(SESSION_STORAGE_KEY)
}

function closeCreatedSecret(): void {
  createdSecretVisible.value = false
  createdSecret.value = ''
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
  const stored = window.sessionStorage.getItem(SESSION_STORAGE_KEY)
  if (stored) {
    sessionCredential.value = stored
    void refreshSession()
  }
})
</script>

<style scoped>
.viewer-grid {
  display: grid;
  grid-template-columns: minmax(320px, 420px) 1fr;
  gap: 20px;
  margin-bottom: 20px;
}

.page-alert {
  margin-bottom: 16px;
}

.credential-card {
  height: fit-content;
}

.action-row {
  display: flex;
  gap: 10px;
}

.compact-value {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

@media (max-width: 980px) {
  .viewer-grid {
    grid-template-columns: 1fr;
  }
}
</style>
