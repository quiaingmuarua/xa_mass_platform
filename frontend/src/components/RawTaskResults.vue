<script setup lang="ts">
import { reactive, ref } from "vue";
import type { Snapshot } from "@/task-management/workspace";
import type { RawResultPreview } from "@/task-management/projects";
defineProps<{ state: Snapshot<RawResultPreview> }>();
defineEmits<{ refresh: [] }>();
const formatted = reactive(new Set<string>());
const feedback = ref("");
function display(id: string, payload: string) {
  if (formatted.has(id)) {
    try {
      return JSON.stringify(JSON.parse(payload), null, 2);
    } catch {
      /* Text remains opaque. */
    }
  }
  return payload;
}
async function copy(payload: string) {
  try {
    await navigator.clipboard.writeText(payload);
    feedback.value = "已复制原文";
  } catch {
    feedback.value = "复制失败，可选择原文后手动复制。";
  }
}
</script>
<template>
  <section class="raw-results" aria-label="原始结果预览">
    <header>
      <h3>原始结果预览</h3>
      <el-button :loading="state.loading" @click="$emit('refresh')">刷新结果</el-button>
    </header>
    <p class="raw-muted">
      当前预览 {{ state.value?.results.length ?? 0 }} 条，最多 100 条。{{
        state.value?.truncated ? "结果未完整展示。" : ""
      }}{{ state.readAt ? "读取于 " + new Date(state.readAt).toLocaleString() : "" }}
    </p>
    <el-alert
      v-if="state.error"
      :title="(state.readAt ? '读取失败，保留旧快照：' : '读取失败：') + state.error"
      type="error"
      :closable="false"
    />
    <p v-if="state.value && !state.value.results.length" class="raw-muted">
      当前预览没有结果，不代表任务执行失败。
    </p>
    <div class="raw-results__scroll">
      <article v-for="entry in state.value?.results ?? []" :key="entry.messageId">
        <header>
          <code>{{ entry.messageId }}</code
          ><el-tag
            :type="
              entry.result.status === 'failed'
                ? 'danger'
                : entry.result.status === 'succeeded'
                  ? 'success'
                  : 'info'
            "
            >{{
              { succeeded: "执行成功", failed: "执行失败", not_observed: "尚未观测" }[
                entry.result.status
              ]
            }}</el-tag
          >
        </header>
        <template v-if="entry.result.status === 'succeeded'">
          <pre>{{ display(entry.messageId, entry.result.opaqueResultPayload) }}</pre>
          <el-button size="small" @click="copy(entry.result.opaqueResultPayload)"
            >复制原文</el-button
          >
          <el-button
            size="small"
            @click="
              formatted.has(entry.messageId)
                ? formatted.delete(entry.messageId)
                : formatted.add(entry.messageId)
            "
            >{{
              formatted.has(entry.messageId) ? "显示原文" : "尝试格式化 JSON"
            }}</el-button
          >
        </template>
        <p v-else class="raw-muted">
          {{
            entry.result.status === "failed"
              ? "执行失败，没有成功结果内容。"
              : "尚未观测到结果，不能推断为失败。"
          }}
        </p>
        <details>
          <summary>原始输入与查询</summary>
          <pre v-if="entry.item">{{ JSON.stringify(entry.item, null, 2) }}</pre>
          <p v-else>Item 数据不可用。</p>
        </details>
      </article>
    </div>
    <p role="status" class="raw-muted">{{ feedback }}</p>
  </section>
</template>
<style scoped>
.raw-results {
  margin: 20px 0;
}
.raw-results header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.raw-results h3 {
  margin: 0;
}
.raw-results__scroll {
  max-height: 560px;
  overflow: auto;
}
.raw-results article {
  padding: 14px 0;
  border-bottom: 1px solid var(--el-border-color-light);
}
.raw-results code {
  overflow-wrap: anywhere;
  min-width: 0;
}
.raw-results pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 220px;
  overflow: auto;
  padding: 10px;
  background: var(--el-fill-color-light);
  font-family: ui-monospace, Consolas, monospace;
}
.raw-results details {
  margin-top: 10px;
}
.raw-results summary {
  cursor: pointer;
}
</style>
