<template>
  <header class="header">
    <div class="header-left">
      <el-tooltip
        :content="sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'"
        placement="bottom"
      >
        <el-button
          class="header-icon-button"
          :aria-label="sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'"
          text
          @click="$emit('toggle-sidebar')"
        >
          <el-icon>
            <component :is="sidebarCollapsed ? Expand : Fold" />
          </el-icon>
        </el-button>
      </el-tooltip>
      <el-breadcrumb class="header-breadcrumb" separator="/">
        <el-breadcrumb-item
          v-for="item in breadcrumbs"
          :key="item.path"
          :to="item.path"
        >
          {{ item.title }}
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>
    <el-dropdown class="header-user" trigger="click">
      <button class="user-trigger" type="button">
        <span class="user-avatar">{{ userInitials }}</span>
        <span class="user-copy">
          <span class="header-user-name">{{ user?.name ?? 'Guest' }}</span>
          <span class="header-user-meta">{{ primaryRole }}</span>
        </span>
        <el-icon><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu class="user-menu">
          <div class="user-menu-summary">
            <strong>{{ user?.name ?? 'Guest' }}</strong>
            <span>{{ user?.email ?? 'No email' }}</span>
            <small>{{ user?.permissions.length ?? 0 }} permissions</small>
          </div>
          <div v-if="showOperatorModeSelect" class="user-menu-mode" @click.stop>
            <span>Operator mode</span>
            <el-select
              :model-value="operatorMode"
              class="operator-select"
              size="small"
              @update:model-value="changeOperatorMode"
            >
              <el-option label="Ops Admin" value="admin" />
              <el-option label="Ops Viewer" value="viewer" />
            </el-select>
          </div>
          <el-dropdown-item class="logout-menu-item" divided @click="handleLogout">
            <el-icon><SwitchButton /></el-icon>
            Logout
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Expand, Fold, SwitchButton } from '@element-plus/icons-vue'
import { getAppConfig } from '@/app/config'
import { isDevHeaderAuth } from '@/auth/backend-auth'
import { initializeAuth, logout, useAuth } from '@/auth/use-auth'
import { type OperatorMode, useOperatorMode } from '@/auth/operator-mode'

defineProps<{
  sidebarCollapsed: boolean
}>()

defineEmits<{
  (event: 'toggle-sidebar'): void
}>()

const route = useRoute()
const router = useRouter()
const { user } = useAuth()
const { operatorMode, setOperatorMode } = useOperatorMode()

const useMockAuth = computed(() => getAppConfig().useMockAuth)
const showOperatorModeSelect = computed(
  () => useMockAuth.value || isDevHeaderAuth.value,
)
const breadcrumbs = computed(() =>
  route.matched
    .filter((record) => record.meta.title && record.path !== '/')
    .map((record) => ({
      path: record.path,
      title: String(record.meta.title),
    })),
)
const primaryRole = computed(() => user.value?.roles[0] ?? 'No role')
const userInitials = computed(() => {
  const name = user.value?.name?.trim()
  if (!name) {
    return 'GU'
  }
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')
})

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
  min-height: 64px;
  padding: 0 20px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-strong);
}

.header-left {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 14px;
}

.header-icon-button {
  width: 36px;
  height: 36px;
  font-size: 18px;
}

.header-breadcrumb {
  min-width: 0;
  font-size: 13px;
}

:deep(.header-breadcrumb .el-breadcrumb__inner) {
  color: var(--color-text-muted);
  font-weight: 600;
}

.header-user {
  flex-shrink: 0;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--radius-card);
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
}

.user-trigger:hover {
  background: var(--color-primary-soft);
}

.user-avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--color-primary);
  color: var(--color-surface-strong);
  font-size: 12px;
  font-weight: 800;
}

.user-copy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}

.operator-select {
  width: 128px;
}

.header-user-name {
  font-weight: 700;
  font-size: 13px;
}

.header-user-meta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--color-text-subtle);
}

.user-menu {
  min-width: 260px;
}

.user-menu-summary {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 16px 12px;
  color: var(--color-text);
}

.user-menu-summary span,
.user-menu-summary small {
  color: var(--color-text-subtle);
}

.user-menu-mode {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px 14px;
  color: var(--color-text-muted);
  font-size: 13px;
}
</style>
