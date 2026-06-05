<template>
  <ConsolePage
    tone="operator"
    width="wide"
    eyebrow="Server contract"
    title="API Reference"
    subtitle="Live server-generated HTTP documentation. The console links to the backend docs instead of maintaining a second frontend API dictionary."
  >
    <template #badge>
      <el-tag effect="plain" type="info">doc.html</el-tag>
    </template>
    <template #actions>
      <el-button type="primary" :disabled="!canOpenDocs" @click="openDocs">
        Open API Docs
      </el-button>
    </template>

    <el-card class="page-card api-reference-card">
      <template #header>
        <div class="api-reference-header">
          <strong>Backend API documentation</strong>
          <span class="api-reference-url mono">{{ apiDocsUrl }}</span>
        </div>
      </template>

      <div v-if="canEmbedDocs" class="api-reference-frame-shell">
        <iframe
          class="api-reference-frame"
          :src="apiDocsUrl"
          title="XA Mass backend API reference"
        />
      </div>

      <PageEmptyState
        v-else
        description="API docs are not available in this frontend preview. Configure VITE_API_DOCS_URL to a reachable backend docs URL, or open the backend-hosted console on localhost:8088."
      />
    </el-card>
  </ConsolePage>
</template>

<script setup lang="ts">
import {computed} from 'vue'
import {getAppConfig} from '@/app/config'
import PageEmptyState from '@/components/PageEmptyState.vue'
import ConsolePage from '@/console-kit/layout/ConsolePage.vue'

const config = computed(() => getAppConfig())
const apiDocsUrl = computed(() => config.value.apiDocsUrl)
const isDefaultDocsUrl = computed(() => apiDocsUrl.value === '/doc.html#/home')
const canEmbedDocs = computed(
  () => !(config.value.useMockApi && isDefaultDocsUrl.value),
)
const canOpenDocs = computed(
  () => canEmbedDocs.value || apiDocsUrl.value.trim().length > 0,
)

function openDocs(): void {
  if (!canOpenDocs.value) {
    return
  }
  window.open(apiDocsUrl.value, '_blank', 'noopener,noreferrer')
}
</script>

<style scoped>
.api-reference-card {
  min-height: 680px;
}

.api-reference-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.api-reference-url {
  color: var(--color-text-muted);
  font-size: 12px;
}

.api-reference-frame-shell {
  height: min(72vh, 820px);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: var(--color-surface-muted);
}

.api-reference-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: var(--color-surface-strong);
}

@media (max-width: 900px) {
  .api-reference-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .api-reference-frame-shell {
    height: 70vh;
  }
}
</style>
