<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <div class="sidebar-brand">
      <div class="brand-mark">XA</div>
      <div v-if="!collapsed" class="brand-copy">
        <div class="brand-title">Mass Console</div>
        <div class="brand-subtitle">Worker Orchestration</div>
      </div>
    </div>

    <el-menu
      :default-active="activePath"
      class="sidebar-menu"
      :collapse="collapsed"
      :collapse-transition="false"
      router
    >
      <template v-for="item in menuItems" :key="item.path">
        <el-sub-menu v-if="item.children.length > 0" :index="item.path">
          <template #title>
            <el-icon><component :is="resolveMenuIcon(item.icon)" /></el-icon>
            <span>{{ item.title }}</span>
          </template>
          <el-menu-item
            v-for="child in item.children"
            :key="child.path"
            :index="child.path"
          >
            <el-icon><component :is="resolveMenuIcon(child.icon)" /></el-icon>
            <span>{{ child.title }}</span>
          </el-menu-item>
        </el-sub-menu>
        <el-menu-item v-else :index="item.path">
          <el-icon><component :is="resolveMenuIcon(item.icon)" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </template>
    </el-menu>
  </aside>
</template>

<script setup lang="ts">
import {computed} from 'vue'
import {useRoute} from 'vue-router'
import {resolveMenuIcon} from '@/layouts/icons'
import {buildMenuModel} from '@/router/menu-model'
import {appRoutes} from '@/router/routes'

defineProps<{
  collapsed: boolean
}>()

const route = useRoute()

const menuItems = computed(() => {
  const rootRoute = appRoutes[0]
  return rootRoute.children
    ? buildMenuModel(rootRoute.children, 'operator', '/')
    : []
})

const activePath = computed(() => route.path)
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  width: var(--sidebar-width);
  min-height: 100vh;
  padding: 20px 16px;
  flex-shrink: 0;
  background: linear-gradient(
    180deg,
    var(--sidebar-bg-start),
    var(--sidebar-bg-end)
  );
  color: var(--sidebar-text);
  transition: width 0.2s ease, padding 0.2s ease;
}

.sidebar--collapsed {
  width: var(--sidebar-width-collapsed);
  padding: 20px 8px;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 8px 20px;
  min-height: 72px;
}

.sidebar--collapsed .sidebar-brand {
  justify-content: center;
  padding: 8px 0 20px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-card);
  background: linear-gradient(
    135deg,
    var(--sidebar-brand-start),
    var(--sidebar-brand-end)
  );
  font-weight: 800;
  letter-spacing: 0;
  flex-shrink: 0;
}

.brand-copy {
  min-width: 0;
}

.brand-title {
  font-size: 17px;
  font-weight: 700;
}

.brand-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: var(--sidebar-text-muted);
}

:deep(.sidebar-menu) {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--sidebar-text-menu);
  --el-menu-hover-bg-color: var(--sidebar-hover-bg);
  --el-menu-active-color: var(--sidebar-text);
  --el-menu-item-height: 48px;
  width: 100%;
}

:deep(.sidebar-menu .el-menu-item),
:deep(.sidebar-menu .el-sub-menu__title) {
  border-radius: var(--radius-card);
  color: var(--sidebar-text-menu);
}

:deep(.sidebar-menu .el-sub-menu .el-menu) {
  background: transparent;
}

:deep(.sidebar-menu .el-sub-menu .el-menu-item) {
  margin-top: 6px;
  padding-left: 44px !important;
  background: var(--sidebar-child-bg);
}

:deep(.sidebar-menu .el-menu-item:hover),
:deep(.sidebar-menu .el-sub-menu__title:hover) {
  background: var(--sidebar-hover-bg);
}

:deep(.sidebar-menu .el-sub-menu.is-opened > .el-sub-menu__title) {
  background: var(--sidebar-open-bg);
  color: var(--sidebar-text);
}

:deep(.sidebar-menu .el-menu-item.is-active) {
  background: var(--sidebar-active-bg);
  color: var(--sidebar-text);
}
</style>
