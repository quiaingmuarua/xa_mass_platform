<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import {
  Collection,
  Document,
  Link,
  Message,
  ChatDotRound,
  Tickets,
  User
} from "@element-plus/icons-vue";
import { useSmsAvailability } from "@/sms/availability";
import { useMessageAvailability } from "@/message-campaigns/availability";
import { useAppChecks } from "@/app-checks/context";
import { consoleIdentity } from "@/console-identity";
import { canUseConsole, type Permission } from "@/access-policy";
const allowed = (permission: Permission) =>
  canUseConsole(consoleIdentity.current.value, permission);
const emit = defineEmits<{ navigate: [] }>();
const route = useRoute();
const sms = useSmsAvailability();
const smsEnabled = computed(() => sms.state.value.status === "enabled");
const messages = useMessageAvailability();
const appChecks = useAppChecks();
const appChecksEnabled = computed(() => appChecks.state.value.status === "enabled");
const messagesEnabled = computed(() =>
  ["enabled", "demo"].includes(messages.state.value.status)
);
</script>

<template>
  <nav
    class="runtime-navigation"
    aria-label="Main navigation"
    @click="emit('navigate')"
  >
    <template v-if="allowed('runtime.view')">
      <span class="runtime-navigation__eyebrow">RUNTIME</span>
      <router-link class="runtime-navigation__link" to="/runtime/workers">
        <el-icon><Collection /></el-icon>
        <span>Workers</span>
      </router-link>
      <router-link class="runtime-navigation__link" to="/runtime/tasks">
        <el-icon><Tickets /></el-icon>
        <span>Tasks</span>
      </router-link>
    </template>
    <template v-if="allowed('workbench.use')">
      <span class="runtime-navigation__eyebrow runtime-navigation__eyebrow--section"
        >WORKBENCH</span
      >
      <router-link class="runtime-navigation__link" to="/task-workbench">
        <el-icon><Tickets /></el-icon><span>任务工作台</span>
      </router-link>
    </template>
    <template
      v-if="
        (allowed('sms.use') && smsEnabled) ||
        (allowed('messages.use') && messagesEnabled) ||
        (allowed('app-checks.tasks') && appChecksEnabled)
      "
    >
      <span class="runtime-navigation__eyebrow runtime-navigation__eyebrow--section"
        >SCENARIOS</span
      >
      <router-link
        v-if="allowed('sms.use') && smsEnabled"
        class="runtime-navigation__link"
        :class="{ 'router-link-active': route.path.startsWith('/sms') }"
        :aria-current="route.path.startsWith('/sms') ? 'page' : undefined"
        to="/sms"
      >
        <el-icon><Message /></el-icon><span>SMS</span>
      </router-link>
      <router-link
        v-if="allowed('messages.use') && messagesEnabled"
        class="runtime-navigation__link"
        to="/messages"
        :class="{ 'router-link-active': route.path.startsWith('/messages') }"
        :aria-current="route.path.startsWith('/messages') ? 'page' : undefined"
      >
        <el-icon><ChatDotRound /></el-icon><span>Messages</span>
      </router-link>
      <router-link
        v-if="allowed('app-checks.tasks') && appChecksEnabled"
        class="runtime-navigation__link"
        to="/app-checks"
        :class="{ 'router-link-active': route.path.startsWith('/app-checks') }"
        :aria-current="route.path.startsWith('/app-checks') ? 'page' : undefined"
      >
        <el-icon><Tickets /></el-icon><span>批量检查</span>
      </router-link>
    </template>
    <template v-if="allowed('users.manage') || allowed('credits.view')">
      <span class="runtime-navigation__eyebrow runtime-navigation__eyebrow--section"
        >CONSOLE</span
      >
      <router-link
        v-if="allowed('users.manage')"
        class="runtime-navigation__link"
        :class="{
          'router-link-active': route.path === '/' && route.query.view === 'users'
        }"
        to="/?view=users"
      >
        <el-icon><User /></el-icon><span>用户管理</span>
      </router-link>
      <router-link
        v-if="allowed('credits.view')"
        class="runtime-navigation__link"
        :class="{
          'router-link-active': route.path === '/' && route.query.view === 'credits'
        }"
        to="/?view=credits"
      >
        <el-icon><Tickets /></el-icon><span>我的积分</span>
      </router-link>
    </template>
    <template v-if="allowed('reference.view')">
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
      <router-link class="runtime-navigation__link" to="/reference/error-codes">
        <el-icon><Collection /></el-icon>
        <span>Code Dictionary</span>
      </router-link>
    </template>
  </nav>
  <div
    v-if="allowed('sms.use') && sms.state.value.status === 'unavailable'"
    class="runtime-sidebar__note"
    role="status"
  >
    <p>SMS 可用性未确认</p>
    <el-button size="small" @click="sms.load(true)">重试 SMS 可用性</el-button>
  </div>
  <div
    v-if="allowed('messages.use') && messages.state.value.status === 'unavailable'"
    class="runtime-sidebar__note"
    role="status"
  >
    <p>Messages 可用性未确认</p>
    <el-button size="small" @click="messages.load(true)"
      >重试 Messages 可用性</el-button
    >
  </div>
  <div
    v-if="allowed('app-checks.tasks') && appChecks.state.value.status === 'unavailable'"
    class="runtime-sidebar__note"
    role="status"
  >
    <p>批量检查可用性未确认</p>
    <el-button size="small" @click="appChecks.load(true)">重试批量检查可用性</el-button>
  </div>
</template>
