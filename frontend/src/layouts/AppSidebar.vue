<template>
  <aside class="sidebar">
    <div class="sidebar-brand">
      <div class="brand-mark">XA</div>
      <div>
        <div class="brand-title">Mass Console</div>
        <div class="brand-subtitle">Worker Orchestration</div>
      </div>
    </div>

    <el-menu
      :default-active="activePath"
      class="sidebar-menu"
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
  width: 280px;
  min-height: 100vh;
  padding: 20px 16px;
  background: linear-gradient(
    180deg,
    rgba(10, 22, 41, 0.96),
    rgba(17, 36, 64, 0.94)
  );
  color: #f8fbff;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 8px 20px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: linear-gradient(135deg, #2f7cff, #79a7ff);
  font-weight: 800;
  letter-spacing: 0.08em;
}

.brand-title {
  font-size: 17px;
  font-weight: 700;
}

.brand-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(248, 251, 255, 0.72);
}

:deep(.sidebar-menu) {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: rgba(248, 251, 255, 0.88);
  --el-menu-hover-bg-color: rgba(121, 167, 255, 0.12);
  --el-menu-active-color: #ffffff;
  --el-menu-item-height: 48px;
}

:deep(.sidebar-menu .el-menu-item),
:deep(.sidebar-menu .el-sub-menu__title) {
  border-radius: 12px;
  color: rgba(248, 251, 255, 0.88);
}

:deep(.sidebar-menu .el-sub-menu .el-menu) {
  background: transparent;
}

:deep(.sidebar-menu .el-sub-menu .el-menu-item) {
  margin-top: 6px;
  padding-left: 44px !important;
  background: rgba(255, 255, 255, 0.04);
}

:deep(.sidebar-menu .el-menu-item:hover),
:deep(.sidebar-menu .el-sub-menu__title:hover) {
  background: rgba(121, 167, 255, 0.12);
}

:deep(.sidebar-menu .el-sub-menu.is-opened > .el-sub-menu__title) {
  background: rgba(255, 255, 255, 0.08);
  color: #ffffff;
}

:deep(.sidebar-menu .el-menu-item.is-active) {
  background: rgba(47, 124, 255, 0.2);
  color: #ffffff;
}
</style>
