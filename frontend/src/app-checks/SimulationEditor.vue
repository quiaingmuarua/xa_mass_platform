<script setup lang="ts">
import type { Simulation } from "./model";
const model = defineModel<Simulation>({ required: true });
const ranges = {
  registered: "已注册",
  unregistered: "未注册",
  failed: "查询失败"
} as const;
</script>
<template>
  <div class="checks-simulation-editor">
    <p class="checks-hint">
      区间左闭右开，三个区间需互斥并覆盖 0..1000；这是模拟参数，不是结果预测。
    </p>
    <div v-for="(label, key) in ranges" :key="key" class="checks-config-input-row">
      <strong>{{ label }}区间</strong>
      <label class="checks-field"
        >起点<input
          v-model.number="model.ranges[key][0]"
          :aria-label="label + '区间起点'"
          type="number"
          min="0"
          max="1000"
          step="1"
      /></label>
      <label class="checks-field"
        >终点<input
          v-model.number="model.ranges[key][1]"
          :aria-label="label + '区间终点'"
          type="number"
          min="0"
          max="1000"
          step="1"
      /></label>
    </div>
    <div class="checks-config-input-row">
      <strong>模拟延迟</strong>
      <label class="checks-field"
        >最小值（ms）<input
          v-model.number="model.delayMs[0]"
          aria-label="最小模拟延迟"
          type="number"
          min="0"
          max="30000"
          step="1"
      /></label>
      <label class="checks-field"
        >最大值（ms）<input
          v-model.number="model.delayMs[1]"
          aria-label="最大模拟延迟"
          type="number"
          min="0"
          max="30000"
          step="1"
      /></label>
    </div>
  </div>
</template>
