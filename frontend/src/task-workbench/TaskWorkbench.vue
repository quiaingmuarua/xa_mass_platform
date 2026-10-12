<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useTaskSession } from "./session";
import RpcTaskPage from "./RpcTaskPage.vue";
import FiniteTaskPage from "./FiniteTaskPage.vue";
import "./workbench.css";
const route = useRoute();
const router = useRouter();
const session = useTaskSession();
const tab = computed(() => (route.query.tab === "finite" ? "finite" : "rpc"));
const defaultProject = computed(() =>
  session.directory.value?.find((p) => p.projectId === "default")
);
let disposed = false;
onMounted(async () => {
  await session.ensureDirectory();
  if (!disposed && route.query.tab !== "finite" && route.query.tab !== "rpc")
    await router.replace({ query: { tab: "rpc" } });
});
onBeforeUnmount(() => {
  disposed = true;
});
function selectTab(value: string | number) {
  if (value === tab.value) return;
  return router.push({
    query:
      value === "rpc"
        ? { tab: "rpc", group: session.navigation.group || undefined }
        : { tab: "finite" }
  });
}
</script>
<template>
  <section class="task-workbench" data-testid="task-workbench">
    <header>
      <h1>任务工作台</h1>
      <el-tag effect="plain">Project · default</el-tag>
    </header>
    <el-tabs :model-value="tab" @tab-change="selectTab"
      ><el-tab-pane label="RPC 调用" name="rpc" /><el-tab-pane
        label="有限任务"
        name="finite"
    /></el-tabs>
    <el-alert
      v-if="session.directory.error"
      :title="
        (session.directory.stale ? '目录读取失败，保留旧快照：' : '') +
        session.directory.error
      "
      type="error"
      :closable="false"
      ><el-button @click="session.loadDirectory">重读 Project 目录</el-button></el-alert
    >
    <p v-if="!session.directory.value" class="raw-muted">
      {{
        session.directory.loading ? "正在读取 Project 目录…" : "Project 目录不可用。"
      }}
    </p>
    <el-alert
      v-else-if="!defaultProject"
      title="当前环境未配置 default Project，任务工作台不可用。"
      type="info"
      :closable="false"
      ><el-button @click="session.loadDirectory">刷新目录</el-button></el-alert
    >
    <template v-else>
      <RpcTaskPage v-if="tab === 'rpc'" :project="defaultProject" />
      <FiniteTaskPage v-else :project="defaultProject" />
    </template>
  </section>
</template>
