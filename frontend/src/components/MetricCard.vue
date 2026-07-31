<script setup lang="ts">
import type { Component } from "vue";

withDefaults(
  defineProps<{
    label: string;
    value: string | number;
    hint: string;
    icon: Component;
    tone?: "primary" | "info" | "success" | "warning";
    testId?: string;
  }>(),
  {
    tone: "primary",
    testId: undefined
  }
);
</script>

<template>
  <article class="metric-card" :class="`metric-card--${tone}`" :data-testid="testId">
    <div>
      <span class="metric-card__label">{{ label }}</span>
      <div class="metric-card__value-row">
        <strong class="metric-card__value">{{ value }}</strong>
        <span>{{ hint }}</span>
      </div>
    </div>
    <span class="metric-card__icon" aria-hidden="true">
      <el-icon><component :is="icon" /></el-icon>
    </span>
  </article>
</template>
