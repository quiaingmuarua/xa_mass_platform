<template>
  <header class="header page-card">
    <div class="header-main">
      <p class="header-eyebrow">XA Mass Platform</p>
      <h1 class="header-title">{{ currentTitle }}</h1>
      <div class="header-mode-row">
        <span class="header-mode-badge" :class="modeBadgeClass">
          {{ integrationMode }}
        </span>
        <span class="header-mode-text">{{ authMode }}</span>
      </div>
    </div>
    <div class="header-user">
      <el-select
        v-if="showOperatorModeSelect"
        :model-value="operatorMode"
        class="operator-select"
        size="small"
        @update:model-value="changeOperatorMode"
      >
        <el-option label="Ops Admin" value="admin" />
        <el-option label="Ops Viewer" value="viewer" />
      </el-select>
      <div>
        <div class="header-user-name">{{ user?.name ?? 'Guest' }}</div>
        <div class="header-user-meta">
          {{ user?.roles.join(', ') ?? 'No role' }} -
          {{ user?.permissions.length ?? 0 }} permissions
        </div>
      </div>
      <el-button size="small" text @click="handleLogout">Logout</el-button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAppConfig } from '@/app/config'
import { isDevHeaderAuth, operatorAuthMode } from '@/auth/backend-auth'
import { initializeAuth, logout, useAuth } from '@/auth/use-auth'
import { type OperatorMode, useOperatorMode } from '@/auth/operator-mode'

const route = useRoute()
const router = useRouter()
const { user } = useAuth()
const { operatorMode, setOperatorMode } = useOperatorMode()

const currentTitle = computed(() => route.meta.title ?? 'Control Console')
const integrationMode = computed(() =>
  getAppConfig().useMockApi ? 'Mock API' : 'Backend API',
)
const authMode = computed(() =>
  getAppConfig().useMockAuth
    ? 'Mock auth'
    : `Backend ${operatorAuthMode.value}`,
)
const useMockAuth = computed(() => getAppConfig().useMockAuth)
const showOperatorModeSelect = computed(
  () => !useMockAuth.value && isDevHeaderAuth.value,
)
const modeBadgeClass = computed(() =>
  getAppConfig().useMockApi ? 'is-mock' : 'is-backend',
)

function changeOperatorMode(mode: OperatorMode): void {
  setOperatorMode(mode)
  void initializeAuth()
}

async function handleLogout(): Promise<void> {
  await logout()
  await router.push('/login')
}
</script>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
}

.header-main {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.header-eyebrow {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: #6e7b8f;
}

.header-title {
  margin: 6px 0 0;
  font-size: 24px;
  font-weight: 700;
  color: #122033;
}

.header-mode-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-mode-badge {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.header-mode-badge.is-backend {
  background: #e7f7ee;
  color: #11663a;
}

.header-mode-badge.is-mock {
  background: #fff4dc;
  color: #8b5a00;
}

.header-mode-text {
  font-size: 13px;
  color: #6e7b8f;
}

.header-user {
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: right;
}

.operator-select {
  width: 128px;
}

.header-user-name {
  font-weight: 700;
}

.header-user-meta {
  margin-top: 4px;
  font-size: 13px;
  color: #6e7b8f;
}
</style>
