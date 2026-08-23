<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { CircleCheck, InfoFilled, Tickets, Warning } from "@element-plus/icons-vue";

import FiniteTaskManagement from "@/components/FiniteTaskManagement.vue";
import JsonBlock from "@/components/JsonBlock.vue";
import MetricCard from "@/components/MetricCard.vue";
import { useRuntimeViewerStore } from "@/runtime-context";
import type { ConfiguredRuntimeResourceEntry } from "@/runtime-viewer/types";

const store = useRuntimeViewerStore();
const selectedEntry = ref<ConfiguredRuntimeResourceEntry>();
const detailsOpen = ref(false);
const activeTab = ref<"finite" | "configured">("finite");

const readableTaskCount = computed(
  () => store.entries.filter((entry) => entry.task !== null).length
);
const missingTaskCount = computed(
  () => store.entries.filter((entry) => entry.task === null).length
);

onMounted(() => {
  void store.initialize();
});

function openDetails(entry: ConfiguredRuntimeResourceEntry): void {
  selectedEntry.value = entry;
  detailsOpen.value = true;
}
</script>

<template>
  <section class="task-page" data-testid="task-runtime-page">
    <header class="worker-page__heading">
      <div>
        <p class="worker-page__eyebrow">RUNTIME / TASKS</p>
        <h1>Tasks</h1>
        <p>有限 Task 交互 Mock 与当前 Profile 长期 Task 只读目录</p>
      </div>
    </header>

    <nav class="task-page-tabs" aria-label="Task 页面分区">
      <button
        type="button"
        class="task-page-tab"
        :class="{ 'is-active': activeTab === 'finite' }"
        @click="activeTab = 'finite'"
      >
        Finite Tasks <span>MOCK</span>
      </button>
      <button
        type="button"
        class="task-page-tab"
        :class="{ 'is-active': activeTab === 'configured' }"
        @click="activeTab = 'configured'"
      >
        Configured Tasks <span>READ-ONLY</span>
      </button>
    </nav>

    <FiniteTaskManagement v-if="activeTab === 'finite'" />

    <template v-else>
      <div
        class="task-metric-grid task-metric-grid--configured"
        aria-label="Task 描述符指标"
      >
        <MetricCard
          label="配置 Task"
          :value="store.entries.length"
          hint="configured entries"
          :icon="Tickets"
          tone="primary"
          test-id="metric-configured-tasks"
        />
        <MetricCard
          label="有效描述符"
          :value="readableTaskCount"
          hint="owner descriptors"
          :icon="CircleCheck"
          tone="success"
          test-id="metric-readable-tasks"
        />
        <MetricCard
          label="缺失描述符"
          :value="missingTaskCount"
          hint="configured but missing"
          :icon="Warning"
          tone="warning"
          test-id="metric-missing-tasks"
        />
      </div>

      <el-alert class="runtime-semantics" type="info" :closable="false" show-icon>
        <template #title>
          本页只展示 Profile 明确配置的长期 Task
          及其资源描述符；不表示批准状态、运行状态、 Item 数量、成功率或调度进度。
        </template>
      </el-alert>

      <section class="worker-panel" aria-label="配置 Task">
        <div
          v-if="store.resourceLoadStatus === 'loading'"
          class="panel-state"
          data-testid="task-loading"
        >
          <el-icon class="is-loading"><Tickets /></el-icon>
          正在读取配置资源目录…
        </div>

        <div
          v-else-if="store.resourceLoadStatus === 'error'"
          class="panel-state panel-state--error"
          data-testid="task-error"
        >
          <el-icon><Warning /></el-icon>
          <div>
            <strong>{{ store.resourceLoadError?.title }}</strong>
            <p>{{ store.resourceLoadError?.message }}</p>
            <small v-if="store.resourceLoadError?.requestId">
              Request ID: {{ store.resourceLoadError.requestId }}
            </small>
          </div>
          <el-button @click="store.initialize">重新读取目录</el-button>
        </div>

        <div
          v-else-if="store.entries.length === 0"
          class="panel-state"
          data-testid="empty-tasks"
        >
          <el-icon><InfoFilled /></el-icon>
          <div>
            <strong>当前 Profile 没有配置长期 Task</strong>
            <p>Runtime View 成功读取了一个空的配置资源目录。</p>
          </div>
        </div>

        <div v-else class="task-table-wrap">
          <el-table :data="store.entries" row-key="taskId" class="task-table">
            <el-table-column label="TASK ID" min-width="300">
              <template #default="{ row }">
                <code>{{ row.taskId }}</code>
              </template>
            </el-table-column>
            <el-table-column label="WORKER GROUP" min-width="250">
              <template #default="{ row }">
                <span class="task-group-id">{{ row.workerGroupId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="DESCRIPTOR" width="125">
              <template #default="{ row }">
                <el-tag v-if="row.task" type="success" effect="plain" size="small">
                  Available
                </el-tag>
                <el-tag v-else type="warning" effect="plain" size="small">
                  Missing
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="ALLOCATION" min-width="190">
              <template #default="{ row }">
                {{ row.task?.workerAllocationMechanism ?? "—" }}
              </template>
            </el-table-column>
            <el-table-column label="PRIORITY" width="92" align="center">
              <template #default="{ row }">
                {{ row.task?.config.priority ?? "—" }}
              </template>
            </el-table-column>
            <el-table-column label="CANDIDATES" width="112" align="center">
              <template #default="{ row }">
                {{ row.task?.config.maximumCandidateWorkers ?? "—" }}
              </template>
            </el-table-column>
            <el-table-column label="RETRIES" width="92" align="center">
              <template #default="{ row }">
                {{ row.task?.config.maxRetryTimes ?? "—" }}
              </template>
            </el-table-column>
            <el-table-column label="IDLE" min-width="150">
              <template #default="{ row }">
                <code>{{ row.task?.idleDisposition ?? "—" }}</code>
              </template>
            </el-table-column>
            <el-table-column width="88" align="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetails(row)">
                  详情
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <el-drawer
        v-model="detailsOpen"
        class="worker-detail-drawer"
        size="min(520px, 100%)"
        destroy-on-close
        @closed="selectedEntry = undefined"
      >
        <template #header>
          <div v-if="selectedEntry" class="worker-drawer-heading">
            <span>TASK DESCRIPTOR</span>
            <strong>{{ selectedEntry.taskId }}</strong>
          </div>
        </template>

        <div v-if="selectedEntry" class="worker-detail" data-testid="task-detail">
          <section>
            <h2>Identity</h2>
            <dl class="worker-detail__identity">
              <div>
                <dt>Task ID</dt>
                <dd>{{ selectedEntry.taskId }}</dd>
              </div>
              <div>
                <dt>WorkerGroup</dt>
                <dd>{{ selectedEntry.workerGroupId }}</dd>
              </div>
              <div>
                <dt>Worker allocation</dt>
                <dd>
                  {{ selectedEntry.task?.workerAllocationMechanism ?? "描述符缺失" }}
                </dd>
              </div>
              <div>
                <dt>Idle disposition</dt>
                <dd>{{ selectedEntry.task?.idleDisposition ?? "描述符缺失" }}</dd>
              </div>
            </dl>
          </section>

          <template v-if="selectedEntry.task">
            <section>
              <h2>Allocation rule</h2>
              <JsonBlock :value="selectedEntry.task.allocationRule" />
            </section>
            <section>
              <h2>Config</h2>
              <JsonBlock :value="selectedEntry.task.config" />
            </section>
          </template>

          <el-alert v-else type="warning" :closable="false" show-icon>
            <template #title>
              Profile 保留了该 Task 坐标，但 Owner 当前没有返回描述符。
            </template>
          </el-alert>
        </div>
      </el-drawer>
    </template>
  </section>
</template>
