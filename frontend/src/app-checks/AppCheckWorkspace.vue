<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Plus, Refresh } from "@element-plus/icons-vue";
import AppCheckCreate from "./AppCheckCreate.vue";
import {
  stateLabel,
  taskStateLabels,
  type Catalog,
  type CheckDetail,
  type CheckTask
} from "./model";
import type { AppCheckTaskSource } from "./task-source";
import {
  cryptoAvailable,
  verificationLabels,
  verifyPreview,
  type Verification
} from "./verify";

const props = defineProps<{ source: AppCheckTaskSource; catalog: Catalog }>();
const route = useRoute(),
  router = useRouter();
const taskId = computed(() =>
  typeof route.params.taskId === "string" ? route.params.taskId : undefined
);
const tasks = ref<CheckTask[]>([]),
  truncated = ref(false),
  detail = shallowRef<CheckDetail>();
const query = ref(""),
  appFilter = ref("all"),
  stateFilter = ref("all"),
  creating = ref(false);
const listBusy = ref(false),
  detailBusy = ref(false),
  listError = ref(""),
  detailError = ref("");
const verification = shallowRef(new Map<string, Verification>()),
  verifying = ref(false),
  verifyError = ref("");
const title = ref<HTMLElement>();
let disposed = false,
  listGeneration = 0,
  detailGeneration = 0,
  verifyGeneration = 0,
  listScroll = 0;
let lastOpened: string | undefined;
const cryptoSupported = cryptoAvailable();
const filtered = computed(() =>
  tasks.value.filter((task) => {
    const search = query.value.trim().toLowerCase();
    return (
      (!search || `${task.name ?? ""} ${task.taskId}`.toLowerCase().includes(search)) &&
      (appFilter.value === "all" || task.appId === appFilter.value) &&
      (stateFilter.value === "all" ||
        (task.state ?? "unavailable") === stateFilter.value)
    );
  })
);
const results = computed(() => detail.value?.results.slice(0, 100) ?? []);
const summary = computed(() => {
  const counts = { matched: 0, mismatch: 0, unavailable: 0 };
  for (const row of verification.value.values()) counts[row.status]++;
  return counts;
});
const countFields = [
  ["totalCount", "查询总数"],
  ["activeCount", "ACTIVE"],
  ["succeededCount", "执行成功"],
  ["failedCount", "当前失败"]
] as const;
const date = (value: number) =>
  new Date(value).toLocaleString("zh-CN", { hour12: false });
const failureMessage = (error: unknown) =>
  error instanceof Error ? error.message : "读取失败，请重试。";
async function loadList() {
  const generation = ++listGeneration;
  listBusy.value = true;
  listError.value = "";
  try {
    const value = await props.source.listTasks();
    if (disposed || generation !== listGeneration) return;
    tasks.value = value.tasks.slice(0, 100);
    truncated.value = value.truncated || value.tasks.length > 100;
  } catch (error) {
    if (!disposed && generation === listGeneration)
      listError.value = failureMessage(error);
  } finally {
    if (!disposed && generation === listGeneration) listBusy.value = false;
  }
}
async function loadDetail(id: string) {
  const generation = ++detailGeneration;
  verifyGeneration++;
  verifying.value = false;
  detailBusy.value = true;
  detailError.value = "";
  try {
    const value = await props.source.loadTask(id);
    if (disposed || generation !== detailGeneration || taskId.value !== id) return;
    detail.value = value;
    verification.value = new Map();
    verifyError.value = "";
  } catch (error) {
    if (!disposed && generation === detailGeneration && taskId.value === id)
      detailError.value = failureMessage(error);
  } finally {
    if (!disposed && generation === detailGeneration) detailBusy.value = false;
  }
}
function openTask(id: string) {
  lastOpened = id;
  listScroll = window.scrollY;
  void router.push(`/app-checks/tasks/${encodeURIComponent(id)}`);
}
async function verify() {
  const snapshot = detail.value;
  if (!snapshot || verifying.value || detailBusy.value || !cryptoSupported) return;
  const generation = ++verifyGeneration;
  verifying.value = true;
  verifyError.value = "";
  try {
    const value = await verifyPreview(snapshot);
    if (!disposed && generation === verifyGeneration && detail.value === snapshot)
      verification.value = value;
  } catch (error) {
    if (!disposed && generation === verifyGeneration)
      verifyError.value = failureMessage(error);
  } finally {
    if (!disposed && generation === verifyGeneration) verifying.value = false;
  }
}
watch(
  taskId,
  async (id, previous) => {
    detailGeneration++;
    verifyGeneration++;
    verifying.value = false;
    detail.value = undefined;
    detailError.value = "";
    verification.value = new Map();
    verifyError.value = "";
    if (id) {
      await loadDetail(id);
      await nextTick();
      if (!disposed && taskId.value === id) title.value?.focus({ preventScroll: true });
    } else {
      await loadList();
      await nextTick();
      if (!disposed && !taskId.value && previous) {
        document
          .getElementById(`check-task-${lastOpened}`)
          ?.focus({ preventScroll: true });
        window.scrollTo({ top: listScroll, left: 0, behavior: "instant" });
      }
    }
  },
  { immediate: true }
);
onBeforeUnmount(() => {
  disposed = true;
  listGeneration++;
  detailGeneration++;
  verifyGeneration++;
});
</script>

<template>
  <div class="checks-workspace" data-testid="app-check-workspace">
    <div class="checks-context">
      PROJECT <strong>app-checks</strong
      ><span v-if="source.mode === 'mock'" class="checks-mock"
        >Mock 数据 · 本地会话样例</span
      >
    </div>
    <template v-if="!taskId">
      <header class="checks-heading">
        <div>
          <h1>应用注册查询</h1>
          <p class="checks-hint">批量查询一个应用的号码注册状态，观察真实执行结果。</p>
        </div>
        <div class="checks-actions">
          <el-button :icon="Refresh" :loading="listBusy" @click="loadList"
            >刷新</el-button
          ><el-button type="primary" :icon="Plus" @click="creating = true"
            >创建查询任务</el-button
          >
        </div>
      </header>
      <p v-if="listError" role="alert" class="checks-error">{{ listError }}</p>
      <section class="checks-surface">
        <div class="checks-toolbar">
          <el-input
            v-model="query"
            aria-label="搜索查询任务"
            placeholder="搜索名称或 Task ID"
            clearable
          /><el-select v-model="appFilter" aria-label="筛选应用"
            ><el-option label="全部应用" value="all" /><el-option
              v-for="app in catalog.apps"
              :key="app.appId"
              :label="app.appId"
              :value="app.appId" /></el-select
          ><el-select v-model="stateFilter" aria-label="筛选任务状态"
            ><el-option label="全部状态" value="all" /><el-option
              v-for="(label, value) in taskStateLabels"
              :key="value"
              :label="label"
              :value="value" /><el-option label="状态不可用" value="unavailable"
          /></el-select>
        </div>
        <p class="checks-table-note">
          筛选当前已加载的 {{ tasks.length }} 个任务，最多 100 条。<span
            v-if="truncated"
            >列表已截断，未展示全部任务。</span
          >
        </p>
        <div
          class="checks-table-scroll"
          tabindex="0"
          aria-label="查询任务列表，可横向滚动"
        >
          <table class="checks-table">
            <thead>
              <tr>
                <th>任务</th>
                <th>App／国家</th>
                <th>创建时间</th>
                <th>Task 状态</th>
                <th>总数</th>
                <th>ACTIVE</th>
                <th>执行成功</th>
                <th>当前失败</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in filtered" :key="task.taskId">
                <td>
                  <button
                    :id="`check-task-${task.taskId}`"
                    class="checks-task-link"
                    @click="openTask(task.taskId)"
                  >
                    {{ task.name ?? task.taskId }}</button
                  ><small v-if="task.name">{{ task.taskId }}</small
                  ><span v-if="task.managed" class="checks-badge">Managed Task</span
                  ><span v-else-if="!task.appId" class="checks-badge"
                    >业务信息缺失</span
                  >
                </td>
                <td>
                  {{ task.appId ?? "—" }}<small>{{ task.country ?? "—" }}</small>
                </td>
                <td>{{ date(task.createdAtMillis) }}</td>
                <td>
                  <span class="checks-badge">{{ stateLabel(task.state) }}</span>
                </td>
                <td>{{ task.totalCount ?? "—" }}</td>
                <td>{{ task.activeCount ?? "—" }}</td>
                <td>{{ task.succeededCount ?? "—" }}</td>
                <td>{{ task.failedCount ?? "—" }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="!filtered.length" class="checks-empty">
          {{
            listBusy
              ? "正在读取任务…"
              : tasks.length
                ? "当前筛选下没有任务。"
                : listError
                  ? "任务列表尚未读取成功。"
                  : "还没有查询任务，从创建开始。"
          }}
        </p>
      </section>
    </template>
    <template v-else>
      <router-link to="/app-checks" class="checks-back">← 返回应用注册查询</router-link>
      <header class="checks-heading">
        <div>
          <h1 ref="title" tabindex="-1">{{ detail?.task.name ?? taskId }}</h1>
          <p class="checks-hint checks-id">{{ taskId }}</p>
        </div>
        <el-button :icon="Refresh" :loading="detailBusy" @click="loadDetail(taskId)"
          >刷新</el-button
        >
      </header>
      <p v-if="detailError" role="alert" class="checks-error">{{ detailError }}</p>
      <p v-if="!detail && detailBusy" class="checks-empty">正在读取任务…</p>
      <template v-if="detail">
        <section class="checks-surface checks-information">
          <div class="checks-actions">
            <span class="checks-badge">{{ stateLabel(detail.task.state) }}</span
            ><span v-if="detail.task.managed" class="checks-badge">Managed Task</span>
          </div>
          <dl class="checks-facts">
            <div>
              <dt>App</dt>
              <dd>{{ detail.task.appId ?? "—" }}</dd>
            </div>
            <div>
              <dt>号码国家</dt>
              <dd>{{ detail.task.country ?? "—" }}</dd>
            </div>
            <div>
              <dt>WorkerGroup</dt>
              <dd>{{ detail.task.workerGroupId ?? "—" }}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>{{ date(detail.task.createdAtMillis) }}</dd>
            </div>
          </dl>
          <details>
            <summary>模拟配置与复算参数</summary>
            <p v-if="detail.task.configurationError" class="checks-error">
              {{ detail.task.configurationError }}
            </p>
            <pre>{{
              detail.task.simulation
                ? JSON.stringify(detail.task.simulation, null, 2)
                : "未提供模拟配置"
            }}</pre>
            <p class="checks-id">salt：{{ detail.task.salt ?? "—" }}</p>
            <p>salt UTC 日期：{{ detail.task.saltDate ?? "—" }}</p>
          </details>
        </section>
        <section aria-label="Item Score 数量观测">
          <div class="checks-counts">
            <div
              v-for="[field, label] in countFields"
              :key="field"
              class="checks-surface checks-count"
            >
              <span>{{ label }}</span
              ><strong>{{ detail.task[field] ?? "—" }}</strong>
            </div>
          </div>
          <p class="checks-hint">
            数量来自 Item Score。已注册与未注册都计入执行成功；这些数量与下方 Result
            内容独立观测。
          </p>
        </section>
        <section class="checks-surface">
          <div class="checks-results-heading">
            <div>
              <h2>执行结果</h2>
              <p class="checks-hint">
                最多 100 条 Result 预览，不承诺文件顺序或完整覆盖。<span
                  v-if="detail.resultsTruncated || detail.results.length > 100"
                  >本次未完整展示。</span
                >
              </p>
            </div>
            <el-button
              :loading="verifying"
              :disabled="!cryptoSupported || detailBusy || !results.length"
              @click="verify"
              >核对当前预览</el-button
            >
          </div>
          <p v-if="!cryptoSupported" class="checks-table-note">
            当前浏览器不支持 Web Crypto，请使用 HTTPS 或本机地址核对。
          </p>
          <p v-if="verifyError" role="alert" class="checks-error checks-table-note">
            {{ verifyError }}
          </p>
          <p v-if="verification.size" class="checks-table-note" role="status">
            当前预览：复算一致 {{ summary.matched }} · 不一致 {{ summary.mismatch }} ·
            无法核对
            {{ summary.unavailable }}。仅检查这份内容，不证明完整调度或执行次数。
          </p>
          <div
            class="checks-table-scroll"
            tabindex="0"
            aria-label="结果预览，可横向滚动"
          >
            <table class="checks-table">
              <thead>
                <tr>
                  <th>号码</th>
                  <th>执行结果</th>
                  <th>注册答案</th>
                  <th>实际 Worker</th>
                  <th>模拟延迟</th>
                  <th>核对</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in results" :key="row.messageId">
                  <td>
                    {{ row.number ?? "—" }}
                    <details>
                      <summary>关联信息</summary>
                      <small>messageId：{{ row.messageId }}</small
                      ><small>Group：{{ row.workerGroupId ?? "—" }}</small>
                    </details>
                  </td>
                  <td>
                    <span
                      class="checks-badge"
                      :class="{ 'checks-negative': row.resultStatus === 'failed' }"
                      >{{
                        row.resultStatus === "succeeded" ? "执行成功" : "执行失败"
                      }}</span
                    >
                  </td>
                  <td>
                    <template v-if="row.resultStatus === 'failed'">无注册答案</template
                    ><span v-else-if="row.contentError" class="checks-error"
                      >内容解析错误：{{ row.contentError }}</span
                    ><template v-else>{{
                      row.registered === true
                        ? "已注册"
                        : row.registered === false
                          ? "未注册"
                          : "—"
                    }}</template>
                  </td>
                  <td class="checks-id">{{ row.workerId ?? "—" }}</td>
                  <td>
                    {{
                      row.simulatedDelayMillis === undefined
                        ? "—"
                        : `${row.simulatedDelayMillis} ms`
                    }}
                  </td>
                  <td>
                    <template v-if="verification.has(row.messageId)"
                      ><span
                        class="checks-badge"
                        :class="{
                          'checks-negative':
                            verification.get(row.messageId)?.status === 'mismatch'
                        }"
                        >{{
                          verificationLabels[verification.get(row.messageId)!.status]
                        }}</span
                      ><small>{{ verification.get(row.messageId)?.reason }}</small
                      ><small
                        v-if="verification.get(row.messageId)?.bucket !== undefined"
                        >桶 {{ verification.get(row.messageId)?.bucket }} · 预期
                        {{ verification.get(row.messageId)?.expectedDelay }} ms</small
                      ></template
                    ><span v-else>未核对</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-if="!results.length" class="checks-empty">当前未观察到 Result。</p>
          <p class="checks-table-note">
            模拟延迟是 Handler
            的配置等待时间。调度结束后仍可刷新读取迟到结果；失败记录不用于推断注册状态。
          </p>
        </section>
      </template>
    </template>
    <AppCheckCreate
      v-model="creating"
      :source="source"
      :catalog="catalog"
      @created="(id) => router.push(`/app-checks/tasks/${encodeURIComponent(id)}`)"
    />
  </div>
</template>
