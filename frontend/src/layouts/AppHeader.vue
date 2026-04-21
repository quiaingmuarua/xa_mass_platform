<template>
  <header class="header page-card">
    <div>
      <p class="header-eyebrow">XA Mass Platform</p>
      <h1 class="header-title">{{ currentTitle }}</h1>
    </div>
    <div class="header-user">
      <div>
        <div class="header-user-name">{{ user?.name ?? 'Guest' }}</div>
        <div class="header-user-meta">
          {{ user?.roles.join(', ') ?? 'No role' }} ·
          {{ user?.permissions.length ?? 0 }} permissions
        </div>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuth } from '@/auth/use-auth'

const route = useRoute()
const { user } = useAuth()

const currentTitle = computed(() => route.meta.title ?? 'Control Console')
</script>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
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

.header-user {
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: right;
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
