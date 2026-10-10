<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { api, statusLabels, waitingMessage, type Reception, type Metrics } from "./api";
import { useSmsAvailability } from "./availability";

const route = useRoute();
const availability = useSmsAvailability();
const catalog = computed(() =>
  availability.state.value.status === "enabled"
    ? availability.state.value.catalog
    : undefined
);
const metricsPage = computed(() => route.path.replace(/\/$/, "") === "/sms/metrics");
const form = reactive({ applicationId: "A", country: "CN", leaseSeconds: 60 });
const storageKey = "sms-reception-session";
function restore(): Reception[] {
  try {
    const stored: unknown = JSON.parse(sessionStorage.getItem(storageKey) ?? "[]");
    return Array.isArray(stored)
      ? stored
          .filter(
            (row): row is Reception =>
              row && typeof row.messageId === "string" && row.status in statusLabels
          )
          .slice(0, 100)
      : [];
  } catch {
    return [];
  }
}
const records = ref<Reception[]>(restore());
const selectedId = ref(records.value[0]?.messageId ?? "");
const lookupId = ref("");
const current = computed(() =>
  records.value.find((row) => row.messageId === selectedId.value)
);
const metrics = ref<Metrics>();
const busy = ref(false),
  error = ref("");
let stopped = false;
let timer: ReturnType<typeof setTimeout> | undefined;
let reading: AbortController | undefined;
let submitting: AbortController | undefined;
function remember(row: Reception) {
  records.value = [
    row,
    ...records.value.filter((item) => item.messageId !== row.messageId)
  ].slice(0, 100);
  try {
    sessionStorage.setItem(storageKey, JSON.stringify(records.value));
  } catch {
    /* The live view still works without browser storage. */
  }
}
const time = (millis?: number) =>
  millis ? new Date(millis).toLocaleTimeString("zh-CN", { hour12: false }) : "—";
async function refresh(force = false) {
  reading?.abort();
  const controller = new AbortController();
  reading = controller;
  const id = selectedId.value;
  const shouldRead =
    id &&
    (force ||
      !current.value ||
      current.value.leaseActive ||
      current.value.status === "NOT_OBSERVED");
  try {
    const [nextMetrics, next] = await Promise.all([
      api<Metrics>("/api/v1/sms/metrics", undefined, controller.signal),
      shouldRead
        ? api<Reception>(
            "/api/v1/sms/messages/" + encodeURIComponent(id),
            undefined,
            controller.signal
          )
        : Promise.resolve(undefined)
    ]);
    if (stopped || controller.signal.aborted) return;
    metrics.value = nextMetrics;
    if (next) remember(next);
    error.value = "";
  } catch (failure) {
    if (!controller.signal.aborted)
      error.value = failure instanceof Error ? failure.message : "读取失败";
  }
}
async function poll() {
  await refresh();
  if (!stopped) timer = setTimeout(poll, 1000);
}
async function lease() {
  if (busy.value) return;
  busy.value = true;
  submitting = new AbortController();
  try {
    const row = await api<Reception>(
      "/api/v1/sms/numbers:lease",
      { ...form },
      submitting.signal
    );
    if (stopped) return;
    remember(row);
    selectedId.value = row.messageId;
    lookupId.value = row.messageId;
    error.value = "";
  } catch (failure) {
    if (!submitting.signal.aborted)
      error.value = failure instanceof Error ? failure.message : "取号失败";
  } finally {
    busy.value = false;
  }
}
async function lookup() {
  const id = lookupId.value.trim();
  if (!id) return;
  selectedId.value = id;
  await refresh(true);
}
async function select(row: Reception) {
  selectedId.value = row.messageId;
  lookupId.value = row.messageId;
  await refresh(true);
}
onMounted(() => void poll());
onBeforeUnmount(() => {
  stopped = true;
  clearTimeout(timer);
  reading?.abort();
  submitting?.abort();
});
</script>

<template>
  <div class="sms-workspace">
    <nav class="sms-tabs" aria-label="SMS 页面">
      <router-link to="/sms" :aria-current="!metricsPage ? 'page' : undefined"
        >接码工作台</router-link
      >
      <router-link to="/sms/metrics" :aria-current="metricsPage ? 'page' : undefined"
        >业务指标</router-link
      >
    </nav>
    <main class="sms-content-area">
      <header>
        <div class="eyebrow">SMS RECEPTION / PREVIEW</div>
        <h1>{{ metricsPage ? "业务指标" : "接码工作台" }}</h1>
        <p>取号后使用 messageId 查询，租期内持续显示最新短信。</p>
      </header>
      <p v-if="error" role="alert" class="error-banner">{{ error }}</p>
      <section class="stat-row" aria-label="运行概况">
        <div>
          <small>取号请求</small><strong>{{ metrics?.requests ?? "—" }}</strong>
        </div>
        <div>
          <small>结果查询</small><strong>{{ metrics?.queries ?? "—" }}</strong>
        </div>
        <div>
          <small>已记录租期</small
          ><strong>{{ metrics?.projection.recorded ?? "—" }}</strong>
        </div>
        <div>
          <small>请求错误</small><strong>{{ metrics?.errors ?? "—" }}</strong>
        </div>
      </section>
      <template v-if="!metricsPage">
        <div class="workbench-grid">
          <section class="panel">
            <h2>申请号码</h2>
            <form @submit.prevent="lease">
              <label
                >接收应用<select v-model="form.applicationId" aria-label="接收应用">
                  <option
                    v-for="app in catalog?.applications"
                    :key="app.id"
                    :value="app.id"
                  >
                    {{ app.name }}
                  </option>
                </select></label
              >
              <label
                >号码国家<select v-model="form.country" aria-label="号码国家">
                  <option
                    v-for="country in catalog?.countries ?? ['CN', 'US', 'GB']"
                    :key="country"
                    :value="country"
                  >
                    {{ country }}
                  </option>
                </select></label
              >
              <label
                >租期（秒）<input
                  v-model.number="form.leaseSeconds"
                  type="number"
                  min="1"
                  max="300"
                  step="1"
                  required
                  aria-label="租期（秒）"
              /></label>
              <button type="submit" class="primary" :disabled="busy">
                {{ busy ? "正在取号…" : "获取号码" }}
              </button>
            </form>
            <p class="hint">同一号码可以服务不同应用。收到短信后，当前租期仍会继续。</p>
          </section>
          <section class="panel current-panel" aria-live="polite">
            <h2>号码与最新短信</h2>
            <template v-if="current">
              <span class="status-label" :data-status="current.status">{{
                statusLabels[current.status]
              }}</span>
              <div class="phone">{{ current.phoneNumber ?? "等待号码结果…" }}</div>
              <p class="hint">
                {{ current.applicationId ?? "—" }} · {{ current.country ?? "—" }} ·
                租期至 {{ time(current.leaseUntil) }}
                <span v-if="current.leaseActive === false">（已结束）</span>
              </p>
              <div v-if="current.sms" class="sms-content">
                <small>最新短信 · {{ time(current.sms.receivedAt) }}</small
                ><strong v-if="current.sms.code">{{ current.sms.code }}</strong>
                <p>{{ current.sms.text }}</p>
              </div>
              <p>{{ waitingMessage(current) }}</p>
              <div class="record-id">{{ current.messageId }}</div>
            </template>
            <p v-else class="hint">申请号码，或输入已有的 messageId 查询。</p>
            <form class="lookup" @submit.prevent="lookup">
              <label
                >messageId<input
                  v-model="lookupId"
                  aria-label="messageId"
                  placeholder="输入 messageId" /></label
              ><button type="submit">查询 / 刷新</button>
            </form>
          </section>
        </div>
        <section class="panel records">
          <h2>本次浏览器会话</h2>
          <p class="hint">
            仅在当前浏览器保留最近 100 条，可随时按 messageId 重新查询。
          </p>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>号码</th>
                  <th>应用 / 国家</th>
                  <th>状态</th>
                  <th>租期至</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in records" :key="row.messageId">
                  <td>{{ row.phoneNumber ?? "等待结果" }}</td>
                  <td>{{ row.applicationId ?? "—" }} / {{ row.country ?? "—" }}</td>
                  <td>{{ statusLabels[row.status] }}</td>
                  <td>{{ time(row.leaseUntil) }}</td>
                  <td><button type="button" @click="select(row)">查看</button></td>
                </tr>
                <tr v-if="!records.length">
                  <td colspan="5">暂无取号记录</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
      <section v-else class="panel metric-grid">
        <div>
          <small>取号等待 P95 / P99</small
          ><strong
            >{{ metrics?.acquisitionLatencyMillis.p95 ?? "—" }} /
            {{ metrics?.acquisitionLatencyMillis.p99 ?? "—" }} ms</strong
          >
        </div>
        <div>
          <small>查询 P95 / P99</small
          ><strong
            >{{ metrics?.queryLatencyMillis.p95 ?? "—" }} /
            {{ metrics?.queryLatencyMillis.p99 ?? "—" }} ms</strong
          >
        </div>
        <div>
          <small>租期通知丢弃 / 失败</small
          ><strong
            >{{ metrics?.projection.dropped ?? "—" }} /
            {{ metrics?.projection.failures ?? "—" }}</strong
          >
        </div>
        <div>
          <small>租期处理排队</small
          ><strong>{{ metrics?.projection.queueBatches ?? "—" }}</strong>
        </div>
      </section>
      <footer>
        SMS Reception {{ catalog?.version ?? "0.1.0-preview" }}
        <span>模拟短信 · 本地运行</span>
      </footer>
    </main>
  </div>
</template>

<style scoped>
.sms-workspace {
  color: var(--rv-text);
}
.sms-tabs {
  display: flex;
  gap: 28px;
  border-bottom: 1px solid var(--rv-border);
  padding: 0 32px;
}
.sms-tabs a {
  padding: 18px 0;
  color: var(--rv-text-secondary);
  text-decoration: none;
}
.sms-tabs a[aria-current="page"] {
  color: var(--rv-text);
  border-bottom: 2px solid currentColor;
}
.sms-content-area {
  max-width: 1360px;
  margin: auto;
  padding: 32px;
}
.eyebrow,
small,
.hint,
footer {
  color: var(--rv-text-secondary);
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
}
h1 {
  font-size: 30px;
  margin: 10px 0;
}
h2 {
  font-size: 18px;
  margin: 0 0 24px;
}
header p {
  color: var(--rv-text-secondary);
  margin-bottom: 30px;
}
.stat-row,
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}
.stat-row {
  margin-bottom: 26px;
}
.stat-row > div,
.panel {
  padding: 24px;
  border: 1px solid var(--rv-border);
  border-radius: 10px;
  background: var(--rv-surface);
}
.stat-row strong,
.metric-grid strong {
  display: block;
  margin-top: 12px;
  font-size: 23px;
}
.workbench-grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(0, 1.5fr);
  gap: 24px;
}
label {
  display: block;
  font-size: 13px;
  margin-bottom: 18px;
}
input,
select {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin-top: 8px;
  padding: 11px;
  color: var(--rv-text);
  border: 1px solid var(--rv-border);
  border-radius: 5px;
  background: var(--rv-bg);
}
button {
  padding: 10px 16px;
  border: 1px solid var(--rv-border);
  border-radius: 5px;
  background: var(--rv-bg);
  color: var(--rv-text);
  cursor: pointer;
}
button.primary {
  width: 100%;
  background: #276b56;
  color: white;
}
button:disabled {
  opacity: 0.6;
  cursor: wait;
}
.hint {
  font-size: 12px;
  line-height: 1.8;
}
.phone {
  font-size: 30px;
  margin-top: 20px;
  overflow-wrap: anywhere;
}
.status-label {
  font-size: 12px;
}
.status-label[data-status="RECEIVED"] {
  color: #358467;
}
.sms-content {
  padding: 20px;
  background: var(--rv-bg);
  margin: 22px 0;
  border-radius: 6px;
  overflow-wrap: anywhere;
}
.sms-content strong {
  display: block;
  font-size: 34px;
  letter-spacing: 5px;
  margin: 10px 0;
}
.record-id {
  font-family: monospace;
  font-size: 11px;
  overflow-wrap: anywhere;
  color: var(--rv-text-secondary);
}
.lookup {
  margin-top: 24px;
}
.records {
  margin-top: 24px;
}
.table-scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  text-align: left;
}
th,
td {
  padding: 13px 10px;
  border-bottom: 1px solid var(--rv-border);
  white-space: nowrap;
}
.metric-grid strong {
  font-size: 18px;
}
.error-banner {
  padding: 14px;
  border: 1px solid #b96b53;
}
footer {
  display: flex;
  justify-content: space-between;
  margin-top: 30px;
  font-size: 11px;
}
@media (max-width: 900px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 600px) {
  .sms-content-area {
    padding: 18px;
  }
  .stat-row {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }
  .panel {
    padding: 18px;
  }
  .phone {
    font-size: 24px;
  }
  footer span {
    display: none;
  }
}
</style>
