<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import WorkerObservationView from "./WorkerObservationView.vue";
import WorkerGroupManagementView from "./WorkerGroupManagementView.vue";
import WorkerBindingsView from "./WorkerBindingsView.vue";

type WorkerWorkspaceView = "workers" | "groups" | "bindings";

const route = useRoute();
const router = useRouter();
const view = computed<WorkerWorkspaceView>(() => {
  if (route.query.view === "bindings") return "bindings";
  if (route.query.view === "groups") return "groups";
  return "workers";
});
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
function openGroupInView(targetView: WorkerWorkspaceView, workerGroupId: string) {
  return router.push({ query: { view: targetView, group: workerGroupId } });
}
</script>
<template>
  <el-tabs :model-value="view" aria-label="Workers 工作区" @tab-change="selectView">
    <el-tab-pane label="Worker 观察" name="workers" />
    <el-tab-pane label="Group 管理" name="groups" />
    <el-tab-pane label="资源绑定" name="bindings" />
  </el-tabs>
  <KeepAlive>
    <WorkerBindingsView
      v-if="view === 'bindings'"
      :requested-group="group"
      @select-group="selectGroup"
    />
    <WorkerGroupManagementView
      v-else-if="view === 'groups'"
      @open-workers="openGroupInView('workers', $event)"
      @open-bindings="openGroupInView('bindings', $event)"
    />
    <WorkerObservationView
      v-else
      :requested-group="group"
      @select-group="selectGroup"
    />
  </KeepAlive>
</template>
