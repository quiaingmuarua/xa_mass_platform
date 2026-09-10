<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { Collection, Document, Link, Message, Tickets } from "@element-plus/icons-vue";
import { useSmsAvailability } from "@/sms/availability";
const emit = defineEmits<{ navigate: [] }>();
const route = useRoute();
const sms = useSmsAvailability();
const smsEnabled = computed(() => sms.state.value.status === "enabled");
</script>

<template>
  <nav
    class="runtime-navigation"
    aria-label="Main navigation"
    @click="emit('navigate')"
  >
    <span class="runtime-navigation__eyebrow">RUNTIME</span>
    <router-link class="runtime-navigation__link" to="/runtime/workers">
      <el-icon><Collection /></el-icon>
      <span>Workers</span>
    </router-link>
    <router-link class="runtime-navigation__link" to="/runtime/tasks">
      <el-icon><Tickets /></el-icon>
      <span>Tasks</span>
    </router-link>
    <template v-if="smsEnabled">
      <span class="runtime-navigation__eyebrow runtime-navigation__eyebrow--section"
        >BUSINESS</span
      >
      <router-link
        class="runtime-navigation__link"
        :class="{ 'router-link-active': route.meta.section === 'Business' }"
        :aria-current="route.meta.section === 'Business' ? 'page' : undefined"
        to="/sms"
      >
        <el-icon><Message /></el-icon><span>SMS</span>
      </router-link>
    </template>
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
  </nav>
  <div
    v-if="sms.state.value.status === 'unavailable'"
    class="runtime-sidebar__note"
    role="status"
  >
    <p>SMS 可用性未确认</p>
    <el-button size="small" @click="sms.load(true)">重试 SMS 可用性</el-button>
  </div>
</template>
