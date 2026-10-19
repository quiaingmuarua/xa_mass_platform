<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import WorkerObservationView from "./WorkerObservationView.vue";
import WorkerBindingsView from "./WorkerBindingsView.vue";
const route = useRoute(),
  router = useRouter();
const view = computed(() => (route.query.view === "bindings" ? "bindings" : "workers"));
const group = computed(() =>
  typeof route.query.group === "string" ? route.query.group : undefined
);
function selectView(value: string | number) {
  return router.push({
    query: { view: String(value), ...(group.value ? { group: group.value } : {}) }
  });
}
function selectGroup(value: string) {
  return router.push({ query: { view: view.value, group: value } });
}
</script>
<template>
  <el-tabs :model-value="view" aria-label="Workers 工作区" @tab-change="selectView">
    <el-tab-pane label="Worker 观察" name="workers" />
    <el-tab-pane label="资源绑定" name="bindings" />
  </el-tabs>
  <KeepAlive>
    <WorkerBindingsView
      v-if="view === 'bindings'"
      :requested-group="group"
      @select-group="selectGroup"
    />
    <WorkerObservationView
      v-else
      :requested-group="group"
      @select-group="selectGroup"
    />
  </KeepAlive>
</template>
