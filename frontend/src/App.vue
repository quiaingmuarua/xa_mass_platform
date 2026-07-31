<script setup lang="ts">
import zhCn from "element-plus/es/locale/lang/zh-cn";

import ConfigErrorView from "@/views/ConfigErrorView.vue";
import type { RuntimeViewerConfigResult } from "@/runtime-viewer/types";

defineProps<{
  configResult: RuntimeViewerConfigResult;
}>();
</script>

<template>
  <el-config-provider :locale="zhCn">
    <ConfigErrorView v-if="!configResult.ok" :error="configResult.error" />
    <router-view v-else />
  </el-config-provider>
</template>
