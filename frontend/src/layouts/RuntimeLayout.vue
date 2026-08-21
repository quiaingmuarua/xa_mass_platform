<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from "vue";
import { useRoute } from "vue-router";
import {
  Collection,
  Connection,
  Document,
  Link,
  Moon,
  Sunny,
  Tickets,
  VideoPlay,
  View
} from "@element-plus/icons-vue";

import { useRuntimeViewerConfig, useRuntimeViewerStore } from "@/runtime-context";
import { useThemeStore } from "@/stores/theme";

const config = useRuntimeViewerConfig();
const store = useRuntimeViewerStore();
const theme = useThemeStore();
const route = useRoute();
const sourceLabel = computed(() =>
  config.mode === "api" ? "API source" : "Mock source"
);
const pageTitle = computed(() => String(route.meta.title ?? "Runtime"));
const pageSection = computed(() =>
  route.name === "runtime-task-batches" ? "Lab" : "Runtime"
);

onMounted(() => theme.apply());
onBeforeUnmount(() => store.dispose());
</script>

<template>
  <div class="runtime-shell">
    <aside class="runtime-sidebar" aria-label="Main navigation">
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

      <nav class="runtime-navigation" aria-label="Runtime and Lab">
        <span class="runtime-navigation__eyebrow">RUNTIME</span>
        <router-link class="runtime-navigation__link" to="/runtime/workers">
          <el-icon><Collection /></el-icon>
          <span>Workers</span>
        </router-link>
        <router-link class="runtime-navigation__link" to="/runtime/tasks">
          <el-icon><Tickets /></el-icon>
          <span>Tasks</span>
        </router-link>

        <span class="runtime-navigation__eyebrow runtime-navigation__eyebrow--section">
          LAB
        </span>
        <router-link class="runtime-navigation__link" to="/runtime/task-batches">
          <el-icon><VideoPlay /></el-icon>
          <span>Task Batches</span>
        </router-link>

        <span class="runtime-navigation__eyebrow runtime-navigation__eyebrow--section">
          REFERENCE
        </span>
        <a class="runtime-navigation__link" href="/scalar">
          <el-icon><Link /></el-icon>
          <span>API Docs</span>
        </a>
        <a class="runtime-navigation__link" href="/overview.htm">
          <el-icon><Document /></el-icon>
          <span>Architecture</span>
        </a>
      </nav>

      <div class="runtime-sidebar__note">
        <span class="runtime-sidebar__note-title">
          <el-icon aria-hidden="true"><View /></el-icon>
          Runtime + Lab
        </span>
        <p>
          Runtime truth remains read-only. Finite Tasks are browser-session Mock; Task
          Batch Lab uploads input, appends Items and keeps generated output files.
        </p>
      </div>
    </aside>

    <div class="runtime-stage">
      <header class="runtime-topbar">
        <div class="runtime-mobile-brand">
          <img src="/logo.svg" alt="" width="28" height="28" />
          <strong>XA MASS</strong>
        </div>
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>{{ pageSection }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
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
            :aria-label="theme.dark ? 'Switch to light mode' : 'Switch to dark mode'"
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
