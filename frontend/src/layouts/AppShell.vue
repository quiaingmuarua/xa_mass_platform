<template>
  <div class="shell" :class="`shell--${currentShell}`">
    <AppSidebar v-if="currentShell === 'operator'" />
    <div class="shell-main">
      <AppHeader v-if="currentShell === 'operator'" />
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
import {computed} from 'vue'
import {RouterView, useRoute} from 'vue-router'
import AppHeader from '@/layouts/AppHeader.vue'
import AppSidebar from '@/layouts/AppSidebar.vue'

const route = useRoute()
const currentShell = computed(() => route.meta.shell)
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
  padding: 24px;
}

.shell--submitter-viewer .shell-content,
.shell--public .shell-content {
  display: flex;
  justify-content: center;
  padding: 32px 20px;
}
</style>
