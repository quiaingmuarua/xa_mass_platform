<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import {
  ElMessage,
  ElForm,
  ElFormItem,
  ElRadioGroup,
  ElRadioButton,
  ElPagination
} from "element-plus";
import "element-plus/es/components/form/style/css";
import "element-plus/es/components/form-item/style/css";
import "element-plus/es/components/radio-group/style/css";
import "element-plus/es/components/radio-button/style/css";
import "element-plus/es/components/pagination/style/css";
import { useRoute, useRouter } from "vue-router";
import { useThemeStore } from "@/stores/theme";
import {
  api,
  canCancel,
  statusLabels,
  waitingMessage,
  type Listener,
  type Metrics,
  type Page,
  type Catalog
} from "./api";

const tabs = ["接码工作台", "监听记录", "业务指标"];
const paths = ["/sms", "/sms/listeners", "/sms/metrics"];
const route = useRoute();
const router = useRouter();
const theme = useThemeStore();
const tab = computed(() => Math.max(0, paths.indexOf(route.path.replace(/\/$/, ""))));
const form = reactive({ applicationId: "A", country: "CN", listenSeconds: 60 });
const requestId = ref(crypto.randomUUID());
const page = ref(1);
const records = ref<Page>({ total: 0, items: [] });
const metrics = ref<Metrics>();
const current = ref<Listener>();
const busy = ref(false);
const error = ref("");
const catalog = ref<Catalog>();
let timer: ReturnType<typeof setTimeout> | undefined;
let active: AbortController | undefined;
let mutation: AbortController | undefined;
let stopped = false;
const activeCount = computed(() => metrics.value?.activeListenersObserved ?? 0);
const time = (value?: number) =>
  value ? new Date(value).toLocaleTimeString("zh-CN", { hour12: false }) : "—";

async function refresh() {
  active?.abort();
  const controller = new AbortController();
  active = controller;
  try {
    const [next, counts] = await Promise.all([
      api<Page>(
        `/api/v1/sms/listeners?offset=${(page.value - 1) * 20}&limit=20`,
        undefined,
        controller.signal
      ),
      api<Metrics>("/api/v1/sms/metrics", undefined, controller.signal)
    ]);
    records.value = next;
    metrics.value = counts;
    if (catalog.value?.runId !== counts.runId) {
      if (catalog.value && catalog.value.runId !== counts.runId) {
        current.value = undefined;
        requestId.value = crypto.randomUUID();
        page.value = 1;
      }
      catalog.value = await api<Catalog>(
        "/api/v1/sms/catalog",
        undefined,
        controller.signal
      );
    }
    if (current.value) {
      const selectedId = current.value.id;
      const latest = await api<Listener>(
        `/api/v1/sms/listeners/${selectedId}`,
        undefined,
        controller.signal
      );
      if (current.value?.id === selectedId) current.value = latest;
    }
    error.value = "";
  } catch (failure) {
    if (!controller.signal.aborted)
      error.value = failure instanceof Error ? failure.message : "连接失败";
  }
}
async function poll() {
  await refresh();
  if (!stopped) timer = setTimeout(poll, 1000);
}
async function action(work: (signal: AbortSignal) => Promise<void>) {
  if (busy.value || stopped) return;
  busy.value = true;
  mutation = new AbortController();
  try {
    await work(mutation.signal);
    if (!stopped) await refresh();
  } catch (failure) {
    if (!mutation.signal.aborted)
      ElMessage.error(failure instanceof Error ? failure.message : "操作失败");
  } finally {
    busy.value = false;
  }
}
async function create() {
  await action(async (signal) => {
    current.value = await api<Listener>(
      "/api/v1/sms/listeners",
      {
        ...form,
        requestId: requestId.value
      },
      signal
    );
    requestId.value = crypto.randomUUID();
    ElMessage.success("监听申请已受理");
  });
}
async function cancel(item: Listener) {
  await action(async (signal) => {
    await api(`/api/v1/sms/listeners/${item.id}/cancel`, {}, signal);
    ElMessage.info("已记录取消请求，等待号码确认");
  });
}
watch(page, () => void refresh());
watch(form, () => {
  requestId.value = crypto.randomUUID();
});
onMounted(() => void poll());
onUnmounted(() => {
  stopped = true;
  clearTimeout(timer);
  active?.abort();
  mutation?.abort();
});
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">S</span>
        <div>SMS Reception<small>短信接收工作空间</small></div>
      </div>
      <div class="workspace-label">本地预览工作空间</div>
      <nav aria-label="主导航">
        <button
          v-for="(name, index) in tabs"
          :key="name"
          :class="{ selected: tab === index }"
          @click="router.push(paths[index])"
        >
          <span>0{{ index + 1 }}</span
          >{{ name }}
        </button>
      </nav>
      <div class="sidebar-note">
        <span class="status-dot"></span> 自有 SIM · Java 模拟池
        <p>CN / US / GB</p>
        <small>0.1.0-preview<br />本次运行独立，重启清空监听。</small>
      </div>
    </aside>
    <main>
      <header>
        <div>
          <div class="eyebrow">SMS RECEPTION / PREVIEW</div>
          <h1>{{ tabs[tab] }}</h1>
          <p>
            {{
              tab === 0
                ? "选择应用与国家，获取号码并等待第一条匹配短信。"
                : tab === 1
                  ? "追踪每次申请的真实业务结果。到期与未确认分别展示。"
                  : "查看已观察的接码结果、延迟和未确认数量。"
            }}
          </p>
        </div>
        <div class="actions">
          <router-link to="/runtime/workers">平台观察</router-link
          ><el-button @click="theme.toggle">{{
            theme.dark ? "浅色" : "深色"
          }}</el-button
          ><span class="environment">● 本地场景</span>
        </div>
      </header>
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        :closable="false"
        class="error-banner"
      />
      <section class="stat-row" aria-label="运行概况">
        <div>
          <small>本次申请</small><strong>{{ metrics?.requests ?? "—" }}</strong
          ><span>本轮监听记录</span>
        </div>
        <div>
          <small>活跃监听</small><strong>{{ activeCount }}</strong
          ><span>含等待取消确认</span>
        </div>
        <div>
          <small>收到短信</small
          ><strong class="green">{{ metrics?.statuses.RECEIVED ?? 0 }}</strong
          ><span>已观察到匹配内容</span>
        </div>
        <div>
          <small>结果未确认</small
          ><strong>{{ metrics?.statuses.UNCONFIRMED ?? 0 }}</strong
          ><span>与明确到期分开计数</span>
        </div>
      </section>

      <template v-if="tab === 0">
        <div class="workbench-grid">
          <section class="panel">
            <div class="panel-heading">
              <h2>新建监听</h2>
              <span>01 / 申请号码</span>
            </div>
            <el-form label-position="top" @submit.prevent="create">
              <el-form-item label="接收应用"
                ><el-select v-model="form.applicationId" aria-label="接收应用"
                  ><el-option
                    v-for="app in catalog?.applications ?? []"
                    :key="app.id"
                    :label="`${app.name} / 优先级 ${app.templates.map((template) => template.priority).join(', ')}`"
                    :value="app.id" /></el-select
              ></el-form-item>
              <el-form-item label="号码国家"
                ><el-radio-group v-model="form.country"
                  ><el-radio-button value="CN">中国 CN</el-radio-button
                  ><el-radio-button value="US">美国 US</el-radio-button
                  ><el-radio-button value="GB">英国 GB</el-radio-button></el-radio-group
                ></el-form-item
              >
              <el-form-item label="监听时长（秒）"
                ><el-input
                  v-model.number="form.listenSeconds"
                  type="number"
                  min="1"
                  max="300"
                  step="1"
                  required
                  aria-label="监听时长（秒）"
              /></el-form-item>
              <el-button
                type="primary"
                native-type="submit"
                :loading="busy"
                class="full-width"
                >获取号码并监听</el-button
              >
            </el-form>
            <p class="hint">
              同一个号码可以承接多个监听。一条短信只归属最高优先级的匹配监听，命中后该监听结束。
            </p>
          </section>
          <section class="panel current-panel">
            <div class="panel-heading">
              <h2>当前监听</h2>
              <span>02 / 等待短信</span>
            </div>
            <template v-if="current"
              ><span class="status-label" :data-status="current.status">{{
                statusLabels[current.status]
              }}</span>
              <div class="phone">{{ current.phone ?? "正在分配号码…" }}</div>
              <p class="hint">
                {{ current.applicationId }} 应用 · {{ current.country }} ·
                {{ time(current.startedAt) }} — {{ time(current.expiresAt) }}
              </p>
              <div v-if="current.sms" class="sms-content">
                <small>收到的短信</small
                ><strong v-if="current.sms.code">{{ current.sms.code }}</strong>
                <p>{{ current.sms.text }}</p>
                <small
                  >{{ time(current.sms.receivedAt) }} ·
                  {{ current.sms.templateId }}</small
                >
              </div>
              <div v-else class="waiting">
                <span>◌</span>
                <p>
                  {{ waitingMessage(current) }}
                </p>
              </div>
              <div class="actions">
                <el-button
                  v-if="canCancel(current.status)"
                  :disabled="busy"
                  @click="cancel(current)"
                  >取消监听</el-button
                >
              </div>
              <small class="record-id">{{ current.id }}</small>
            </template>
            <div v-else class="empty">
              <span>✉</span>
              <h3>从一个号码开始</h3>
              <p>提交申请后，这里会展示号码、监听窗口和匹配到的短信。</p>
            </div>
          </section>
        </div>
      </template>
      <section v-if="tab !== 2" class="panel records">
        <div class="panel-heading">
          <h2>{{ tab === 0 ? "监听记录" : "本次运行的全部监听" }}</h2>
          <span>每页 20 条 · {{ records.total }} 条记录</span>
        </div>
        <el-table
          :data="records.items"
          empty-text="暂无监听，先在工作台申请一个号码"
          row-key="id"
        >
          <el-table-column label="应用 / 国家" min-width="110"
            ><template #default="{ row }"
              ><strong>{{ row.applicationId }}</strong
              ><span class="country">{{ row.country }}</span></template
            ></el-table-column
          >
          <el-table-column prop="phone" label="接收号码" min-width="180"
            ><template #default="{ row }"
              ><span class="mono">{{ row.phone ?? "等待分配" }}</span></template
            ></el-table-column
          >
          <el-table-column label="状态" min-width="130"
            ><template #default="{ row }"
              ><span class="status-label" :data-status="row.status">{{
                statusLabels[row.status]
              }}</span></template
            ></el-table-column
          >
          <el-table-column label="短信" min-width="210"
            ><template #default="{ row }">{{
              row.sms?.text ?? row.reason ?? "—"
            }}</template></el-table-column
          >
          <el-table-column label="建立 / 到期" min-width="170"
            ><template #default="{ row }"
              >{{ time(row.startedAt) }} / {{ time(row.expiresAt) }}</template
            ></el-table-column
          >
          <el-table-column label="操作" min-width="140"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                @click="
                  current = row;
                  router.push('/sms');
                "
                >查看</el-button
              ><el-button
                v-if="canCancel(row.status)"
                link
                :disabled="busy"
                @click="cancel(row)"
                >取消</el-button
              ></template
            ></el-table-column
          > </el-table
        ><el-pagination
          v-model:current-page="page"
          :page-size="20"
          :total="records.total"
          layout="prev, pager, next"
        />
      </section>

      <section v-if="tab === 2" class="panel">
        <div class="panel-heading">
          <h2>业务结果与延迟</h2>
          <span>本次运行 · 实际观察样本</span>
        </div>
        <div class="metric-grid">
          <div>
            <small>建立延迟 P95 / P99</small
            ><strong
              >{{ metrics?.establishmentLatencyMillis.p95 ?? "—" }} /
              {{ metrics?.establishmentLatencyMillis.p99 ?? "—" }} ms</strong
            >
          </div>
          <div>
            <small>短信观察延迟 P95 / P99</small
            ><strong
              >{{ metrics?.smsObservationLatencyMillis.p95 ?? "—" }} /
              {{ metrics?.smsObservationLatencyMillis.p99 ?? "—" }} ms</strong
            >
          </div>
          <div>
            <small>明确到期</small><strong>{{ metrics?.statuses.EXPIRED ?? 0 }}</strong>
          </div>
          <div>
            <small>明确取消</small
            ><strong>{{ metrics?.statuses.CANCELLED ?? 0 }}</strong>
          </div>
          <div>
            <small>待提交命令</small><strong>{{ metrics?.commandQueue ?? 0 }}</strong>
          </div>
          <div>
            <small>读取错误 / 提交不确定</small
            ><strong
              >{{ metrics?.observationErrors ?? 0 }} /
              {{ metrics?.submissionUnknown ?? 0 }}</strong
            >
          </div>
        </div>
        <p class="hint">
          到期不计为接码成功；结果未确认单独展示。延迟仅包含可观察样本。
        </p>
      </section>
      <footer>
        SMS Reception {{ catalog?.version ?? "0.1.0-preview"
        }}<span>仅模拟数据 · 本地运行</span>
      </footer>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  color: var(--rv-text);
  background: var(--rv-bg);
}
* {
  box-sizing: border-box;
}
button {
  font: inherit;
}
h1,
h2,
h3,
p {
  margin-top: 0;
}
.app-shell {
  display: flex;
  min-height: 100vh;
}
.sidebar {
  width: 248px;
  background: #153d35;
  color: #eff7f1;
  padding: 34px 22px;
  position: fixed;
  inset: 0 auto 0 0;
  display: flex;
  flex-direction: column;
}
.brand {
  display: flex;
  gap: 12px;
  align-items: center;
  font-weight: 650;
  font-size: 17px;
}
.brand-mark {
  display: grid;
  place-items: center;
  background: #d5edbc;
  color: #173d35;
  width: 39px;
  height: 42px;
  border-radius: 11px;
  font-size: 25px;
}
.brand small {
  display: block;
  font-size: 11px;
  font-weight: 400;
  color: #a8c4b6;
  margin-top: 6px;
}
.workspace-label {
  color: #91b2a2;
  font-size: 11px;
  margin: 46px 12px 15px;
  letter-spacing: 1px;
}
nav {
  display: grid;
  gap: 8px;
}
nav button {
  border: 0;
  background: transparent;
  color: #bed1c7;
  border-radius: 8px;
  text-align: left;
  padding: 15px 12px;
  cursor: pointer;
  font-size: 13px;
}
nav button span {
  margin-right: 15px;
  font-size: 11px;
  opacity: 0.6;
}
nav button.selected {
  background: #2a5146;
  color: #eff7f1;
}
.sidebar-note {
  margin-top: auto;
  padding: 22px 12px 0;
  color: #c8dbce;
  font-size: 12px;
}
.sidebar-note p {
  margin: 12px 0 20px;
}
.sidebar-note small {
  font-size: 11px;
  line-height: 1.9;
  color: #9ab7a9;
}
.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #c1e39c;
  margin-right: 5px;
}
main {
  margin-left: 248px;
  padding: 38px 44px;
  max-width: 1680px;
  width: calc(100% - 248px);
}
header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 28px;
}
.eyebrow {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--rv-text-secondary);
  margin-bottom: 10px;
}
h1 {
  font-size: 27px;
  font-weight: 650;
  margin-bottom: 12px;
}
header p {
  font-size: 13px;
  color: var(--rv-text-secondary);
  margin-bottom: 0;
  line-height: 1.7;
}
.environment {
  font-size: 11px;
  color: var(--rv-text-secondary);
  background: var(--rv-success-soft);
  border: 1px solid var(--rv-border);
  padding: 8px 11px;
  border-radius: 16px;
  white-space: nowrap;
}
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 26px;
}
.stat-row > div {
  border: 1px solid var(--rv-border);
  background: var(--rv-surface);
  border-radius: 12px;
  padding: 20px 23px;
}
.stat-row small,
.metric-grid small {
  display: block;
  font-size: 11px;
  color: var(--rv-text-secondary);
}
.stat-row strong {
  display: block;
  font-size: 30px;
  font-weight: 550;
  margin: 13px 0 8px;
}
.stat-row span {
  font-size: 10px;
  color: var(--rv-text-secondary);
}
.green {
  color: var(--rv-success);
}
.workbench-grid {
  display: grid;
  grid-template-columns: 1fr 1.15fr;
  gap: 24px;
  margin-bottom: 24px;
}
.panel {
  padding: 24px;
  background: var(--rv-surface);
  border: 1px solid var(--rv-border);
  border-radius: 12px;
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 24px;
}
.panel-heading h2 {
  font-size: 15px;
  font-weight: 650;
  margin: 0;
}
.panel-heading > span {
  font-size: 10px;
  color: var(--rv-text-secondary);
}
.el-form-item {
  margin-bottom: 23px;
}
.el-form-item__label {
  font-size: 12px !important;
}
.el-select,
.full-width {
  width: 100%;
}
.hint {
  font-size: 11px;
  line-height: 1.9;
  color: var(--rv-text-secondary);
  margin: 17px 0 0;
}
.current-panel {
  position: relative;
}
.phone {
  font-size: 29px;
  letter-spacing: 0.8px;
  color: var(--rv-text);
  margin: 20px 0 10px;
  font-variant-numeric: tabular-nums;
}
.status-label {
  display: inline-block;
  border-radius: 5px;
  padding: 5px 8px;
  background: var(--rv-bg);
  font-size: 11px;
  color: var(--rv-text-secondary);
  white-space: nowrap;
}
.status-label[data-status="RECEIVED"] {
  background: var(--rv-success-soft);
  color: var(--rv-success);
}
.status-label[data-status="LISTENING"] {
  background: var(--rv-info-soft);
  color: var(--rv-info);
}
.status-label[data-status="UNCONFIRMED"],
.status-label[data-status="CANCELLING"] {
  background: var(--rv-warning-soft);
  color: var(--rv-warning);
}
.status-label[data-status="REJECTED"] {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.sms-content {
  background: var(--rv-success-soft);
  border: 1px solid var(--rv-border);
  border-radius: 9px;
  padding: 22px;
  margin: 24px 0;
}
.sms-content small {
  color: var(--rv-text-secondary);
  font-size: 10px;
}
.sms-content strong {
  display: block;
  font-size: 34px;
  letter-spacing: 7px;
  font-weight: 500;
  margin: 12px 0;
}
.sms-content p {
  font-size: 13px;
  margin: 14px 0;
  overflow-wrap: anywhere;
}
.waiting {
  min-height: 145px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: var(--rv-text-secondary);
}
.waiting span {
  font-size: 32px;
}
.waiting p {
  font-size: 12px;
  max-width: 250px;
  line-height: 1.8;
  margin: 0;
}
.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.record-id {
  font-size: 9px;
  color: var(--rv-text-secondary);
  display: block;
  margin-top: 20px;
  overflow-wrap: anywhere;
}
.empty {
  display: flex;
  min-height: 295px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--rv-text-secondary);
  text-align: center;
}
.empty > span {
  font-size: 40px;
  background: var(--rv-bg);
  border-radius: 50%;
  width: 76px;
  height: 76px;
  display: grid;
  place-items: center;
  margin-bottom: 20px;
}
.empty h3 {
  font-weight: 500;
  font-size: 16px;
  color: var(--rv-text-secondary);
}
.empty p {
  font-size: 12px;
  max-width: 240px;
  line-height: 1.8;
}
.records .el-table {
  font-size: 12px;
  --el-table-header-bg-color: var(--rv-bg);
  --el-table-border-color: var(--rv-border);
}
.el-pagination {
  justify-content: flex-end;
  margin-top: 20px;
}
.country {
  font-size: 10px;
  color: var(--rv-text-secondary);
  margin-left: 10px;
}
.mono {
  font-variant-numeric: tabular-nums;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 28px;
}
.metric-grid strong {
  display: block;
  font-size: 16px;
  font-weight: 500;
  margin-top: 13px;
}
footer {
  display: flex;
  justify-content: space-between;
  margin-top: 30px;
  font-size: 10px;
  color: var(--rv-text-secondary);
}
.error-banner {
  margin-bottom: 20px;
}
@media (max-width: 1100px) {
  .sidebar {
    width: 204px;
    padding: 28px 15px;
  }
  main {
    margin-left: 204px;
    width: calc(100% - 204px);
    padding: 28px 24px;
  }
  .workbench-grid {
    grid-template-columns: 1fr;
  }
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .stat-row {
    gap: 10px;
  }
  .stat-row > div {
    padding: 17px 13px;
  }
}
@media (max-width: 700px) {
  .app-shell {
    display: block;
  }
  .sidebar {
    position: static;
    width: 100%;
    padding: 18px;
  }
  .brand {
    font-size: 15px;
  }
  .brand-mark {
    width: 33px;
    height: 35px;
  }
  .workspace-label,
  .sidebar-note {
    display: none;
  }
  nav {
    display: flex;
    gap: 4px;
    margin-top: 20px;
  }
  nav button {
    font-size: 12px;
    padding: 11px 9px;
  }
  nav button span {
    display: none;
  }
  main {
    margin: 0;
    width: 100%;
    padding: 24px 16px;
  }
  header {
    margin-bottom: 22px;
  }
  h1 {
    font-size: 24px;
  }
  .environment {
    display: none;
  }
  .stat-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .stat-row strong {
    font-size: 25px;
  }
  .panel {
    padding: 19px;
  }
  .phone {
    font-size: 25px;
  }
  .metric-grid {
    gap: 24px 15px;
  }
  .metric-grid strong {
    font-size: 14px;
  }
  footer span {
    display: none;
  }
}
</style>
