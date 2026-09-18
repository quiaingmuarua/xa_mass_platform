<script setup lang="ts">
import { useAppChecks } from "./context";
import AppCheckWorkspace from "./AppCheckWorkspace.vue";
import "./style.css";
const context = useAppChecks();
const state = context.state;
</script>
<template>
  <AppCheckWorkspace
    v-if="state.status === 'enabled'"
    :source="context.source"
    :catalog="state.catalog"
  />
  <section v-else class="checks-surface checks-unavailable" aria-live="polite">
    <h1>应用注册查询</h1>
    <p v-if="state.status === 'loading'">正在确认场景可用性…</p>
    <p v-else-if="state.status === 'disabled'">当前实例未启用应用注册查询。</p>
    <template v-else-if="state.status === 'unavailable'"
      ><p>{{ state.message }}</p>
      <el-button @click="context.load(true)">重新确认可用性</el-button></template
    >
  </section>
</template>
