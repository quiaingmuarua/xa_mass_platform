<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import {
  createWorkerGroupBindingsLoader,
  createWorkerGroupBindingsState
} from "@/runtime-viewer/worker-group-bindings";
import type { RuntimeViewerConfig } from "@/runtime-viewer/types";
const props = defineProps<{
  workerGroupId: string;
  config: RuntimeViewerConfig;
  compact?: boolean;
  eventCode?: string;
}>();
const opened = ref(!props.compact);
const bindings = createWorkerGroupBindingsState(
  createWorkerGroupBindingsLoader(props.config)
);
const state = computed(() => bindings.entry(props.workerGroupId));
const snapshot = computed(() => state.value.snapshot);
const selectedFeedback = computed(
  () =>
    snapshot.value?.fixedWindowPools.filter((pool) =>
      pool.feedback.messageEvents.includes(props.eventCode ?? "")
    ) ?? []
);
watch(
  () => [props.workerGroupId, opened.value] as const,
  ([group, open]) => {
    if (group && open) void bindings.refresh(group);
  },
  { immediate: true }
);
onBeforeUnmount(bindings.dispose);
</script>
<template>
  <details
    class="group-bindings"
    data-testid="group-bindings"
    :open="!compact"
    @toggle="opened = ($event.target as HTMLDetailsElement).open"
  >
    <summary>
      {{ compact ? "当前事件反馈与资源关系" : "Group 资源关系" }}
      <span>{{ config.mode === "mock" ? "Mock 演示" : "当前进程启动配置" }}</span>
    </summary>
    <div class="group-bindings__toolbar">
      <code>{{ workerGroupId }}</code
      ><span v-if="snapshot"
        >读取于 {{ new Date(snapshot.readAt).toLocaleString() }}</span
      >
      <el-button
        size="small"
        :loading="state.loading"
        @click="bindings.refresh(workerGroupId)"
        >刷新绑定</el-button
      >
    </div>
    <p v-if="state.error" role="alert" class="group-bindings__error">
      {{ state.error }}
    </p>
    <p v-if="state.stale && snapshot">当前显示上次成功读取的快照，已过期。</p>
    <p v-if="state.loading && !snapshot">正在读取资源绑定…</p>
    <template v-if="snapshot">
      <p v-if="snapshot.workerGroup === null">
        Group 注册描述不可用；以下仅为当前进程配置。
      </p>
      <template v-if="compact">
        <p v-if="!eventCode">
          完整 Item JSONL 的反馈按每项消息事件匹配；请在 Group 关系中核对事件列表。
        </p>
        <p v-else-if="selectedFeedback.length === 0">当前事件没有匹配的窗口反馈。</p>
        <p v-for="pool in selectedFeedback" :key="pool.poolName">
          {{ pool.feedback.observationEventName }} · {{ eventCode }} →
          {{ pool.timestampProperty }}、{{ pool.countProperty }} （{{
            pool.windowMillis
          }}
          ms，配套窗口 {{ pool.poolName }}）
        </p>
        <router-link
          :to="{
            path: '/runtime/workers',
            query: { view: 'bindings', group: workerGroupId }
          }"
          >查看当前 Group 资源关系</router-link
        >
      </template>
      <template v-else>
        <h3>Project 使用范围</h3>
        <ul v-if="snapshot.projects.length">
          <li v-for="project in snapshot.projects" :key="project.projectId">
            <code>{{ project.projectId }}</code> ·
            {{
              project.workerGroupSource === "declared"
                ? "明确声明此 Group"
                : "允许使用已注册 Group"
            }}
          </li>
        </ul>
        <p v-else>当前没有 Project 声明可使用此 Group。</p>
        <p class="group-bindings__hint">
          使用范围独立于 Pool 启用关系；这里不代表当前调用方的访问权限。
        </p>
        <h3>已声明事件</h3>
        <p>{{ snapshot.workerGroup?.eventCodes.join("、") || "未提供" }}</p>
        <h3>Pool 与查询</h3>
        <p>已启用 Pool：{{ snapshot.matching.pools.join("、") || "无" }}</p>
        <div v-for="fn in snapshot.matching.functions" :key="fn.name">
          <code>{{ fn.name }}</code> → {{ fn.poolName ?? "独立身份查询，不消费 Pool" }}
        </div>
        <div
          v-for="pool in snapshot.fixedWindowPools"
          :key="pool.poolName"
          class="group-bindings__rule"
        >
          <strong>{{ pool.poolName }} · 窗口资格与配套反馈</strong>
          <dl>
            <dt>资格参数</dt>
            <dd>{{ pool.windowMillis }} ms / 每 Worker 观察阈值 {{ pool.maxCount }}</dd>
            <dt>消费查询</dt>
            <dd>
              <code>{{ pool.functionName }}</code>
            </dd>
            <dt>补给读取属性</dt>
            <dd>{{ pool.timestampProperty }}、{{ pool.countProperty }}</dd>
            <dt>反馈观察点</dt>
            <dd>{{ pool.feedback.observationEventName }}</dd>
            <dt>匹配消息事件</dt>
            <dd>{{ pool.feedback.messageEvents.join("、") }}</dd>
            <dt>反馈写入属性</dt>
            <dd>
              {{ pool.timestampProperty }}、{{ pool.countProperty }}；使用同一窗口长度
            </dd>
          </dl>
        </div>
        <p v-if="!snapshot.fixedWindowPools.length">未绑定宿主窗口策略。</p>
        <p
          v-if="
            snapshot.matching.pools.some(
              (name) =>
                !snapshot!.fixedWindowPools.some((pool) => pool.poolName === name)
            )
          "
        >
          其他 Pool 的详细规则当前接口未提供。
        </p>
      </template>
    </template>
    <p class="group-bindings__hint">
      绑定不证明 Worker 在线、Handler 已安装或已入池。反馈按消息事件匹配，不是 Pool
      消费计数、成功执行数或严格额度。配置重启生效；修改窗口长度须使用新 Redis scope。
    </p>
  </details>
</template>
<style scoped>
.group-bindings {
  margin: 16px 0;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  padding: 12px;
}
summary {
  cursor: pointer;
  font-weight: 600;
}
summary span,
.group-bindings__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.group-bindings__toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 12px 0;
}
dl {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 8px;
  font-size: 13px;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.group-bindings__rule {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 12px;
  margin-top: 12px;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.group-bindings__rule p {
  margin: 6px 0;
}
.group-bindings__error {
  color: var(--el-color-danger);
}
</style>
