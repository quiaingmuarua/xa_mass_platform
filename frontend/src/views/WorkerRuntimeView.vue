<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  CircleCheck,
  Collection,
  DataAnalysis,
  InfoFilled,
  RefreshRight,
  Search,
  Tickets,
  Warning
} from "@element-plus/icons-vue";

import JsonBlock from "@/components/JsonBlock.vue";
import MetricCard from "@/components/MetricCard.vue";
import { useRuntimeViewerStore } from "@/runtime-context";
import { filterCurrentSample } from "@/runtime-viewer/filter";
import type { JsonValue, WorkerView } from "@/runtime-viewer/types";

const store = useRuntimeViewerStore();
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

watch(
  () => store.activeWorkerGroupId,
  () => {
    searchText.value = "";
    closeDetails();
  }
);

onMounted(() => {
  void store.initialize();
});

onBeforeUnmount(() => {
  store.dispose();
});

async function selectGroup(workerGroupId: string): Promise<void> {
  closeDetails();
  await store.selectGroup(workerGroupId);
}

async function refreshSample(): Promise<void> {
  closeDetails();
  await store.refreshActiveGroup();
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

function formattedTime(value?: string): string {
  if (value === undefined) {
    return "尚未采样";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(new Date(value));
}
</script>

<template>
  <section class="worker-page" data-testid="worker-runtime-page">
    <header class="worker-page__heading">
      <div>
        <p class="worker-page__eyebrow">RUNTIME / WORKERS</p>
        <h1>Worker Runtime</h1>
        <p>Worker 声明与能力的只读随机预览</p>
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

    <div class="metric-grid" aria-label="Worker 样本指标">
      <MetricCard
        label="已配置 Group"
        :value="store.configuredWorkerGroupIds.length"
        hint="environment IDs"
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
        label="有效描述符"
        :value="store.activeSample?.returnedCount ?? '—'"
        hint="decoded rows"
        :icon="CircleCheck"
        tone="success"
        test-id="metric-returned"
      />
      <MetricCard
        label="不可读描述符"
        :value="store.activeSample?.unreadableCount ?? '—'"
        hint="not refilled"
        :icon="Warning"
        tone="warning"
        test-id="metric-unreadable"
      />
    </div>

    <el-alert class="runtime-semantics" type="info" :closable="false" show-icon>
      <template #title>
        <strong>样本语义：</strong>
        每次只对当前 WorkerGroup
        执行一次随机采样；结果不承诺顺序、稳定性、完整性或总量。
        页面不推断在线状态、Score、lease 或 Transport 会话。
      </template>
    </el-alert>

    <section class="worker-panel" aria-label="Worker 样本">
      <div
        v-if="store.groupLoadStatus === 'loading'"
        class="panel-state"
        data-testid="group-loading"
      >
        <el-icon class="is-loading"><RefreshRight /></el-icon>
        正在读取配置的 WorkerGroup…
      </div>

      <div
        v-else-if="store.groupLoadStatus === 'error'"
        class="panel-state panel-state--error"
        data-testid="group-error"
      >
        <el-icon><Warning /></el-icon>
        <div>
          <strong>{{ store.groupLoadError?.title }}</strong>
          <p>{{ store.groupLoadError?.message }}</p>
          <small v-if="store.groupLoadError?.requestId">
            Request ID: {{ store.groupLoadError.requestId }}
          </small>
        </div>
        <el-button @click="store.initialize">重新读取 Group</el-button>
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
          v-if="store.activeWorkerGroupId && isMissing(store.activeWorkerGroupId)"
          class="panel-state panel-state--warning"
          data-testid="missing-group"
        >
          <el-icon><Warning /></el-icon>
          <div>
            <strong>WorkerGroup 未找到</strong>
            <p>
              配置中的 {{ store.activeWorkerGroupId }} 当前没有 Owner 描述符。这不表示
              Group 或 Worker 离线。
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
            <span>
              当前筛选
              <strong>{{ filteredWorkers.length }}</strong>
              /
              {{ store.activeSample?.returnedCount ?? 0 }}
            </span>
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
                Request ID:
                {{ store.activeSampleState.error.requestId }}
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
                Request ID:
                {{ store.activeSampleState.error.requestId }}
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
                  <template #default="{ row }">
                    <code>{{ row.workerId }}</code>
                  </template>
                </el-table-column>
                <el-table-column label="WORKER GROUP" min-width="220">
                  <template #default="{ row }">
                    <div class="group-cell">
                      <span>
                        {{
                          groupDisplayName(row.workerGroupId)
                            .split(" ")
                            .map((value) => value[0])
                            .join("")
                        }}
                      </span>
                      <div>
                        <strong>{{ row.workerGroupId }}</strong>
                        <small>
                          {{ groupDisplayName(row.workerGroupId) }}
                        </small>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="endpointManagerId"
                  label="ENDPOINT MANAGER"
                  min-width="170"
                />
                <el-table-column label="WORKER PROPERTIES" min-width="210">
                  <template #default="{ row }">
                    <span class="attribute-preview">
                      {{ compactJson(row.workerProperties) }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="PLATFORM PROPERTIES" min-width="210">
                  <template #default="{ row }">
                    <span class="attribute-preview">
                      {{ compactJson(row.platformProperties) }}
                    </span>
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
                  <el-button
                    link
                    type="primary"
                    :aria-label="`查看 ${worker.workerId} 详情`"
                    @click="openDetails(worker)"
                  >
                    详情
                  </el-button>
                </div>
                <dl>
                  <div>
                    <dt>Endpoint</dt>
                    <dd>{{ worker.endpointManagerId }}</dd>
                  </div>
                  <div>
                    <dt>Worker properties</dt>
                    <dd>{{ compactJson(worker.workerProperties) }}</dd>
                  </div>
                  <div>
                    <dt>Platform properties</dt>
                    <dd>{{ compactJson(worker.platformProperties) }}</dd>
                  </div>
                </dl>
              </article>
            </div>

            <footer class="sample-footnote">
              当前显示 {{ filteredWorkers.length }} 个有效描述符；本页没有
              cursor、总数或下一页。
            </footer>
          </template>
        </template>
      </template>
    </section>

    <el-drawer
      v-model="detailsOpen"
      class="worker-detail-drawer"
      size="min(520px, 100%)"
      destroy-on-close
      @closed="selectedWorker = undefined"
    >
      <template #header>
        <div v-if="selectedWorker" class="worker-drawer-heading">
          <span>WORKER DESCRIPTOR</span>
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
            详情只包含 WorkerResourceCatalog 描述符字段，不包含 Score、
            lease、连接或执行状态。
          </template>
        </el-alert>
      </div>
    </el-drawer>
  </section>
</template>
