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
