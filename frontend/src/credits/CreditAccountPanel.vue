<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { can } from "@/access-policy";
import { captureTaskCaller, consoleIdentity, identityLabel } from "@/console-identity";
import { consoleMockKey } from "@/console-mock";
import { creditSourceKey } from "./source";
import { createCreditWorkspace } from "./workspace";

const props = defineProps<{ userId: string }>();
const emit = defineEmits<{ unsettled: [value: boolean] }>();
const caller = captureTaskCaller();
const generation = consoleIdentity.generation.value;
const source = inject(creditSourceKey);
if (!source) throw new Error("积分数据源不可用");
const mock = inject(consoleMockKey, undefined);
const workspace = createCreditWorkspace(
  source,
  caller,
  props.userId,
  () => consoleIdentity.generation.value === generation
);
const { account, entries, nextCursor, pending, loading, busy, error, notice } =
  workspace;
const manage = can(caller, "credits.manage");
const direction = ref("add"),
  amount = ref(""),
  reason = ref(""),
  loseResponse = ref(false);
const delta = computed(
  () => (direction.value === "add" ? 1 : -1) * Number(amount.value)
);
watch(
  () => busy.value || !!pending.value,
  (value) => emit("unsettled", value)
);
watch(workspace.confirmed, (outcome) => {
  if (outcome) {
    amount.value = "";
    reason.value = "";
  }
});
const expected = computed(() => {
  if (!amount.value || !Number.isSafeInteger(delta.value) || !account.value) return "—";
  const result = BigInt(account.value.balance) + BigInt(delta.value);
  return result < 0n || result > BigInt(Number.MAX_SAFE_INTEGER)
    ? "超出可用范围"
    : result.toString();
});
async function submit() {
  if (
    !/^\d+$/.test(amount.value) ||
    !Number.isSafeInteger(+amount.value) ||
    +amount.value <= 0
  ) {
    error.value = "积分数额须为正的安全整数";
    return;
  }
  if (!reason.value.trim() || reason.value.trim().length > 500) {
    error.value = "请填写 1～500 字的调账原因";
    return;
  }
  if (busy.value || pending.value) return;
  if (loseResponse.value && mock) {
    mock.credits.dropNextResponse(caller);
    loseResponse.value = false;
  }
  await workspace.submit(delta.value, reason.value);
  if (
    consoleIdentity.generation.value === generation &&
    !pending.value &&
    !error.value
  ) {
    amount.value = "";
    reason.value = "";
  }
}
const time = (value: number | null) =>
  value === null ? "尚无变更" : new Date(value).toLocaleString();
onMounted(() => void workspace.load());
onBeforeUnmount(() => workspace.dispose());
</script>
<template>
  <section class="credits-panel" :aria-label="`积分账户 ${userId}`">
    <header class="credits-panel__heading">
      <div>
        <h2>
          {{ identityLabel(userId) }}
          <small v-if="identityLabel(userId) !== userId">{{ userId }}</small>
        </h2>
        <p>查看余额和每笔积分变更。</p>
      </div>
      <el-button :loading="loading" :disabled="busy" @click="workspace.load()"
        >刷新账户</el-button
      >
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <el-alert
      v-if="notice"
      :title="notice"
      type="info"
      :closable="false"
      role="status"
    />
    <div v-if="account" class="credits-panel__balance">
      <span>当前余额</span
      ><strong data-testid="credit-balance">{{
        account.balance.toLocaleString()
      }}</strong>
      <span
        >最后变更：{{ time(account.updatedAtMillis) }} · 最新流水 #{{
          account.latestSequence
        }}</span
      >
    </div>
    <el-alert
      v-if="account && !account.userPresent"
      title="该用户已不在当前目录中，保留账户仅可查询。"
      type="warning"
      :closable="false"
    />
    <div v-if="pending && !busy" class="credits-panel__pending" role="status">
      <h3>核对原调账请求</h3>
      <p>
        请求标识：<code>{{ pending.requestId }}</code>
      </p>
      <p>
        积分变化：{{ pending.delta > 0 ? "+" : "" }}{{ pending.delta }} · 原因：{{
          pending.reason
        }}
      </p>
      <el-button :disabled="busy" @click="workspace.reconcile">核对结果</el-button>
      <el-button :disabled="busy" @click="workspace.retry">使用原请求重试</el-button>
    </div>
    <form
      v-if="manage && account?.userPresent"
      class="credits-panel__form"
      @submit.prevent="submit"
    >
      <h3>人工调账 · {{ userId }}</h3>
      <fieldset :disabled="busy || !!pending">
        <label
          >方向<select v-model="direction" aria-label="调账方向">
            <option value="add">增加积分</option>
            <option value="subtract">减少积分</option>
          </select></label
        >
        <label
          >积分数额<input
            v-model="amount"
            inputmode="numeric"
            aria-label="积分数额"
            placeholder="请输入正整数"
        /></label>
        <label class="credits-panel__reason"
          >原因<textarea
            v-model="reason"
            aria-label="调账原因"
            maxlength="500"
            rows="2"
            required
          />
        </label>
        <p>预计余额：{{ expected }}，以 Server 实际校验为准。</p>
        <label v-if="mock && can(caller, 'demo.control')" class="credits-panel__demo"
          ><input
            v-model="loseResponse"
            type="checkbox"
          />Mock：提交后丢弃响应一次</label
        >
        <el-button
          type="primary"
          native-type="submit"
          :loading="busy"
          :disabled="busy || !!pending"
          >提交调账</el-button
        >
      </fieldset>
    </form>
    <div class="credits-panel__heading">
      <h3>变更记录</h3>
      <span>按流水序号倒序 · 每页 50 条</span>
    </div>
    <el-table
      :data="entries"
      row-key="sequence"
      aria-label="积分变更记录"
      empty-text="暂无积分变更"
    >
      <el-table-column prop="sequence" label="流水" width="65" />
      <el-table-column label="积分变化" width="100"
        ><template #default="{ row }"
          >{{ row.delta > 0 ? "+" : "" }}{{ row.delta }}</template
        ></el-table-column
      >
      <el-table-column prop="balanceBefore" label="变更前" min-width="85" />
      <el-table-column prop="balanceAfter" label="变更后" min-width="85" />
      <el-table-column prop="actorUserId" label="操作者" min-width="85" />
      <el-table-column prop="reason" label="原因" min-width="160" />
      <el-table-column label="时间" min-width="170"
        ><template #default="{ row }">{{
          time(row.createdAtMillis)
        }}</template></el-table-column
      >
      <el-table-column prop="requestId" label="请求标识" min-width="300" />
    </el-table>
    <div>
      <el-button :disabled="loading || busy" @click="workspace.load()"
        >回到第一页</el-button
      ><el-button :disabled="!nextCursor || loading || busy" @click="workspace.nextPage"
        >下一页</el-button
      >
    </div>
  </section>
</template>
<style scoped>
.credits-panel {
  display: grid;
  gap: 18px;
}
.credits-panel__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
h2,
h3,
p {
  margin: 0;
}
h2 small {
  color: var(--rv-muted);
  font-size: 14px;
}
p {
  margin-top: 8px;
}
.credits-panel__heading p,
.credits-panel__heading span,
.credits-panel__balance span {
  color: var(--rv-muted);
}
.credits-panel__balance {
  display: grid;
  gap: 8px;
  padding: 24px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}
.credits-panel__balance strong {
  font-size: 34px;
}
.credits-panel__form,
.credits-panel__pending {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 18px;
}
.credits-panel__pending code {
  overflow-wrap: anywhere;
}
.credits-panel__pending p {
  margin-bottom: 12px;
}
fieldset {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 16px;
  border: 0;
  margin: 12px 0 0;
  padding: 0;
}
label {
  display: grid;
  gap: 6px;
}
input,
select,
textarea {
  font: inherit;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  padding: 8px;
}
.credits-panel__reason,
fieldset p,
.credits-panel__demo {
  flex-basis: 100%;
}
.credits-panel__demo {
  display: flex;
  align-items: center;
}
fieldset:disabled {
  opacity: 0.65;
}
</style>
