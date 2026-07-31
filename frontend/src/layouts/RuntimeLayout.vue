<script setup lang="ts">
import { computed, onMounted } from "vue";
import { Collection, Connection, Moon, Sunny, View } from "@element-plus/icons-vue";

import { useRuntimeViewerConfig } from "@/runtime-context";
import { useThemeStore } from "@/stores/theme";

const config = useRuntimeViewerConfig();
const theme = useThemeStore();
const sourceLabel = computed(() =>
  config.mode === "api" ? "API source" : "Mock source"
);

onMounted(() => theme.apply());
</script>

<template>
  <div class="runtime-shell">
    <aside class="runtime-sidebar" aria-label="主导航">
      <div class="runtime-brand">
        <img
          class="runtime-brand__logo"
          src="/logo.svg"
          alt=""
          width="34"
          height="34"
        />
        <div>
          <strong>XA MASS</strong>
          <span>RUNTIME VIEWER</span>
        </div>
      </div>

      <nav class="runtime-navigation" aria-label="Runtime">
        <span class="runtime-navigation__eyebrow">RUNTIME</span>
        <router-link class="runtime-navigation__link" to="/runtime/workers">
          <el-icon><Collection /></el-icon>
          <span>Workers</span>
        </router-link>
      </nav>

      <div class="runtime-sidebar__note">
        <span class="runtime-sidebar__note-title">
          <el-icon aria-hidden="true"><View /></el-icon>
          Read-only preview
        </span>
        <p>当前页面只展示 Owner 提供的随机描述符样本。</p>
      </div>
    </aside>

    <div class="runtime-stage">
      <header class="runtime-topbar">
        <div class="runtime-mobile-brand">
          <img src="/logo.svg" alt="" width="28" height="28" />
          <strong>XA MASS</strong>
        </div>
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>Runtime</el-breadcrumb-item>
          <el-breadcrumb-item>Workers</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="runtime-topbar__actions">
          <span
            class="source-badge"
            :class="{ 'source-badge--mock': config.mode === 'mock' }"
            data-testid="source-badge"
          >
            <el-icon aria-hidden="true"><Connection /></el-icon>
            {{ sourceLabel }}
          </span>
          <el-button
            circle
            class="theme-toggle"
            :aria-label="theme.dark ? '切换浅色模式' : '切换深色模式'"
            data-testid="theme-toggle"
            @click="theme.toggle"
          >
            <el-icon>
              <Sunny v-if="theme.dark" />
              <Moon v-else />
            </el-icon>
          </el-button>
        </div>
      </header>

      <main class="runtime-main">
        <router-view />
      </main>
    </div>
  </div>
</template>
