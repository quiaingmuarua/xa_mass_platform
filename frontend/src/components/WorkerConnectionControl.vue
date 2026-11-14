<script setup lang="ts">
import { computed, inject, ref, watch } from "vue";
import { workerConnectionControlKey } from "@/runtime-viewer/connection-control-state";
import JsonBlock from "./JsonBlock.vue";

const props = defineProps<{ workerGroupId: string; workerId: string }>();
const session = inject(workerConnectionControlKey, undefined);
const state = computed(() => session?.state(props.workerGroupId, props.workerId));
const snapshot = computed(() => state.value?.snapshot.value);
const pending = computed(() => state.value?.pending.value);
const until = ref("");
const reason = ref("");
const confirmation = ref<{
  group: string;
  worker: string;
  until: number | null;
  reason: string;
  discard?: boolean;
}>();
const time = (value: number | null | undefined) =>
  value == null ? "—" : new Date(value).toLocaleString();
function localInput(value: number) {
  const date = new Date(value);
  return new Date(value - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}
watch(
  () => [props.workerGroupId, props.workerId],
  () => {
    confirmation.value = undefined;
    until.value = localInput(
      pending.value?.cooldownUntilMillis ?? Date.now() + 86_400_000
    );
    reason.value = pending.value?.reason ?? "";
    void state.value?.refresh();
  },
  { immediate: true }
);

function propose(release: boolean) {
  if (!state.value?.canWrite.value) return;
  const deadline = release ? null : new Date(until.value).getTime();
  if (
    deadline !== null &&
    (!Number.isSafeInteger(deadline) || deadline <= Date.now())
  ) {
    state.value.error.value = "请选择未来的冷却截止时间";
    return;
  }
  confirmation.value = {
    group: props.workerGroupId,
    worker: props.workerId,
    until: deadline,
    reason: reason.value
  };
}
async function confirm() {
  const change = confirmation.value;
  if (
    !change ||
    change.group !== props.workerGroupId ||
    change.worker !== props.workerId
  )
    return;
  confirmation.value = undefined;
  if (change.discard) state.value?.discardPending();
  else await state.value?.submit(change.until, change.reason);
}

function proposeDiscard() {
  if (!state.value?.canDiscard.value || !pending.value) return;
  confirmation.value = {
    group: props.workerGroupId,
    worker: props.workerId,
    until: pending.value.cooldownUntilMillis,
    reason: pending.value.reason,
    discard: true
  };
}
</script>

<template>
  <section
    class="connection-control"
    aria-label="Worker 接入控制"
    data-testid="worker-connection-control"
  >
    <h2>接入控制</h2>
    <p>冷却会撤销连接并拒绝重新接入。调度暂停、Pool 资格和实际网络状态是独立信息。</p>
    <p v-if="!state || !state.permitted" role="alert">
      当前会话没有管理员接入控制权限。
    </p>
    <template v-else>
      <el-button
        :loading="state.loading.value"
        :disabled="state.busy.value || !!confirmation"
        data-testid="refresh-connection-control"
        @click="state.refresh()"
        >读取／核对接入状态</el-button
      >
      <p v-if="state.error.value" role="alert">{{ state.error.value }}</p>
      <p v-if="state.stale.value" role="alert">
        读取或提交未确认，仍保留原快照和读取时间。重新读取前不能修改。
      </p>
      <p v-if="state.notice.value" role="status">{{ state.notice.value }}</p>
      <dl v-if="snapshot">
        <dt>Server 接入决定</dt>
        <dd>
          {{ snapshot.status === "cooldown" ? "冷却中" : "允许接入" }}（读取时状态）
        </dd>
        <dt>冷却截止</dt>
        <dd>{{ time(snapshot.cooldownUntilMillis) }}</dd>
        <dt>原因</dt>
        <dd>{{ snapshot.reason || "—" }}</dd>
        <dt>Transport</dt>
        <dd>{{ snapshot.transportType || "未配置" }}</dd>
        <dt>读取时间</dt>
        <dd>{{ time(snapshot.readAt) }}</dd>
      </dl>
      <p v-if="snapshot && !snapshot.supported" role="alert">
        {{
          snapshot.unsupportedReason === "websocket_required"
            ? "此 Worker 不是 WebSocket，本片不支持其接入冷却。"
            : "绑定的 Adapter 未由当前运行中的 Runtime 管理，不能执行接入控制。"
        }}
      </p>
      <template v-if="pending">
        <h3>保留的原请求</h3>
        <p v-if="state.pendingStatus.value === 'conflict'">
          原请求冲突；重新读取后可明确放弃本地核对，不能静默覆盖。
        </p>
        <p v-if="state.pendingStatus.value === 'superseded'">
          已有更新修订，原请求保留且禁止重试。
        </p>
        <JsonBlock :value="pending" />
        <el-button
          :disabled="!state.canRetry.value"
          :loading="state.busy.value"
          data-testid="retry-connection-control"
          @click="state.retry()"
          >显式重试原请求</el-button
        >
        <el-button
          v-if="state.pendingStatus.value !== 'unconfirmed'"
          :disabled="!state.canDiscard.value"
          @click="proposeDiscard"
        >
          放弃本地核对并重新编辑
        </el-button>
        <p>
          重试使用相同身份、修订和绝对截止时间，不会延长冷却。读取到已保存状态不能单独证明旧连接已经关闭。
        </p>
      </template>
      <fieldset :disabled="!state.canWrite.value || !!confirmation">
        <legend>管理员修改</legend>
        <label
          >冷却截止时间
          <input v-model="until" type="datetime-local" aria-label="冷却截止时间"
        /></label>
        <label
          >原因 <el-input v-model="reason" maxlength="256" aria-label="冷却原因"
        /></label>
        <div class="connection-control__actions">
          <el-button
            :disabled="!state.canWrite.value || !!confirmation"
            @click="propose(false)"
            >设置／调整冷却</el-button
          >
          <el-button
            :disabled="!state.canWrite.value || !!confirmation"
            @click="propose(true)"
            >解除冷却</el-button
          >
        </div>
      </fieldset>
      <el-dialog
        :model-value="!!confirmation"
        title="确认 Worker 接入控制"
        width="min(520px, 95vw)"
        :close-on-click-modal="false"
        @close="confirmation = undefined"
      >
        <template v-if="confirmation">
          <p>Group：{{ confirmation.group }}</p>
          <p>Worker：{{ confirmation.worker }}</p>
          <p v-if="confirmation.discard">
            仅放弃本地保存的请求核对，不撤销 Server
            上可能发生的修改或断连，不声明旧请求没有执行。之后的新操作将使用已读取的修订。
          </p>
          <p v-else-if="confirmation.until !== null">
            冷却至 {{ time(confirmation.until) }}。将立即撤销路由并断开连接；已进入的
            Handler 可能继续执行，业务副作用无法撤销，结果可能未能返回。
          </p>
          <p v-else>
            只解除接入冷却。Worker 需要再次检查后才能连接；不会解除调度暂停，也不会改变
            Pool 规则。
          </p>
          <p>原因：{{ confirmation.reason || "—" }}</p>
        </template>
        <template #footer
          ><el-button @click="confirmation = undefined">取消</el-button
          ><el-button type="primary" @click="confirm">确认提交</el-button></template
        >
      </el-dialog>
    </template>
  </section>
</template>

<style scoped>
.connection-control {
  display: grid;
  gap: 12px;
}
.connection-control p {
  margin: 0;
  line-height: 1.6;
}
.connection-control dl {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: 8px;
}
.connection-control dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.connection-control fieldset {
  display: grid;
  gap: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
}
.connection-control label {
  display: grid;
  gap: 6px;
}
.connection-control__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
