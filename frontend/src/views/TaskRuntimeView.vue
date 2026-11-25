<script setup lang="ts">
import { computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useTaskSession } from "@/task-workbench/session";
import RpcTaskPage from "@/task-workbench/RpcTaskPage.vue";
import FiniteTaskPage from "@/task-workbench/FiniteTaskPage.vue";
import GlobalRuntimeTasksPanel from "@/task-workbench/GlobalRuntimeTasksPanel.vue";
import "@/task-workbench/workbench.css";

const route = useRoute();
const router = useRouter();
const session = useTaskSession();

const tab = computed<"rpc" | "finite" | "global">(() => {
  if (route.query.tab === "global" || route.query.view === "global") return "global";
  if (route.query.tab === "finite") return "finite";
  return "rpc";
});
const requestedProject = computed(() =>
  typeof route.query.project === "string" ? route.query.project : ""
);
const projectId = computed(
  () =>
    requestedProject.value ||
    session.directory.value?.find((p) => p.projectId === session.navigation.projectId)
      ?.projectId ||
    session.directory.value?.find((p) => p.projectId === "default")?.projectId ||
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
  () => [
    route.query.project,
    route.query.tab,
    route.query.view,
    session.directory.value
  ],
  () => {
    if (route.path !== "/runtime/tasks") return;
    if (tab.value === "global") {
      if (route.query.tab !== "global" || route.query.view === "global") {
        const nextQuery = {
          ...Object.fromEntries(
            Object.entries(route.query).filter(([key]) => key !== "view")
          ),
          tab: "global"
        };
        void router.replace({ query: nextQuery });
      }
      return;
    }
    if (!session.directory.value) return;
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
  return router.push({
    query: {
      project: id,
      tab: tab.value,
      ...(tab.value === "rpc" ? session.project(id).rpcQuery : {})
    }
  });
}

function selectTab(value: string | number) {
  if (value === tab.value) return;
  if (value === "global") return router.push({ query: { tab: "global" } });
  const targetProject = projectId.value;
  const base = targetProject ? { project: targetProject } : {};
  return router.push({
    query:
      value === "rpc"
        ? {
            ...base,
            tab: "rpc",
            ...(targetProject ? session.project(targetProject).rpcQuery : {})
          }
        : { ...base, tab: "finite" }
  });
}
</script>
<template>
  <section class="task-workbench" data-testid="task-runtime-page">
    <header class="tw-header">
      <h1>Runtime Tasks</h1>
      <div class="tw-header__context">
        <el-tag v-if="tab === 'global'" effect="plain">全局 · 跨 Project</el-tag>
        <el-select
          v-else-if="
            (session.directory.value?.length ?? 0) > 1 || (projectId && !project)
          "
          :model-value="projectId"
          aria-label="Runtime Tasks Project"
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
        <el-tag v-else-if="project" effect="plain">
          Project · {{ project.projectId }}
        </el-tag>
      </div>
    </header>

    <div class="tw-nav-bar">
      <el-tabs :model-value="tab" @tab-change="selectTab">
        <el-tab-pane label="RPC 任务" name="rpc" />
        <el-tab-pane label="有限任务" name="finite" />
        <el-tab-pane label="全局调度" name="global" />
      </el-tabs>
      <router-link
        v-if="tab === 'finite'"
        :to="{ path: '/storage/tasks', query: { project: projectId || undefined } }"
        class="history-entry tw-history-link"
      >
        历史任务
      </router-link>
    </div>

    <el-alert
      v-if="session.directory.error"
      :title="
        (session.directory.stale ? '目录读取失败，保留旧快照：' : '') +
        session.directory.error
      "
      type="error"
      :closable="false"
    >
      <el-button @click="session.loadDirectory">重读 Project 目录</el-button>
    </el-alert>

    <GlobalRuntimeTasksPanel v-if="tab === 'global'" />
    <p v-else-if="!session.directory.value" class="raw-muted">
      {{
        session.directory.loading ? "正在读取 Project 目录…" : "Project 目录不可用。"
      }}
    </p>
    <el-alert
      v-else-if="!session.directory.value.length || !project"
      :title="
        !session.directory.value.length
          ? '当前环境未配置 Project，按 Project 管理不可用；可切换至全局调度观察。'
          : `Project ${projectId} 不在当前目录中，请重新选择或切换全局调度。`
      "
      type="info"
      :closable="false"
    >
      <el-button @click="session.loadDirectory">刷新目录</el-button>
    </el-alert>
    <template v-else>
      <RpcTaskPage v-if="tab === 'rpc'" :key="project.projectId" :project="project" />
      <FiniteTaskPage v-else :key="project.projectId" :project="project" />
    </template>
  </section>
</template>
