<script setup lang="ts">
import { computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useTaskSession } from "./session";
import RpcTaskPage from "./RpcTaskPage.vue";
import FiniteTaskPage from "./FiniteTaskPage.vue";
import "./workbench.css";
const route = useRoute();
const router = useRouter();
const session = useTaskSession();
const tab = computed(() => (route.query.tab === "finite" ? "finite" : "rpc"));
const requestedProject = computed(() =>
  typeof route.query.project === "string" ? route.query.project : ""
);
const projectId = computed(
  () =>
    requestedProject.value ||
    session.directory.value?.find((p) => p.projectId === session.navigation.projectId)
      ?.projectId ||
    session.directory.value?.[0]?.projectId ||
    ""
);
const project = computed(() =>
  session.directory.value?.find((p) => p.projectId === projectId.value)
);
onMounted(() => {
  void session.ensureDirectory();
});
watch(
  () => [route.query.project, route.query.tab, session.directory.value],
  () => {
    if (route.path !== "/task-workbench" || !session.directory.value) return;
    const directory = session.directory.value;
    const selected = projectId.value;
    if (!selected) return;
    if (directory.some((p) => p.projectId === selected))
      session.navigation.projectId = selected;
    if (requestedProject.value !== selected || route.query.tab !== tab.value)
      void router.replace({
        query: { ...route.query, project: selected, tab: tab.value }
      });
  },
  { immediate: true }
);
function selectProject(id: string) {
  if (id === projectId.value) return;
  const { group } = session.project(id).rpcQuery;
  return router.push({
    query: {
      project: id,
      tab: tab.value,
      ...(tab.value === "rpc" ? { group } : {})
    }
  });
}
function selectTab(value: string | number) {
  if (value === tab.value) return;
  return router.push({
    query:
      value === "rpc"
        ? {
            project: projectId.value,
            tab: "rpc",
            ...session.project(projectId.value).rpcQuery
          }
        : { project: projectId.value, tab: "finite" }
  });
}
</script>
<template>
  <section class="task-workbench" data-testid="task-workbench">
    <header>
      <h1>任务工作台</h1>
      <el-select
        v-if="(session.directory.value?.length ?? 0) > 1 || (projectId && !project)"
        :model-value="projectId"
        aria-label="工作台 Project"
        placeholder="选择 Project"
        @change="selectProject(String($event))"
      >
        <el-option
          v-for="p in session.directory.value ?? []"
          :key="p.projectId"
          :label="p.projectId"
          :value="p.projectId"
        />
      </el-select>
      <el-tag v-else-if="project" effect="plain"
        >Project · {{ project.projectId }}</el-tag
      >
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
      v-else-if="!session.directory.value.length || !project"
      :title="
        !session.directory.value.length
          ? '当前环境未配置 Project，任务工作台不可用。'
          : `Project ${projectId} 不在当前目录中，请重新选择。`
      "
      type="info"
      :closable="false"
      ><el-button @click="session.loadDirectory">刷新目录</el-button></el-alert
    >
    <template v-else>
      <RpcTaskPage v-if="tab === 'rpc'" :key="project.projectId" :project="project" />
      <FiniteTaskPage v-else :key="project.projectId" :project="project" />
    </template>
  </section>
</template>
