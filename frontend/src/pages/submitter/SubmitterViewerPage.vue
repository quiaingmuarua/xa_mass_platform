<template>
  <section class="app-page submitter-viewer-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">API Key Viewer</h2>
        <p class="page-subtitle">
          Enter an API key secret to inspect that key's submitter profile and
          usage. The secret is used once and is not stored in this browser.
        </p>
      </div>
      <el-button :disabled="!viewerCredential" @click="refreshViewer">
        Refresh
      </el-button>
    </header>

    <el-alert
      v-if="useMockApi"
      class="page-alert"
      type="warning"
      :closable="false"
      title="Backend API mode is required for API-key viewer."
    />

    <PageErrorState
      v-if="errorMessage"
      :message="errorMessage"
      @retry="refreshViewer"
    />

    <section class="viewer-grid">
      <el-card class="page-card credential-card">
        <template #header>
          <strong>Open viewer</strong>
        </template>
        <el-form label-position="top">
          <el-form-item label="API Key Secret">
            <el-input
              v-model="apiKeySecret"
              type="password"
              show-password
              placeholder="mass_sk_..."
              autocomplete="off"
            />
            <div class="field-hint">
              The secret works like a password. It is exchanged for a short-lived
              browser credential and is never stored locally.
            </div>
          </el-form-item>
          <el-button
            type="primary"
            :disabled="!apiKeySecret.trim()"
            @click="openViewer"
          >
            View API key usage
          </el-button>
        </el-form>
        <el-button
          class="exit-button"
          type="danger"
          plain
          :disabled="!viewerCredential"
          @click="exitViewer"
        >
          Exit viewer
        </el-button>
      </el-card>

      <el-card class="page-card">
        <template #header>
          <strong>Current API key</strong>
        </template>
        <PageEmptyState
          v-if="!viewer"
          description="Enter an API key secret to inspect key-owned resources."
        />
        <el-descriptions v-else :column="1" border>
          <el-descriptions-item label="Principal">
            {{ viewer.principalId }}
          </el-descriptions-item>
          <el-descriptions-item label="Key ID">
            <span class="mono">{{ viewer.keyId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="Owner user">
            {{ viewer.createdForUserId }}
          </el-descriptions-item>
          <el-descriptions-item label="Permissions">
            {{ viewer.permissions.join(', ') || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="Projects">
            {{ viewer.projectScopes.join(', ') || 'all' }}
          </el-descriptions-item>
          <el-descriptions-item label="Events">
            {{ viewer.eventScopes.join(', ') || 'all' }}
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
          {{ usage?.keyId ?? viewer?.keyId ?? '-' }}
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

const VIEWER_STORAGE_KEY = 'xa.mass.apiKeyViewerCredential'

const apiKeySecret = ref('')
const viewerCredential = ref('')
const viewer = ref<SubmitterViewerSessionView | null>(null)
const profile = ref<CurrentSubmitterProfile | null>(null)
const usage = ref<CurrentSubmitterUsageResponse | null>(null)
const errorMessage = ref('')
const loading = ref(false)

const useMockApi = computed(() => getAppConfig().useMockApi)

async function openViewer(): Promise<void> {
  errorMessage.value = ''
  try {
    const created = await createSubmitterViewerSession(apiKeySecret.value)
    apiKeySecret.value = ''
    setViewerCredential(created.rawSecret)
    viewer.value = created.session
    await refreshViewer()
  } catch (error) {
    errorMessage.value = toErrorMessage(error, 'Failed to open API-key viewer.')
  }
}

async function refreshViewer(): Promise<void> {
  if (!viewerCredential.value) {
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const [viewerRow, profileRow, usageRow] = await Promise.all([
      getCurrentSubmitterViewerSession(viewerCredential.value),
      getSubmitterProfileWithCredential(viewerCredential.value),
      getSubmitterUsageWithCredential(viewerCredential.value),
    ])
    viewer.value = viewerRow
    profile.value = profileRow
    usage.value = usageRow
  } catch (error) {
    clearViewer()
    errorMessage.value = toErrorMessage(error, 'Failed to load API-key viewer.')
  } finally {
    loading.value = false
  }
}

async function exitViewer(): Promise<void> {
  if (!viewerCredential.value) {
    return
  }
  try {
    await logoutSubmitterViewerSession(viewerCredential.value)
  } catch {
    // Local cleanup is still correct if the server already invalidated it.
  } finally {
    clearViewer()
  }
}

function setViewerCredential(credential: string): void {
  const normalized = credential.trim()
  viewerCredential.value = normalized
  if (normalized) {
    window.sessionStorage.setItem(VIEWER_STORAGE_KEY, normalized)
  }
}

function clearViewer(): void {
  viewerCredential.value = ''
  viewer.value = null
  profile.value = null
  usage.value = null
  window.sessionStorage.removeItem(VIEWER_STORAGE_KEY)
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
  const stored = window.sessionStorage.getItem(VIEWER_STORAGE_KEY)
  if (stored) {
    viewerCredential.value = stored
    void refreshViewer()
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

.field-hint {
  margin-top: 8px;
  color: #667085;
  font-size: 13px;
  line-height: 1.5;
}

.exit-button {
  margin-top: 12px;
}

.compact-value {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 980px) {
  .viewer-grid {
    grid-template-columns: 1fr;
  }
}
</style>
