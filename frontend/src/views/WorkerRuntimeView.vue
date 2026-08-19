<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  Collection,
  Connection,
  DataAnalysis,
  InfoFilled,
  RefreshRight,
  Search,
  Tickets,
  TrendCharts,
  Warning
} from "@element-plus/icons-vue";

import JsonBlock from "@/components/JsonBlock.vue";
import MetricCard from "@/components/MetricCard.vue";
import {
  useRuntimeViewerConfig,
  useRuntimeViewerStore,
  useWorkerStatusStore
} from "@/runtime-context";
import { filterCurrentSample } from "@/runtime-viewer/filter";
import type { JsonValue, WorkerView } from "@/runtime-viewer/types";
import {
  presentNetworkState,
  presentSchedulingState,
  presentStatusAxis,
  type WorkerStatusPresentation,
  type WorkerStatusTagTone
} from "@/worker-status/presentation";
import type {
  WorkerNetworkObservation,
  WorkerSchedulingObservation,
  WorkerStatusAxis,
  WorkerStatusEntry
} from "@/worker-status/types";

const store = useRuntimeViewerStore();
const workerStatus = useWorkerStatusStore();
const runtimeConfig = useRuntimeViewerConfig();
const schedulingIsMock = runtimeConfig.mode === "mock";
const searchText = ref("");
const selectedWorker = ref<WorkerView>();
const detailsOpen = ref(false);

const activeWorkers = computed(() => store.activeSample?.workers ?? []);
const filteredWorkers = computed(() =>
  filterCurrentSample(activeWorkers.value, searchText.value)
);
const isRefreshing = computed(() => store.activeSampleState?.status === "refreshing");
const isInitialSampleLoading = computed(
  () =>
    store.activeSampleState?.status === "loading" && store.activeSample === undefined
);
const canRefresh = computed(
  () =>
    store.activeGroup !== undefined &&
    store.activeSampleState?.status !== "loading" &&
    store.activeSampleState?.status !== "refreshing"
);
const isStatusRefreshing = computed(() => workerStatus.isLoading(activeWorkers.value));
const networkSummary = computed(() => {
  const observations = activeWorkers.value.flatMap((worker) => {
    const axis = statusEntry(worker).network;
    return axis.observation !== undefined && !axis.stale ? [axis.observation] : [];
  });
  return {
    connected: observations.filter((value) => value.state === "connected").length,
    observed: observations.length
  };
});
const schedulingSummary = computed(() => {
  const observations = activeWorkers.value.flatMap((worker) => {
    const axis = statusEntry(worker).scheduling;
    return axis.observation !== undefined && !axis.stale ? [axis.observation] : [];
  });
  return {
    dueHot: observations.filter((value) => value.state === "due-hot").length,
    observed: observations.length
  };
});

watch(
  () => store.activeWorkerGroupId,
  () => {
    searchText.value = "";
    closeDetails();
  }
);

onMounted(() => {
  void initializePage();
});

onBeforeUnmount(() => {
  workerStatus.dispose();
});

async function initializePage(): Promise<void> {
  await store.initializeWorkerView();
  observeCurrentSampleIfPresent();
}

async function selectGroup(workerGroupId: string): Promise<void> {
  closeDetails();
  await store.selectGroup(workerGroupId);
  observeCurrentSampleIfPresent();
}

async function refreshSample(): Promise<void> {
  const workerGroupId = store.activeWorkerGroupId;
  closeDetails();
  await store.refreshActiveGroup();
  if (
    workerGroupId !== undefined &&
    store.activeWorkerGroupId === workerGroupId &&
    store.activeSampleState?.status === "ready" &&
    !store.activeSampleState.stale
  ) {
    void workerStatus.replaceSample(workerGroupId, activeWorkers.value);
  }
}

async function refreshCurrentStatus(): Promise<void> {
  const workerGroupId = store.activeWorkerGroupId;
  if (workerGroupId !== undefined) {
    await workerStatus.refreshWorkers(workerGroupId, activeWorkers.value);
  }
}

async function refreshSelectedWorkerStatus(): Promise<void> {
  if (selectedWorker.value !== undefined) {
    await workerStatus.refreshWorker(selectedWorker.value);
  }
}

function observeCurrentSampleIfPresent(): void {
  const workerGroupId = store.activeWorkerGroupId;
  if (workerGroupId !== undefined && store.activeSample !== undefined) {
    void workerStatus.ensureSample(workerGroupId, activeWorkers.value);
  }
}

function openDetails(worker: WorkerView): void {
  selectedWorker.value = worker;
  detailsOpen.value = true;
}

function closeDetails(): void {
  detailsOpen.value = false;
  selectedWorker.value = undefined;
}

function isMissing(workerGroupId: string): boolean {
  return store.missingWorkerGroupIds.includes(workerGroupId);
}

function groupDisplayName(workerGroupId: string): string {
  if (workerGroupId === "scenario-phone-number-workers") {
    return "Phone Number";
  }
  if (workerGroupId === "scenario-string-utils-workers") {
    return "String Utils";
  }
  return workerGroupId
    .replace(/^scenario-/, "")
    .replace(/-workers$/, "")
    .split("-")
    .map((part) => part.charAt(0).toLocaleUpperCase() + part.slice(1))
    .join(" ");
}

function sampleCount(workerGroupId: string): number | undefined {
  return store.samples[workerGroupId]?.sample?.sampledCount;
}

function compactJson(value: Record<string, JsonValue>): string {
  return Object.keys(value).length === 0 ? "—" : JSON.stringify(value);
}

function formattedTime(value?: string, emptyValue = "尚未采样"): string {
  if (value === undefined) {
    return emptyValue;
  }
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(new Date(value));
}

function statusEntry(worker: WorkerView): WorkerStatusEntry {
  return workerStatus.status(worker);
}

function networkPresentation(worker: WorkerView): WorkerStatusPresentation | undefined {
  const observation = statusEntry(worker).network.observation;
  return observation === undefined ? undefined : presentNetworkState(observation.state);
}

function schedulingPresentation(
  worker: WorkerView
): WorkerStatusPresentation | undefined {
  const observation = statusEntry(worker).scheduling.observation;
  return observation === undefined
    ? undefined
    : presentSchedulingState(observation.state);
}

type StatusAxis =
  | WorkerStatusAxis<WorkerNetworkObservation>
  | WorkerStatusAxis<WorkerSchedulingObservation>;

function axisLabel(axis: StatusAxis, presentation?: WorkerStatusPresentation): string {
  return presentStatusAxis(axis, presentation).label;
}

function axisTone(
  axis: StatusAxis,
  presentation?: WorkerStatusPresentation
): WorkerStatusTagTone {
  return presentStatusAxis(axis, presentation).tone;
}

function axisAuxiliary(axis: StatusAxis): string | undefined {
  return presentStatusAxis(axis).auxiliary;
}

function metricValue(value: number, observed: number): string {
  return observed === 0 && activeWorkers.value.length > 0
    ? "—"
    : `${value} / ${observed}`;
}
</script>

<template>
  <section class="worker-page" data-testid="worker-runtime-page">
    <header class="worker-page__heading">
      <div>
        <p class="worker-page__eyebrow">RUNTIME / WORKERS</p>
        <h1>Worker Runtime</h1>
        <p>Worker 声明样本、Adapter Network 与 Kernel Scheduling 观测</p>
      </div>
      <div class="worker-page__heading-actions">
        <span class="generated-at" data-testid="generated-at">
          生成于 {{ formattedTime(store.activeSample?.generatedAt) }}
        </span>
        <el-button
          type="primary"
          :icon="RefreshRight"
          :loading="isRefreshing"
          :disabled="!canRefresh"
          data-testid="refresh-button"
          @click="refreshSample"
        >
          刷新样本
        </el-button>
      </div>
    </header>

    <div class="metric-grid" aria-label="Worker 样本与状态指标">
      <MetricCard
        label="已配置 Group"
        :value="store.configuredWorkerGroupIds.length"
        hint="Profile directory"
        :icon="Collection"
        tone="primary"
        test-id="metric-configured-groups"
      />
      <MetricCard
        label="当前样本"
        :value="store.activeSample?.sampledCount ?? '—'"
        hint="one random read"
        :icon="DataAnalysis"
        tone="info"
        test-id="metric-current-sample"
      />
      <MetricCard
        label="Network Connected"
        :value="metricValue(networkSummary.connected, networkSummary.observed)"
        hint="MOCK observed"
        :icon="Connection"
        tone="success"
        test-id="metric-network-connected"
      />
      <MetricCard
        label="Scheduling HOT Due"
        :value="metricValue(schedulingSummary.dueHot, schedulingSummary.observed)"
        :hint="schedulingIsMock ? 'MOCK observed' : 'Kernel observed'"
        :icon="TrendCharts"
        tone="warning"
        test-id="metric-scheduling-due-hot"
      />
    </div>

    <el-alert class="runtime-semantics" type="info" :closable="false" show-icon>
      <template #title>
        <strong>双轴语义：</strong>
        Adapter Network 与 Kernel Scheduling 是独立观测，connected ≠ bound ≠ schedulable
        ≠ executing。Adapter Network 当前仍为 MOCK；Kernel Scheduling
        {{ schedulingIsMock ? "为显式 MOCK" : "来自 Kernel 语义投影" }}，前端不解析 raw
        Score。
      </template>
    </el-alert>

    <section class="worker-panel" aria-label="Worker 样本">
      <div
        v-if="store.resourceLoadStatus === 'loading'"
        class="panel-state"
        data-testid="group-loading"
      >
        <el-icon class="is-loading"><RefreshRight /></el-icon>
        正在读取配置的 WorkerGroup…
      </div>

      <div
        v-else-if="store.resourceLoadStatus === 'error'"
        class="panel-state panel-state--error"
        data-testid="group-error"
      >
        <el-icon><Warning /></el-icon>
        <div>
          <strong>{{ store.resourceLoadError?.title }}</strong>
          <p>{{ store.resourceLoadError?.message }}</p>
          <small v-if="store.resourceLoadError?.requestId">
            Request ID: {{ store.resourceLoadError.requestId }}
          </small>
        </div>
        <el-button @click="initializePage">重新读取目录</el-button>
      </div>

      <template v-else>
        <div class="worker-group-tabs" role="tablist" aria-label="WorkerGroup">
          <button
            v-for="workerGroupId in store.configuredWorkerGroupIds"
            :key="workerGroupId"
            type="button"
            class="worker-group-tab"
            :class="{
              'worker-group-tab--active': store.activeWorkerGroupId === workerGroupId,
              'worker-group-tab--missing': isMissing(workerGroupId)
            }"
            role="tab"
            :aria-selected="store.activeWorkerGroupId === workerGroupId"
            :data-testid="`group-tab-${workerGroupId}`"
            @click="selectGroup(workerGroupId)"
          >
            <span>
              <strong>{{ groupDisplayName(workerGroupId) }}</strong>
              <small>{{ workerGroupId }}</small>
            </span>
            <em v-if="isMissing(workerGroupId)">未找到</em>
            <em v-else-if="sampleCount(workerGroupId) !== undefined">
              {{ sampleCount(workerGroupId) }}
            </em>
          </button>
        </div>

        <div
          v-if="store.configuredWorkerGroupIds.length === 0"
          class="panel-state"
          data-testid="empty-configured-groups"
        >
          <el-icon><InfoFilled /></el-icon>
          <div>
            <strong>当前 Profile 没有配置 WorkerGroup</strong>
            <p>Runtime View 成功读取了一个空的配置资源目录。</p>
          </div>
        </div>

        <div
          v-else-if="store.activeWorkerGroupId && isMissing(store.activeWorkerGroupId)"
          class="panel-state panel-state--warning"
          data-testid="missing-group"
        >
          <el-icon><Warning /></el-icon>
          <div>
            <strong>WorkerGroup 未找到</strong>
            <p>
              配置中的 {{ store.activeWorkerGroupId }} 当前没有 Owner 描述符。这不表示
              Group 或 Worker 的网络或调度状态。
            </p>
          </div>
        </div>

        <template v-else-if="store.activeGroup">
          <div class="worker-toolbar">
            <el-input
              v-model="searchText"
              clearable
              :prefix-icon="Search"
              placeholder="筛选当前样本 Worker ID / 属性"
              aria-label="筛选当前样本"
            />
            <div class="worker-toolbar__actions">
              <span>
                当前筛选 <strong>{{ filteredWorkers.length }}</strong> /
                {{ store.activeSample?.returnedCount ?? 0 }}
              </span>
              <el-button
                :icon="Connection"
                :loading="isStatusRefreshing"
                :disabled="activeWorkers.length === 0"
                data-testid="refresh-status-button"
                @click="refreshCurrentStatus"
              >
                刷新状态
              </el-button>
              <el-tag effect="plain" type="warning" size="small">NETWORK MOCK</el-tag>
              <el-tag
                v-if="!schedulingIsMock"
                effect="plain"
                type="success"
                size="small"
              >
                SCHEDULING LIVE
              </el-tag>
            </div>
          </div>

          <div class="active-group-context">
            <span>
              <Tickets aria-hidden="true" />
              {{ groupDisplayName(store.activeGroup.workerGroupId) }}
            </span>
            <el-tag
              v-for="eventCode in store.activeGroup.eventCodes"
              :key="eventCode"
              size="small"
              effect="plain"
            >
              {{ eventCode }}
            </el-tag>
          </div>

          <el-alert
            v-if="store.activeSampleState?.stale"
            class="stale-sample-alert"
            type="warning"
            :closable="false"
            show-icon
            data-testid="stale-sample"
          >
            <template #title>
              刷新失败，仍显示上一次内存样本；该样本已标记为陈旧。
              <span v-if="store.activeSampleState.error?.requestId">
                Request ID: {{ store.activeSampleState.error.requestId }}
              </span>
            </template>
          </el-alert>

          <div
            v-if="store.activeSampleState?.status === 'error' && !store.activeSample"
            class="panel-state panel-state--error panel-state--sample"
            data-testid="sample-error"
          >
            <el-icon><Warning /></el-icon>
            <div>
              <strong>{{ store.activeSampleState.error?.title }}</strong>
              <p>{{ store.activeSampleState.error?.message }}</p>
              <small v-if="store.activeSampleState.error?.requestId">
                Request ID: {{ store.activeSampleState.error.requestId }}
              </small>
            </div>
          </div>

          <div
            v-else-if="isInitialSampleLoading"
            class="panel-state panel-state--sample"
            data-testid="sample-loading"
          >
            <el-icon class="is-loading"><RefreshRight /></el-icon>
            正在随机采样当前 WorkerGroup…
          </div>

          <div
            v-else-if="store.activeSample?.sampledCount === 0"
            class="panel-state panel-state--sample"
            data-testid="empty-sample"
          >
            <el-icon><InfoFilled /></el-icon>
            <div>
              <strong>当前随机样本为空</strong>
              <p>Owner 成功读取了一个空 Worker HASH；这不是分页结束或完整性证明。</p>
            </div>
          </div>

          <div
            v-else-if="store.activeSample && filteredWorkers.length === 0"
            class="panel-state panel-state--sample"
            data-testid="filtered-empty"
          >
            <el-icon><Search /></el-icon>
            当前样本中没有匹配项；搜索不会调用后端 Filter DSL。
          </div>

          <template v-else-if="store.activeSample">
            <div class="worker-table-wrap">
              <el-table
                :data="filteredWorkers"
                row-key="workerId"
                class="worker-table"
                data-testid="worker-table"
              >
                <el-table-column prop="workerId" label="WORKER ID" min-width="238">
                  <template #default="{ row }"
                    ><code>{{ row.workerId }}</code></template
                  >
                </el-table-column>
                <el-table-column
                  prop="endpointManagerId"
                  label="ENDPOINT MANAGER"
                  min-width="180"
                />
                <el-table-column label="ADAPTER NETWORK · MOCK" min-width="185">
                  <template #default="{ row }">
                    <div class="worker-status-cell">
                      <el-tag
                        :type="
                          axisTone(statusEntry(row).network, networkPresentation(row))
                        "
                        effect="light"
                        size="small"
                      >
                        {{
                          axisLabel(statusEntry(row).network, networkPresentation(row))
                        }}
                      </el-tag>
                      <small v-if="axisAuxiliary(statusEntry(row).network)">
                        {{ axisAuxiliary(statusEntry(row).network) }}
                      </small>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  :label="
                    schedulingIsMock ? 'KERNEL SCHEDULING · MOCK' : 'KERNEL SCHEDULING'
                  "
                  min-width="195"
                >
                  <template #default="{ row }">
                    <div class="worker-status-cell">
                      <el-tag
                        :type="
                          axisTone(
                            statusEntry(row).scheduling,
                            schedulingPresentation(row)
                          )
                        "
                        effect="light"
                        size="small"
                      >
                        {{
                          axisLabel(
                            statusEntry(row).scheduling,
                            schedulingPresentation(row)
                          )
                        }}
                      </el-tag>
                      <small v-if="axisAuxiliary(statusEntry(row).scheduling)">
                        {{ axisAuxiliary(statusEntry(row).scheduling) }}
                      </small>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="WORKER PROPERTIES" min-width="220">
                  <template #default="{ row }">
                    <span class="attribute-preview">{{
                      compactJson(row.workerProperties)
                    }}</span>
                  </template>
                </el-table-column>
                <el-table-column width="96" align="right">
                  <template #default="{ row }">
                    <el-button
                      link
                      type="primary"
                      :aria-label="`查看 ${row.workerId} 详情`"
                      @click="openDetails(row)"
                    >
                      详情
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="worker-mobile-list" data-testid="worker-mobile-list">
              <article
                v-for="worker in filteredWorkers"
                :key="worker.workerId"
                class="worker-mobile-card"
              >
                <div class="worker-mobile-card__heading">
                  <code>{{ worker.workerId }}</code>
                  <el-button link type="primary" @click="openDetails(worker)"
                    >详情</el-button
                  >
                </div>
                <dl>
                  <div>
                    <dt>Endpoint</dt>
                    <dd>{{ worker.endpointManagerId }}</dd>
                  </div>
                  <div>
                    <dt>Network</dt>
                    <dd>
                      {{
                        axisLabel(
                          statusEntry(worker).network,
                          networkPresentation(worker)
                        )
                      }}
                    </dd>
                  </div>
                  <div>
                    <dt>Scheduling</dt>
                    <dd>
                      {{
                        axisLabel(
                          statusEntry(worker).scheduling,
                          schedulingPresentation(worker)
                        )
                      }}
                    </dd>
                  </div>
                  <div>
                    <dt>Properties</dt>
                    <dd>{{ compactJson(worker.workerProperties) }}</dd>
                  </div>
                </dl>
              </article>
            </div>

            <footer class="sample-footnote">
              当前筛选 {{ filteredWorkers.length }} / 有效描述符
              {{ store.activeSample.returnedCount }}；不可读描述符
              {{ store.activeSample.unreadableCount }}。本页没有 cursor、总数或下一页。
            </footer>
          </template>
        </template>
      </template>
    </section>

    <el-drawer
      v-model="detailsOpen"
      class="worker-detail-drawer"
      size="min(560px, 100%)"
      destroy-on-close
      @closed="selectedWorker = undefined"
    >
      <template #header>
        <div v-if="selectedWorker" class="worker-drawer-heading">
          <span>WORKER OBSERVATION</span>
          <strong>{{ selectedWorker.workerId }}</strong>
        </div>
      </template>
      <div v-if="selectedWorker" class="worker-detail" data-testid="worker-detail">
        <section>
          <h2>Identity</h2>
          <dl class="worker-detail__identity">
            <div>
              <dt>Worker ID</dt>
              <dd>{{ selectedWorker.workerId }}</dd>
            </div>
            <div>
              <dt>WorkerGroup</dt>
              <dd>{{ selectedWorker.workerGroupId }}</dd>
            </div>
            <div>
              <dt>Endpoint Manager</dt>
              <dd>{{ selectedWorker.endpointManagerId }}</dd>
            </div>
          </dl>
        </section>

        <section class="worker-status-detail">
          <div class="worker-status-detail__heading">
            <div>
              <h2>Adapter Network</h2>
              <p>Adapter Route 的独立观测投影</p>
            </div>
            <el-tag effect="plain" type="warning" size="small">MOCK</el-tag>
          </div>
          <div class="worker-status-card">
            <div class="worker-status-card__state">
              <el-tag
                :type="
                  axisTone(
                    statusEntry(selectedWorker).network,
                    networkPresentation(selectedWorker)
                  )
                "
                effect="light"
              >
                {{
                  axisLabel(
                    statusEntry(selectedWorker).network,
                    networkPresentation(selectedWorker)
                  )
                }}
              </el-tag>
              <div>
                <strong>Network State</strong>
                <small>{{
                  networkPresentation(selectedWorker)?.description ??
                  "尚无可展示的 Adapter Network 观测。"
                }}</small>
              </div>
            </div>
            <dl>
              <div>
                <dt>Endpoint Manager</dt>
                <dd>{{ selectedWorker.endpointManagerId }}</dd>
              </div>
              <div>
                <dt>Read At</dt>
                <dd>
                  {{
                    formattedTime(
                      statusEntry(selectedWorker).network.observation?.readAt,
                      "尚未观测"
                    )
                  }}
                </dd>
              </div>
              <div v-if="axisAuxiliary(statusEntry(selectedWorker).network)">
                <dt>Observation</dt>
                <dd>{{ axisAuxiliary(statusEntry(selectedWorker).network) }}</dd>
              </div>
            </dl>
          </div>
        </section>

        <section class="worker-status-detail">
          <div class="worker-status-detail__heading">
            <div>
              <h2>Kernel Scheduling</h2>
              <p>Worker Score 的语义化投影，不暴露 raw Score</p>
            </div>
            <el-tag
              effect="plain"
              :type="schedulingIsMock ? 'warning' : 'success'"
              size="small"
            >
              {{ schedulingIsMock ? "MOCK" : "LIVE" }}
            </el-tag>
          </div>
          <div class="worker-status-card">
            <div class="worker-status-card__state">
              <el-tag
                :type="
                  axisTone(
                    statusEntry(selectedWorker).scheduling,
                    schedulingPresentation(selectedWorker)
                  )
                "
                effect="light"
              >
                {{
                  axisLabel(
                    statusEntry(selectedWorker).scheduling,
                    schedulingPresentation(selectedWorker)
                  )
                }}
              </el-tag>
              <div>
                <strong>Scheduling State</strong>
                <small>{{
                  schedulingPresentation(selectedWorker)?.description ??
                  "尚无可展示的 Kernel Scheduling 观测。"
                }}</small>
              </div>
            </div>
            <dl>
              <div>
                <dt>Read At</dt>
                <dd>
                  {{
                    formattedTime(
                      statusEntry(selectedWorker).scheduling.observation?.readAt,
                      "尚未观测"
                    )
                  }}
                </dd>
              </div>
              <div v-if="axisAuxiliary(statusEntry(selectedWorker).scheduling)">
                <dt>Observation</dt>
                <dd>{{ axisAuxiliary(statusEntry(selectedWorker).scheduling) }}</dd>
              </div>
            </dl>
          </div>
        </section>

        <el-button
          class="worker-status-refresh"
          :icon="RefreshRight"
          :loading="workerStatus.isLoading([selectedWorker])"
          @click="refreshSelectedWorkerStatus"
        >
          刷新该 Worker 状态
        </el-button>

        <section>
          <h2>Worker properties</h2>
          <JsonBlock :value="selectedWorker.workerProperties" />
        </section>
        <section>
          <h2>Platform properties</h2>
          <JsonBlock :value="selectedWorker.platformProperties" />
        </section>
        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            Connected 不证明 Binding、Schedulable 或 Executing；HOT Held 不证明Worker
            正在执行；HOT Due也不包含当前Kernel epoch或匹配策略结论。
          </template>
        </el-alert>
      </div>
    </el-drawer>
  </section>
</template>
