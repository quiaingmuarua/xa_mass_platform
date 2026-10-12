<script setup lang="ts">
import { ElDescriptions, ElDescriptionsItem } from "element-plus";
import "element-plus/es/components/descriptions/style/css";
import "element-plus/es/components/descriptions-item/style/css";
import { configurationSources, type ConfigurationSection } from "./configuration";
defineProps<{ section: ConfigurationSection }>();
</script>
<template>
  <div class="checks-configuration-rows">
    <p class="checks-hint checks-configuration-source">
      来源：{{ configurationSources[section.source] }} · 范围：{{ section.scope }}
    </p>
    <p v-if="section.unavailable" class="checks-configuration-gap">
      {{ section.unavailable }}
    </p>
    <ElDescriptions v-if="section.rows.length" :column="2" border size="small">
      <ElDescriptionsItem
        v-for="row in section.rows"
        :key="row.label"
        :label="row.label"
        >{{ row.value }}</ElDescriptionsItem
      >
    </ElDescriptions>
  </div>
</template>
