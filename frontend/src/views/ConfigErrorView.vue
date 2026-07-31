<script setup lang="ts">
import { WarningFilled } from "@element-plus/icons-vue";

import type { RuntimeViewerConfigError } from "@/runtime-viewer/types";

defineProps<{
  error: RuntimeViewerConfigError;
}>();
</script>

<template>
  <main class="standalone-state" data-testid="config-error">
    <section class="standalone-state__card">
      <img src="/logo.svg" alt="" width="42" height="42" />
      <el-icon class="standalone-state__icon"><WarningFilled /></el-icon>
      <p class="standalone-state__eyebrow">RUNTIME VIEWER</p>
      <h1>{{ error.title }}</h1>
      <p>请修正本地环境配置后重新启动 Vite。配置错误期间不会发送 Runtime View 请求。</p>
      <ul>
        <li v-for="detail in error.details" :key="detail">
          {{ detail }}
        </li>
      </ul>
    </section>
  </main>
</template>
