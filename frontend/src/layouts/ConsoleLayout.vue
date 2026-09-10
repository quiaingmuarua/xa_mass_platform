<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, provide, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { Connection, Menu, Moon, Sunny, View } from "@element-plus/icons-vue";
import ConsoleNavigation from "./ConsoleNavigation.vue";

import { createSmsAvailability, smsAvailabilityKey } from "@/sms/availability";
import {
  createMessageAvailability,
  messageAvailabilityKey
} from "@/message-campaigns/availability";
import { useThemeStore } from "@/stores/theme";

const demo = import.meta.env.VITE_RUNTIME_DATA_SOURCE === "mock";
const sms = createSmsAvailability(demo);
provide(smsAvailabilityKey, sms);
const messages = createMessageAvailability(demo);
provide(messageAvailabilityKey, messages);
const mobileNavigation = ref(false);
onMounted(() => {
  void sms.load();
  void messages.load();
});
onBeforeUnmount(() => {
  sms.dispose();
  messages.dispose();
});
const theme = useThemeStore();
const route = useRoute();
watch(
  () => route.fullPath,
  () => {
    mobileNavigation.value = false;
  }
);
const referencePage = computed(() => route.meta.section === "Reference");
const runtimePage = computed(() => route.meta.section === "Runtime");
const sourceLabel = computed(() => {
  if (referencePage.value) return "Build reference";
  if (route.meta.section === "Business")
    return demo ? "Mock source" : `${String(route.meta.title)} API`;
  return demo ? "Mock source" : "API source";
});
const pageTitle = computed(() => String(route.meta.title ?? "Runtime"));
const pageSection = computed(() => String(route.meta.section ?? "Runtime"));

onMounted(() => theme.apply());
</script>

<template>
  <div class="runtime-shell">
    <aside class="runtime-sidebar" aria-label="Main navigation">
      <div class="runtime-brand">
        <img
          class="runtime-brand__logo"
          :src="'/logo.svg'"
          alt=""
          width="34"
          height="34"
        />
        <div>
          <strong>XA MASS</strong>
          <span>CONSOLE</span>
        </div>
      </div>

      <ConsoleNavigation />

      <div v-if="runtimePage" class="runtime-sidebar__note">
        <span class="runtime-sidebar__note-title">
          <el-icon aria-hidden="true"><View /></el-icon>
          Runtime API
        </span>
        <p>
          Runtime truth remains read-only. Task actions use public APIs and retain only
          browser-session diagnostic coordinates here.
        </p>
      </div>
    </aside>

    <el-drawer
      v-model="mobileNavigation"
      title="Navigation"
      direction="ltr"
      size="236px"
      class="console-navigation-drawer"
    >
      <ConsoleNavigation @navigate="mobileNavigation = false" />
    </el-drawer>

    <div class="runtime-stage">
      <header class="runtime-topbar">
        <el-button
          circle
          class="console-menu-toggle"
          aria-label="Open navigation"
          :aria-expanded="mobileNavigation"
          @click="mobileNavigation = true"
        >
          <el-icon><Menu /></el-icon>
        </el-button>
        <div class="runtime-mobile-brand">
          <img :src="'/logo.svg'" alt="" width="28" height="28" />
          <strong>XA MASS</strong>
        </div>
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>{{ pageSection }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="runtime-topbar__actions">
          <span
            class="source-badge"
            :class="{
              'source-badge--mock': runtimePage && demo
            }"
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

<style>
.el-button.console-menu-toggle {
  display: none;
}
.console-navigation-drawer {
  --el-drawer-bg-color: var(--rv-sidebar);
  color: #fff;
}
.console-navigation-drawer .el-drawer__body {
  padding: 0;
}
.console-navigation-drawer .el-drawer__header {
  color: #fff;
  margin-bottom: 0;
}
@media (max-width: 760px) {
  .el-button.console-menu-toggle {
    display: inline-flex;
    margin-right: 10px;
  }
}
</style>
