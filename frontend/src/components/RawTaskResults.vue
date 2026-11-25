<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import type { Snapshot } from "@/task-management/workspace";
import type { RawResultPreview } from "@/task-management/projects";
import { formatDateTime, presentResultStatus } from "@/task-workbench/presentation";

const props = defineProps<{ state: Snapshot<RawResultPreview> }>();
defineEmits<{ refresh: [] }>();

const formatted = reactive(new Set<string>());
const feedback = ref("");
const statusFilter = ref<"all" | "succeeded" | "failed" | "not_observed">("all");
const search = ref("");

const entries = computed(() => props.state.value?.results ?? []);

const counts = computed(() => {
  const summary = {
    all: entries.value.length,
    succeeded: 0,
    failed: 0,
    not_observed: 0
  };
  for (const entry of entries.value) {
    if (entry.result.status === "succeeded") summary.succeeded++;
    else if (entry.result.status === "failed") summary.failed++;
    else summary.not_observed++;
  }
  return summary;
});

const filteredEntries = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  return entries.value.filter((entry) => {
    if (statusFilter.value !== "all" && entry.result.status !== statusFilter.value)
      return false;
    if (!keyword) return true;
    return entry.messageId.toLowerCase().includes(keyword);
  });
});

function itemContext(item: RawResultPreview["results"][number]["item"]) {
  if (!item) return null;
  const eventCode = typeof item.eventCode === "string" ? item.eventCode : null;
  const selector = item.workerSelector;
  const executorName =
    selector &&
    typeof selector === "object" &&
    !Array.isArray(selector) &&
    typeof selector.executorName === "string"
      ? selector.executorName
      : null;
  if (!eventCode && !executorName) return null;
  return { eventCode, executorName };
}

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
    <header class="raw-results__head">
      <div>
        <h3>原始结果预览</h3>
        <p class="raw-muted">
          当前预览 {{ entries.length }} 条（最多 100 条）{{
            state.value?.truncated ? " · 结果未完整展示" : ""
          }}{{ state.readAt ? " · 读取于 " + formatDateTime(state.readAt) : "" }}
        </p>
      </div>
      <el-button size="small" :loading="state.loading" @click="$emit('refresh')">
        刷新结果
      </el-button>
    </header>

    <el-alert
      v-if="state.error"
      :title="(state.readAt ? '读取失败，保留旧快照：' : '读取失败：') + state.error"
      type="error"
      :closable="false"
    />

    <div v-if="entries.length" class="raw-results__toolbar">
      <div class="raw-results__pills" role="group" aria-label="预览状态筛选">
        <button
          type="button"
          class="raw-results__pill"
          :class="{ 'is-active': statusFilter === 'all' }"
          @click="statusFilter = 'all'"
        >
          全部 <strong>{{ counts.all }}</strong>
        </button>
        <button
          type="button"
          class="raw-results__pill"
          :class="{ 'is-active': statusFilter === 'succeeded' }"
          @click="statusFilter = 'succeeded'"
        >
          成功 <strong>{{ counts.succeeded }}</strong>
        </button>
        <button
          type="button"
          class="raw-results__pill"
          :class="{ 'is-active': statusFilter === 'failed' }"
          @click="statusFilter = 'failed'"
        >
          失败 <strong>{{ counts.failed }}</strong>
        </button>
        <button
          type="button"
          class="raw-results__pill"
          :class="{ 'is-active': statusFilter === 'not_observed' }"
          @click="statusFilter = 'not_observed'"
        >
          未观测 <strong>{{ counts.not_observed }}</strong>
        </button>
      </div>
      <el-input
        v-model="search"
        size="small"
        clearable
        placeholder="按 messageId 筛选当前预览"
        aria-label="筛选结果 messageId"
      />
    </div>

    <p v-if="state.value && !entries.length" class="raw-muted">
      当前预览没有结果，不代表任务执行失败。
    </p>
    <p v-else-if="entries.length && !filteredEntries.length" class="raw-muted">
      当前筛选条件下无匹配预览记录。
    </p>

    <div class="raw-results__scroll">
      <article v-for="entry in filteredEntries" :key="entry.messageId">
        <header>
          <div class="raw-results__identity">
            <code>{{ entry.messageId }}</code>
            <template v-if="itemContext(entry.item)">
              <span v-if="itemContext(entry.item)?.eventCode" class="raw-results__chip">
                {{ itemContext(entry.item)?.eventCode }}
              </span>
              <span
                v-if="itemContext(entry.item)?.executorName"
                class="raw-results__chip"
              >
                {{ itemContext(entry.item)?.executorName }}
              </span>
            </template>
          </div>
          <el-tag
            :type="presentResultStatus(entry.result.status).tone"
            effect="light"
            size="small"
          >
            {{ presentResultStatus(entry.result.status).label }}
          </el-tag>
        </header>
        <template v-if="entry.result.status === 'succeeded'">
          <pre>{{ display(entry.messageId, entry.result.opaqueResultPayload) }}</pre>
          <div class="raw-results__actions">
            <el-button size="small" @click="copy(entry.result.opaqueResultPayload)">
              复制原文
            </el-button>
            <el-button
              size="small"
              @click="
                formatted.has(entry.messageId)
                  ? formatted.delete(entry.messageId)
                  : formatted.add(entry.messageId)
              "
            >
              {{ formatted.has(entry.messageId) ? "显示原文" : "尝试格式化 JSON" }}
            </el-button>
          </div>
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
  display: grid;
  gap: 12px;
}
.raw-results__head,
.raw-results article > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.raw-results h3 {
  margin: 0 0 4px;
  font-size: 15px;
}
.raw-results__head p {
  margin: 0;
}
.raw-results__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}
.raw-results__toolbar .el-input {
  width: 220px;
}
.raw-results__pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.raw-results__pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--rv-text-secondary, var(--el-text-color-secondary));
  background: var(--rv-bg, var(--el-fill-color-light));
  border: 1px solid var(--rv-border, var(--el-border-color-light));
  border-radius: 999px;
  cursor: pointer;
}
.raw-results__pill.is-active {
  color: var(--rv-primary, var(--el-color-primary));
  background: var(--rv-primary-soft, var(--el-color-primary-light-9));
  border-color: color-mix(
    in srgb,
    var(--rv-primary, var(--el-color-primary)) 35%,
    transparent
  );
}
.raw-results__scroll {
  max-height: 520px;
  overflow: auto;
}
.raw-results article {
  padding: 14px 0;
  border-bottom: 1px solid var(--rv-border, var(--el-border-color-light));
}
.raw-results__identity {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}
.raw-results code {
  overflow-wrap: anywhere;
  min-width: 0;
  font-size: 12px;
}
.raw-results__chip {
  padding: 1px 6px;
  font-size: 11px;
  font-family: ui-monospace, Consolas, monospace;
  color: var(--rv-text-secondary, var(--el-text-color-secondary));
  background: var(--rv-bg, var(--el-fill-color-light));
  border: 1px solid var(--rv-border, var(--el-border-color-light));
  border-radius: 5px;
}
.raw-results pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 220px;
  overflow: auto;
  padding: 10px 12px;
  margin: 10px 0 8px;
  border-radius: 8px;
  border: 1px solid var(--rv-border, var(--el-border-color-light));
  background: var(--rv-bg, var(--el-fill-color-light));
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}
.raw-results__actions {
  display: flex;
  gap: 8px;
}
.raw-results details {
  margin-top: 8px;
}
.raw-results summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--rv-text-secondary, var(--el-text-color-secondary));
}
</style>
