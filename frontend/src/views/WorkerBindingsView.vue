<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRuntimeViewerConfig } from "@/runtime-context";
import {
  createWorkerGroupBindingsDirectoryLoader,
  type WorkerGroupBindingsDirectory
} from "@/runtime-viewer/worker-group-bindings";
import WorkerGroupBindings from "@/components/WorkerGroupBindings.vue";
const props = defineProps<{ requestedGroup?: string }>();
const emit = defineEmits<{ selectGroup: [group: string] }>();
const config = useRuntimeViewerConfig(),
  load = createWorkerGroupBindingsDirectoryLoader(config);
const directory = ref<WorkerGroupBindingsDirectory>(),
  loading = ref(false),
  error = ref("");
const knownId = ref("");
const rememberedGroup = ref(props.requestedGroup ?? "");
watch(
  () => props.requestedGroup,
  (value) => {
    if (value) rememberedGroup.value = value;
  }
);
const group = computed(() => props.requestedGroup || rememberedGroup.value);
const projects = computed(() => directory.value?.projects ?? []);
let request: AbortController | undefined;
async function refresh() {
  request?.abort();
  const current = new AbortController();
  request = current;
  loading.value = true;
  try {
    const value = await load(current.signal);
    if (request === current) {
      directory.value = value;
      if (!rememberedGroup.value) rememberedGroup.value = value.workerGroupIds[0] ?? "";
      error.value = "";
    }
  } catch {
    if (request === current) error.value = "目录读取失败；保留上次快照，已过期。";
  } finally {
    if (request === current) loading.value = false;
  }
}
onMounted(refresh);
onBeforeUnmount(() => {
  request?.abort();
  request = undefined;
});
function inspect() {
  if (knownId.value.trim()) emit("selectGroup", knownId.value.trim());
}
</script>
<template>
  <section aria-label="Group 资源绑定">
    <h1>Group 资源绑定</h1>
    <p>
      启动配置关联的 Group：Matching／窗口绑定与明确 Project
      成员的并集。不是完整注册目录，也不代表有在线 Worker。
    </p>
    <p v-if="directory">
      最近成功读取：{{ new Date(directory.readAt).toLocaleString() }} ·
      {{ config.mode === "mock" ? "Mock 演示" : "当前进程启动配置" }}
    </p>

    <section
      v-if="projects.length > 0"
      class="project-topology"
      aria-label="Project 资源拓扑"
      data-testid="project-topology"
    >
      <div class="project-topology__header">
        <h2>Project 资源拓扑（只读）</h2>
        <p>
          展示当前进程已声明的 Project 与其 WorkerGroup 绑定来源；后续动态 Project
          管理将复用同一资源视图。
        </p>
      </div>
      <div class="project-topology__grid">
        <article
          v-for="project in projects"
          :key="project.projectId"
          class="project-card"
        >
          <div class="project-card__title">
            <strong>{{ project.projectId }}</strong>
            <el-tag
              size="small"
              effect="plain"
              :type="project.exclusiveInputOwner ? 'warning' : 'info'"
            >
              {{
                project.exclusiveInputOwner
                  ? `Scenario 托管 (${project.exclusiveInputOwner})`
                  : "平台配置"
              }}
            </el-tag>
          </div>
          <div class="project-card__groups">
            <span
              v-if="project.workerGroupSource === 'registered'"
              class="project-card__muted"
            >
              允许使用全部已注册 WorkerGroup
            </span>
            <template v-else-if="project.workerGroupIds.length > 0">
              <button
                v-for="groupId in project.workerGroupIds"
                :key="groupId"
                type="button"
                class="project-group-chip"
                :class="{ 'is-active': group === groupId }"
                @click="emit('selectGroup', groupId)"
              >
                {{ groupId }}
              </button>
            </template>
            <span v-else class="project-card__muted">未声明明确 WorkerGroup</span>
          </div>
        </article>
      </div>
    </section>

    <div class="binding-navigation">
      <el-select
        :model-value="group"
        filterable
        aria-label="资源绑定 Group"
        placeholder="选择启动关联 Group"
        @change="emit('selectGroup', String($event))"
      >
        <el-option
          v-if="group && !directory?.workerGroupIds.includes(group)"
          :label="group + ' · 已知 ID'"
          :value="group"
        />
        <el-option
          v-for="id in directory?.workerGroupIds ?? []"
          :key="id"
          :label="id"
          :value="id"
        />
      </el-select>
      <el-button :loading="loading" @click="refresh">刷新绑定目录</el-button>
      <el-input
        v-model="knownId"
        aria-label="已知 Group ID"
        placeholder="按已知 Group ID 核对"
        @keyup.enter="inspect"
      />
      <el-button :disabled="!knownId.trim()" @click="inspect">核对 Group</el-button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!loading && directory?.workerGroupIds.length === 0">
      当前启动配置没有关联 Group；仍可按已知 ID 核对。
    </p>
    <template v-if="group">
      <WorkerGroupBindings :worker-group-id="group" :config="config" />
      <router-link :to="{ path: '/runtime/workers', query: { view: 'workers', group } }"
        >观察该 Group 的 Worker 与实际属性</router-link
      >
    </template>
  </section>
</template>
<style scoped>
.project-topology {
  margin: 16px 0 20px;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}
.project-topology__header h2 {
  margin: 0 0 4px;
  font-size: 16px;
}
.project-topology__header p {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.project-topology__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}
.project-card {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}
.project-card__title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.project-card__groups {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.project-card__muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.project-group-chip {
  padding: 2px 8px;
  border-radius: 4px;
  border: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  cursor: pointer;
}
.project-group-chip:hover,
.project-group-chip.is-active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.binding-navigation {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
}
.binding-navigation .el-select {
  width: 330px;
}
.binding-navigation .el-input {
  width: 280px;
}
[role="alert"] {
  color: var(--el-color-danger);
}
</style>
