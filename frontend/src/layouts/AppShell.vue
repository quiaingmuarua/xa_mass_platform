<template>
  <div class="shell" :class="`shell--${currentShell}`">
    <AppSidebar v-if="currentShell === 'operator'" :collapsed="sidebarCollapsed" />
    <div class="shell-main">
      <AppHeader
        v-if="currentShell === 'operator'"
        :sidebar-collapsed="sidebarCollapsed"
        @toggle-sidebar="toggleSidebar"
      />
      <main class="shell-content">
        <RouterView v-slot="{ Component, route }">
          <KeepAlive>
            <component
              :is="Component"
              v-if="route.meta.keepAlive"
              :key="route.fullPath"
            />
          </KeepAlive>
          <component
            :is="Component"
            v-if="!route.meta.keepAlive"
            :key="route.fullPath"
          />
        </RouterView>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import {RouterView, useRoute} from 'vue-router'
import AppHeader from '@/layouts/AppHeader.vue'
import AppSidebar from '@/layouts/AppSidebar.vue'

const route = useRoute()
const currentShell = computed(() => route.meta.shell)
const sidebarCollapsed = ref(false)

function toggleSidebar(): void {
  sidebarCollapsed.value = !sidebarCollapsed.value
}
</script>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}

.shell-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.shell-content {
  flex: 1;
  padding: var(--shell-content-padding);
}

.shell--submitter-viewer .shell-content,
.shell--public .shell-content {
  display: flex;
  justify-content: center;
  padding: var(--shell-public-padding);
}
</style>
