<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from "vue";
import {
  createWorkerGroupBindingsLoader,
  createWorkerGroupBindingsState
} from "@/runtime-viewer/worker-group-bindings";
import type { RuntimeViewerConfig } from "@/runtime-viewer/types";

const props = defineProps<{ workerGroupId: string; config: RuntimeViewerConfig }>();
const bindings = createWorkerGroupBindingsState(
  createWorkerGroupBindingsLoader(props.config)
);
const state = computed(() => bindings.entry(props.workerGroupId));
const snapshot = computed(() => state.value.snapshot);
watch(
  () => props.workerGroupId,
  (group) => {
    void bindings.refresh(group);
  },
  { immediate: true }
);
onBeforeUnmount(bindings.dispose);
</script>

<template>
  <details class="group-bindings" data-testid="group-bindings">
    <summary>
      生效资源绑定
      <span>{{ config.mode === "mock" ? "Mock 演示" : "当前进程启动配置" }}</span>
    </summary>
    <div class="group-bindings__toolbar">
      <code>{{ workerGroupId }}</code>
      <span v-if="snapshot"
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
      <dl>
        <dt>已声明事件</dt>
        <dd>{{ snapshot.workerGroup?.eventCodes.join("、") || "未提供" }}</dd>
        <dt>已启用 Pool</dt>
        <dd>{{ snapshot.matching.pools.join("、") || "无" }}</dd>
        <dt>查询 → Pool</dt>
        <dd>
          <div v-for="fn in snapshot.matching.functions" :key="fn.name">
            <code>{{ fn.name }}</code> → {{ fn.poolName ?? "独立身份查询" }}
          </div>
        </dd>
      </dl>
      <div
        v-for="pool in snapshot.fixedWindowPools"
        :key="pool.poolName"
        class="group-bindings__rule"
      >
        <strong>{{ pool.poolName }} · 固定窗口资格</strong>
        <p>
          {{ pool.windowMillis }} ms / 每 Worker 观察阈值 {{ pool.maxCount }}；消费函数
          <code>{{ pool.functionName }}</code>
        </p>
        <p>
          补给读取 Platform Properties：<code>{{ pool.timestampProperty }}</code
          >、<code>{{ pool.countProperty }}</code>
        </p>
      </div>
      <p v-if="snapshot.fixedWindowPools.length === 0">未绑定具名窗口 Pool。</p>
      <div
        v-for="feedback in snapshot.feedbacks"
        :key="feedback.name"
        class="group-bindings__rule"
      >
        <strong>{{ feedback.name }} · 分配反馈</strong>
        <p>
          <code>{{ feedback.observationEventName }}</code> ·
          {{ feedback.messageEvents.join("、") }}
        </p>
        <p>
          窗口 {{ feedback.windowMillis }} ms，写入
          <code>{{ feedback.timestampProperty }}</code
          >、<code>{{ feedback.countProperty }}</code
          >；参数来自 {{ feedback.poolName }}
        </p>
      </div>
      <p v-if="snapshot.feedbacks.length === 0">
        未绑定自动分配反馈；这不代表 Pool 必然不可用。
      </p>
    </template>
    <p class="group-bindings__hint">
      绑定只说明已配置能力，不证明 Worker 在线、Handler 已安装或库存可用。分配反馈为
      best-effort 观察，不是成功执行数或严格配额。宿主配置重启生效；修改窗口长度须使用新
      Redis scope。
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
