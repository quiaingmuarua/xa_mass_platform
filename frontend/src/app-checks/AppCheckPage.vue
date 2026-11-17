<script setup lang="ts">
import { useAppChecks } from "./context";
import AppCheckWorkspace from "./AppCheckWorkspace.vue";
import "./style.css";
import { consoleIdentity } from "@/console-identity";
import { useRoute } from "vue-router";
import TaskHistoryWorkspace from "@/task-history/TaskHistoryWorkspace.vue";
const route = useRoute();
const identity = consoleIdentity.requireCurrent();
const context = useAppChecks();
const state = context.state;
</script>
<template>
  <TaskHistoryWorkspace
    v-if="route.query.view === 'history'"
    project-id="app-checks"
    kind="app-checks"
    runtime-path="/app-checks"
  />
  <router-link v-else to="/app-checks?view=history" class="history-entry"
    >历史任务</router-link
  >
  <AppCheckWorkspace
    v-if="route.query.view !== 'history' && state.status === 'enabled'"
    :key="identity.userId"
    :source="context.source"
    :catalog="state.catalog"
    :session="context.sessionFor(identity.userId)"
    :configuration-read="context.configurationRead.value"
    @refresh-configuration="context.load(true)"
  />
  <section
    v-else-if="route.query.view !== 'history'"
    class="checks-surface checks-unavailable"
    aria-live="polite"
  >
    <h1>批量检查</h1>
    <p v-if="state.status === 'loading'">正在确认场景可用性…</p>
    <p v-else-if="state.status === 'disabled'">当前实例未启用批量检查。</p>
    <template v-else-if="state.status === 'unavailable'"
      ><p>{{ state.message }}</p>
      <el-button @click="context.load(true)">重新确认可用性</el-button></template
    >
  </section>
</template>
