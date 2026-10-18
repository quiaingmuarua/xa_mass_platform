<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, provide, ref, watch, inject } from "vue";
import { useRoute, useRouter } from "vue-router";
import { consoleIdentity } from "@/console-identity";
import { canUseConsole, roles } from "@/access-policy";
import { consoleMockKey } from "@/console-mock";
import { Connection, Menu, Moon, Sunny, View } from "@element-plus/icons-vue";
import ConsoleNavigation from "./ConsoleNavigation.vue";
import ConfigErrorView from "@/views/ConfigErrorView.vue";
import type { RuntimeViewerConfigError } from "@/runtime-viewer/types";

import { createSmsAvailability, smsAvailabilityKey } from "@/sms/availability";
import {
  createMessageAvailability,
  messageAvailabilityKey
} from "@/message-campaigns/availability";
import { useThemeStore } from "@/stores/theme";
import { messageTaskSourceKey } from "@/message-campaigns/task-source";
import { ApiMessageTaskSource } from "@/message-campaigns/api-task-source";
import { createMessageSession, messageSessionKey } from "@/message-campaigns/workbench";
import { appCheckContextKey, createAppCheckContext } from "@/app-checks/context";
import { ApiAppCheckTaskSource } from "@/app-checks/task-source";

defineProps<{ runtimeError?: RuntimeViewerConfigError }>();
const demo = import.meta.env.VITE_RUNTIME_DATA_SOURCE === "mock";
const identity = consoleIdentity.requireCurrent();
const mock = inject(consoleMockKey, undefined);
const appChecks = createAppCheckContext(
  demo ? mock!.appChecks : new ApiAppCheckTaskSource()
);
provide(appCheckContextKey, appChecks);
const sms = createSmsAvailability(demo);
provide(smsAvailabilityKey, sms);
const messages = createMessageAvailability(demo);
provide(messageAvailabilityKey, messages);
provide(
  messageTaskSourceKey,
  demo
    ? mock!.messages
    : new ApiMessageTaskSource(() =>
        messages.state.value.status === "enabled"
          ? messages.state.value.catalog
          : undefined
      )
);
provide(messageSessionKey, createMessageSession());
const mobileNavigation = ref(false);
onMounted(() => {
  if (canUseConsole(identity, "sms.use")) void sms.load();
  if (canUseConsole(identity, "messages.use")) void messages.load();
  if (canUseConsole(identity, "app-checks.tasks")) void appChecks.load();
});
onBeforeUnmount(() => {
  sms.dispose();
  messages.dispose();
  appChecks.dispose();
});
const theme = useThemeStore();
const route = useRoute();
const router = useRouter();
function logout() {
  consoleIdentity.logout();
  void router.replace({ name: "login" });
}
watch(
  () => route.fullPath,
  () => {
    mobileNavigation.value = false;
  }
);
const referencePage = computed(() => route.meta.section === "Reference");
const runtimePage = computed(() => route.meta.section === "Runtime");
const sourceLabel = computed(() => {
  if (route.path === "/") return "开发环境";
  if (referencePage.value) return "Build reference";
  if (route.meta.section === "Scenarios")
    return demo ? "Mock source" : `${String(route.meta.title)} API`;
  return demo ? "Mock source" : "API source";
});
const pageTitle = computed(() =>
  route.path === "/"
    ? route.query.view === "users"
      ? "用户管理"
      : route.query.view === "credits"
        ? "我的积分"
        : "控制台"
    : String(route.meta.title ?? "Runtime")
);
const pageSection = computed(() =>
  route.path === "/" ? "Console" : String(route.meta.section ?? "Runtime")
);

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
          Tasks 展示 Project 与全局原始观察。default 的 RPC
          和有限任务操作请使用任务工作台。
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
          <span class="console-current-user" data-testid="current-user"
            >{{ identity.displayName }} · {{ identity.userId }}</span
          >
          <span class="source-badge">{{ roles[identity.role].label }}</span>
          <el-button @click="logout">退出</el-button>
          <span
            class="source-badge"
            :class="{
              'source-badge--mock': !referencePage && demo
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
        <ConfigErrorView v-if="runtimeError" :error="runtimeError" />
        <slot v-else><router-view /></slot>
      </main>
    </div>
  </div>
</template>

<style>
.console-current-user {
  white-space: nowrap;
}
.runtime-topbar__actions .source-badge {
  flex-shrink: 0;
  white-space: nowrap;
}
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
