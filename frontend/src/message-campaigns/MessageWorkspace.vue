<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  api,
  confirmedCampaign,
  labels,
  MessageApiError,
  type Campaign,
  type Catalog,
  type Message,
  type Metrics,
  type Page
} from "./api";
import { useMessageAvailability } from "./availability";

const props = defineProps<{ catalog: Catalog }>();
const route = useRoute();
const router = useRouter();
const availability = useMessageAvailability();
const form = reactive({
  name: "",
  country: "CN",
  body: "",
  recipients: "",
  senderPhone: ""
});
const campaigns = ref<Campaign[]>([]);
const campaignTotal = ref(0);
const campaignPage = ref(1);
const detail = ref<Campaign>();
const messages = ref<Message[]>([]);
const messageTotal = ref(0);
const messagePage = ref(1);
const metrics = ref<Metrics>();
const error = ref("");
const submissionError = ref("");
const creating = ref(false);
const unknown = ref(false);
let requestId = crypto.randomUUID();
let disposed = false;
let timer: ReturnType<typeof setTimeout> | undefined;
let reading: AbortController | undefined;
let mutation: AbortController | undefined;
let generation = 0;
const selectedId = computed(() =>
  typeof route.params.id === "string" ? route.params.id : undefined
);
const metricsPage = computed(
  () => route.path.replace(/\/$/, "") === "/messages/metrics"
);
const recipients = computed(() =>
  form.recipients
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
);
const canSubmit = computed(
  () =>
    form.name.trim() &&
    form.body.trim() &&
    recipients.value.length > 0 &&
    recipients.value.length <= 1000 &&
    new Set(recipients.value).size === recipients.value.length
);
function checkRun(runId: string) {
  if (runId !== props.catalog.runId) {
    void availability.load(true);
    return false;
  }
  return true;
}
async function refresh() {
  if (disposed) return;
  clearTimeout(timer);
  reading?.abort();
  const controller = new AbortController();
  reading = controller;
  const current = ++generation;
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    if (metricsPage.value) {
      const value = await api<Metrics>("/metrics", undefined, controller.signal);
      if (!disposed && current === generation && checkRun(value.runId))
        metrics.value = value;
    } else if (selectedId.value) {
      const id = encodeURIComponent(selectedId.value);
      const [value, page] = await Promise.all([
        api<Campaign>(`/campaigns/${id}`, undefined, controller.signal),
        api<Page<Message>>(
          `/campaigns/${id}/messages?offset=${(messagePage.value - 1) * 30}&limit=30`,
          undefined,
          controller.signal
        )
      ]);
      if (!disposed && current === generation && checkRun(page.runId)) {
        detail.value = value;
        messages.value = page.items;
        messageTotal.value = page.total;
      }
    } else {
      const page = await api<Page<Campaign>>(
        `/campaigns?offset=${(campaignPage.value - 1) * 30}&limit=30`,
        undefined,
        controller.signal
      );
      if (!disposed && current === generation && checkRun(page.runId)) {
        campaigns.value = page.items;
        campaignTotal.value = page.total;
        if (unknown.value && page.items.some((c) => c.requestId === requestId)) {
          unknown.value = false;
          submissionError.value = "已在批次列表确认本次申请。";
          requestId = crypto.randomUUID();
        }
      }
    }
    if (current === generation) error.value = "";
  } catch (failure) {
    if (!disposed && current === generation)
      error.value = failure instanceof Error ? failure.message : "读取失败";
  } finally {
    clearTimeout(timeout);
    if (!disposed && current === generation)
      timer = setTimeout(() => void refresh(), 1000);
  }
}
async function createCampaign() {
  if (!canSubmit.value || creating.value || unknown.value) return;
  creating.value = true;
  submissionError.value = "";
  const controller = new AbortController();
  mutation = controller;
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await api<unknown>(
      "/campaigns",
      {
        requestId,
        name: form.name,
        country: form.country,
        body: form.body,
        recipientIds: recipients.value,
        ...(form.senderPhone.trim() ? { senderPhone: form.senderPhone.trim() } : {})
      },
      controller.signal
    );
    const result = confirmedCampaign(response, requestId);
    if (!disposed) {
      requestId = crypto.randomUUID();
      await router.push(`/messages/campaigns/${result.id}`);
    }
  } catch (failure) {
    if (!disposed) {
      unknown.value = !(
        failure instanceof MessageApiError &&
        failure.status >= 400 &&
        failure.status < 500
      );
      submissionError.value = unknown.value
        ? "本次申请结果未确认。请查看批次列表；页面不会自动重发。"
        : (failure as Error).message;
    }
  } finally {
    clearTimeout(timeout);
    if (!disposed) creating.value = false;
  }
}
watch(
  () => route.path,
  () => {
    detail.value = undefined;
    messages.value = [];
    messagePage.value = 1;
    void refresh();
  }
);
watch([messagePage, campaignPage], () => void refresh());
onMounted(() => void refresh());
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  clearTimeout(timer);
  reading?.abort();
  mutation?.abort();
});
</script>

<template>
  <div class="messages-workspace">
    <header class="messages-intro">
      <div>
        <p class="eyebrow">MESSAGE CAMPAIGNS</p>
        <h1>消息触达</h1>
        <p>批量发送，持续观察送达、阅读和最新回复。</p>
      </div>
      <el-button @click="refresh">刷新</el-button>
    </header>
    <nav class="messages-tabs" aria-label="Messages pages">
      <router-link to="/messages">工作台与批次</router-link>
      <router-link to="/messages/metrics">业务指标</router-link>
      <span v-if="selectedId">批次详情</span>
    </nav>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <template v-if="metricsPage">
      <section v-if="metrics" class="messages-card">
        <h2>本轮业务观察</h2>
        <div class="messages-stats">
          <div>
            批次<strong>{{ metrics.campaigns }}</strong>
          </div>
          <div>
            消息<strong>{{ metrics.messages }}</strong>
          </div>
          <div>
            提交未确认<strong>{{ metrics.submissionUnknown }}</strong>
          </div>
          <div>
            观察错误<strong>{{ metrics.observationErrors }}</strong>
          </div>
        </div>
        <p>
          发送观察 P95 / P99：{{ metrics.sendingLatencyMillis.p95 }} /
          {{ metrics.sendingLatencyMillis.p99 }} ms
        </p>
        <p>
          回执观察 P95 / P99：{{ metrics.receiptObservationLatencyMillis.p95 }} /
          {{ metrics.receiptObservationLatencyMillis.p99 }} ms
        </p>
        <p v-for="(count, status) in metrics.statuses" :key="status">
          {{ labels[status] ?? status }}：{{ count }}
        </p>
      </section>
    </template>
    <template v-else-if="selectedId">
      <section v-if="detail" class="messages-card">
        <h2>{{ detail.name }}</h2>
        <p>
          {{ labels[detail.submission] ?? detail.submission }} · {{ detail.country }} ·
          {{ detail.messageCount }} 条消息
        </p>
        <p class="message-body">{{ detail.body }}</p>
        <div class="status-chips">
          <el-tag v-for="(count, status) in detail.statuses" :key="status"
            >{{ labels[status] ?? status }} {{ count }}</el-tag
          >
        </div>
        <p class="muted">
          发送结果与后续回执分别观察。缺少回执不代表未读；本轮持续更新最新回复。
        </p>
      </section>
      <section class="messages-card">
        <h2>收件人与最新回复</h2>
        <el-table :data="messages" empty-text="尚未观察到消息">
          <el-table-column prop="recipientId" label="收件人" min-width="150" />
          <el-table-column label="观察状态" min-width="180"
            ><template #default="{ row }">{{
              labels[row.status] ?? row.status
            }}</template></el-table-column
          >
          <el-table-column prop="phone" label="实际发送号码" min-width="180" />
          <el-table-column prop="workerId" label="执行 Worker" min-width="180" />
          <el-table-column prop="reply" label="最新回复" min-width="220" />
        </el-table>
        <el-pagination
          v-model:current-page="messagePage"
          :page-size="30"
          :total="messageTotal"
          layout="prev, pager, next"
        />
      </section>
    </template>
    <template v-else>
      <section class="messages-card">
        <h2>创建发送批次</h2>
        <el-form label-position="top" @submit.prevent="createCampaign">
          <div class="messages-fields">
            <el-form-item label="批次名称"
              ><el-input v-model="form.name" maxlength="128" aria-label="批次名称"
            /></el-form-item>
            <el-form-item label="发送国家"
              ><el-select v-model="form.country" aria-label="发送国家"
                ><el-option
                  v-for="country in catalog.countries"
                  :key="country.id"
                  :label="country.id"
                  :value="country.id" /></el-select
            ></el-form-item>
            <el-form-item label="发送号码（可选）"
              ><el-input
                v-model="form.senderPhone"
                placeholder="留空由平台选择"
                aria-label="发送号码"
            /></el-form-item>
          </div>
          <div class="messages-fields">
            <el-form-item label="消息正文"
              ><el-input
                v-model="form.body"
                type="textarea"
                :rows="4"
                maxlength="4096"
                aria-label="消息正文"
            /></el-form-item>
            <el-form-item :label="`收件人（每行一个，${recipients.length}/1000）`"
              ><el-input
                v-model="form.recipients"
                type="textarea"
                :rows="4"
                aria-label="收件人"
                placeholder="user-001&#10;user-002"
            /></el-form-item>
          </div>
          <el-button
            native-type="submit"
            type="primary"
            :loading="creating"
            :disabled="!canSubmit || unknown"
            >创建批次</el-button
          >
          <p v-if="submissionError" role="status">{{ submissionError }}</p>
        </el-form>
      </section>
      <section class="messages-card">
        <h2>本轮批次</h2>
        <el-table :data="campaigns" empty-text="创建第一个消息批次">
          <el-table-column label="批次" min-width="170"
            ><template #default="{ row }"
              ><router-link :to="`/messages/campaigns/${row.id}`">{{
                row.name
              }}</router-link></template
            ></el-table-column
          >
          <el-table-column prop="country" label="国家" width="80" />
          <el-table-column prop="messageCount" label="消息数" width="90" />
          <el-table-column label="提交" min-width="180"
            ><template #default="{ row }">{{
              labels[row.submission] ?? row.submission
            }}</template></el-table-column
          >
        </el-table>
        <el-pagination
          v-model:current-page="campaignPage"
          :page-size="30"
          :total="campaignTotal"
          layout="prev, pager, next"
        />
      </section>
    </template>
  </div>
</template>
<style scoped>
.messages-workspace {
  display: grid;
  gap: 20px;
  min-width: 0;
}
.messages-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.messages-intro h1 {
  margin: 6px 0;
  font-size: 28px;
}
.messages-intro p,
.muted {
  color: var(--rv-text-secondary);
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 0.15em;
}
.messages-tabs,
.status-chips {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.messages-tabs a {
  color: var(--rv-text-secondary);
  text-decoration: none;
  padding: 8px 0;
}
.messages-tabs a.router-link-exact-active {
  color: var(--el-color-primary);
  border-bottom: 2px solid currentColor;
}
.messages-card {
  background: var(--rv-surface);
  border: 1px solid var(--rv-border);
  border-radius: 14px;
  padding: 24px;
  min-width: 0;
}
.messages-card h2 {
  font-size: 17px;
  margin: 0 0 18px;
}
.messages-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}
.messages-fields > * {
  flex: 1;
  min-width: 200px;
}
.messages-stats {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
}
.messages-stats strong {
  display: block;
  font-size: 28px;
  margin: 8px 0;
}
.message-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.el-pagination {
  margin-top: 18px;
}
@media (max-width: 600px) {
  .messages-card {
    padding: 16px;
  }
  .messages-fields {
    display: block;
  }
}
</style>
