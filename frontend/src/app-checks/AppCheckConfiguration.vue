<script setup lang="ts">
import { computed, ref } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import { appLabel, type Catalog } from "./model";
import {
  applicationConfiguration,
  scenarioConfiguration,
  type ConfigurationReadState
} from "./configuration";
import { timestamp } from "./workbench";
import ConfigurationRows from "./ConfigurationRows.vue";
import SimulationSummary from "./SimulationSummary.vue";
const props = defineProps<{
  catalog: Catalog;
  appId: string;
  mode: "api" | "mock";
  read: ConfigurationReadState;
  returnToCreate: boolean;
}>();
const emit = defineEmits<{ select: [appId: string]; refresh: []; returnToDraft: [] }>();
const configuration = computed(() =>
  applicationConfiguration(props.catalog, props.appId, props.mode)
);
const common = computed(() => scenarioConfiguration(props.catalog, props.mode));
const heading = ref<HTMLElement>();
defineExpose({ focusHeading: () => heading.value?.focus({ preventScroll: true }) });
</script>
<template>
  <section class="checks-configuration-page" aria-label="应用与运行策略">
    <header class="checks-heading">
      <h1 ref="heading" tabindex="-1">应用与运行策略</h1>
      <div class="checks-list-controls">
        <div class="checks-actions">
          <el-button v-if="returnToCreate" @click="emit('returnToDraft')"
            >返回创建草稿</el-button
          >
          <el-button :icon="Refresh" :loading="read.busy" @click="emit('refresh')"
            >刷新配置</el-button
          >
        </div>
        <small class="checks-hint">{{
          read.readAt ? "最近成功读取 " + timestamp(read.readAt) : "已载入配置"
        }}</small>
      </div>
    </header>
    <p v-if="read.error" class="checks-error" role="alert">
      {{ read.error }} 已保留上次快照，配置可能已过期。
    </p>
    <p class="checks-hint checks-configuration-mode">
      {{
        mode === "mock"
          ? "Mock 演示数据 · 只读，不代表实际运行配置"
          : "只读 · 仅展示当前接口提供的信息"
      }}
    </p>
    <section
      class="checks-surface checks-configuration-sections"
      aria-label="App Checks 通用配置"
    >
      <h2>App Checks 通用配置</h2>
      <p class="checks-hint">所有应用共用的场景规则，切换应用不会改变这部分配置。</p>
      <ConfigurationRows :section="common" />
      <details class="checks-configuration-simulation">
        <summary>共用模拟默认参数</summary>
        <p class="checks-hint">
          当前 Catalog
          为所有应用提供同一份默认参数。新草稿复制此默认值，已有草稿和任务保持各自的保存值。
        </p>
        <SimulationSummary
          :value="catalog.simulationExample"
          :source="mode === 'mock' ? 'mock' : 'catalog'"
          scope="场景"
        />
      </details>
    </section>
    <section
      class="checks-surface checks-configuration-sections"
      aria-label="App 运行配置"
    >
      <h2>App 运行配置</h2>
      <p class="checks-hint">
        按应用查看 WorkerGroup 绑定，以及对应 Pool / Group 的运行规则。
      </p>
      <div class="checks-configuration-selector">
        <label class="checks-field"
          >应用<select
            :value="appId"
            aria-label="配置应用"
            @change="emit('select', ($event.target as HTMLSelectElement).value)"
          >
            <option v-if="!configuration" :value="appId" disabled>
              请选择可用应用
            </option>
            <option v-for="app in catalog.apps" :key="app.appId" :value="app.appId">
              {{ appLabel(app.appId) }}
            </option>
          </select></label
        >
      </div>
      <p v-if="!configuration" role="alert" class="checks-configuration-gap">
        应用「{{ appId }}」不在当前 Catalog 中，请重新选择。
      </p>
      <div v-else class="checks-application-sections">
        <section>
          <ConfigurationRows :section="configuration.binding" />
        </section>
        <section>
          <h3>关联 Pool 规则</h3>
          <ConfigurationRows :section="configuration.pool" />
          <p v-if="mode === 'mock'" class="checks-hint">
            对 Group 内单个 Worker 的观察筛选，在入池时判断资格；观察阈值不是 Project
            配额、Group 总额度或严格吞吐保证。演示不据此模拟调度。
          </p>
        </section>
      </div>
    </section>
  </section>
</template>
