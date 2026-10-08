<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import type { CheckDetail } from "./model";
import {
  cryptoAvailable,
  verificationLabels,
  verifyPreview,
  type Verification
} from "./verify";
const props = defineProps<{ detail: CheckDetail; busy: boolean }>();
const expanded = ref(false),
  verifying = ref(false),
  error = ref("");
const checks = shallowRef(new Map<string, Verification>());
const available = cryptoAvailable();
let generation = 0;
watch(
  () => props.detail,
  () => {
    generation++;
    verifying.value = false;
    checks.value = new Map();
    error.value = "";
  }
);
const summary = computed(() => {
  const result = { matched: 0, mismatch: 0, unavailable: 0 };
  for (const row of checks.value.values()) result[row.status]++;
  return result;
});
async function verify() {
  if (!available || verifying.value || props.busy) return;
  const token = ++generation;
  verifying.value = true;
  try {
    const result = await verifyPreview(props.detail);
    if (token === generation) checks.value = result;
  } catch (failure) {
    if (token === generation)
      error.value = failure instanceof Error ? failure.message : "核对失败";
  } finally {
    if (token === generation) verifying.value = false;
  }
}
onBeforeUnmount(() => {
  generation++;
});
</script>
<template>
  <section class="checks-diagnostics">
    <button class="checks-link" :aria-expanded="expanded" @click="expanded = !expanded">
      技术信息 <span aria-hidden="true">{{ expanded ? "⌃" : "⌄" }}</span>
    </button>
    <div v-if="expanded" class="checks-diagnostics-body">
      <slot />
      <dl class="checks-description">
        <div>
          <dt>Task ID</dt>
          <dd>{{ detail.task.taskId }}</dd>
        </div>
        <div>
          <dt>Worker Group</dt>
          <dd>{{ detail.task.workerGroupId || "—" }}</dd>
        </div>
        <div>
          <dt>原始状态</dt>
          <dd>{{ detail.task.state || "—" }}</dd>
        </div>
        <div>
          <dt>salt / 日期</dt>
          <dd>{{ detail.task.salt || "—" }} / {{ detail.task.saltDate || "—" }}</dd>
        </div>
      </dl>
      <p v-if="detail.task.configurationError" role="alert" class="checks-error">
        {{ detail.task.configurationError }}
      </p>
      <details>
        <summary>模拟配置</summary>
        <pre>{{ JSON.stringify(detail.task.simulation, null, 2) }}</pre>
      </details>
      <p class="checks-hint">
        仅核对当前最多 100 条预览的业务内容，不审计调度与重试次数。
      </p>
      <p v-if="!available" class="checks-hint">
        当前浏览器不支持 Web Crypto，请使用 HTTPS 或本机地址进行核对。
      </p>
      <el-button
        :disabled="!available || busy || !detail.results.length"
        :loading="verifying"
        @click="verify"
        >核对当前预览</el-button
      >
      <p v-if="error" role="alert" class="checks-error">{{ error }}</p>
      <p v-if="checks.size">
        复算一致 {{ summary.matched }} · 不一致 {{ summary.mismatch }} · 无法核对
        {{ summary.unavailable }}
      </p>
      <div class="checks-table-scroll" tabindex="0" aria-label="结果技术信息">
        <table class="checks-table">
          <thead>
            <tr>
              <th>Message ID</th>
              <th>实际 Worker</th>
              <th>模拟延迟</th>
              <th>复算</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in detail.results" :key="row.messageId">
              <td>{{ row.messageId }}</td>
              <td>{{ row.workerId || "—" }}</td>
              <td>{{ row.simulatedDelayMillis ?? "—" }}</td>
              <td>
                {{
                  checks.get(row.messageId)
                    ? verificationLabels[checks.get(row.messageId)!.status]
                    : "未核对"
                }}<small>{{ checks.get(row.messageId)?.reason }}</small>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>
