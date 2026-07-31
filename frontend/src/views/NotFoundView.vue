<script setup lang="ts">
import { ArrowLeft } from "@element-plus/icons-vue";
</script>

<template>
  <main class="standalone-state">
    <section class="standalone-state__card">
      <img src="/logo.svg" alt="" width="42" height="42" />
      <p class="standalone-state__eyebrow">404</p>
      <h1>页面不存在</h1>
      <p>第一版 Runtime Viewer 只提供 Worker 页面。</p>
      <router-link class="standalone-state__link" to="/runtime/workers">
        <el-icon><ArrowLeft /></el-icon>
        返回 Worker Runtime
      </router-link>
    </section>
  </main>
</template>
