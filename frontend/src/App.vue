<script setup lang="ts">
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { onMounted, provide } from "vue";
import { consoleIdentity } from "@/console-identity";
import { createConsoleMock, consoleMockKey } from "@/console-mock";
import { useThemeStore } from "@/stores/theme";
const theme = useThemeStore();
onMounted(() => theme.apply());
if (import.meta.env.VITE_RUNTIME_DATA_SOURCE === "mock")
  provide(consoleMockKey, createConsoleMock());
</script>
<template>
  <el-config-provider :locale="zhCn">
    <router-view v-slot="{ Component, route }">
      <component
        :is="Component"
        v-if="route.meta.public || consoleIdentity.current.value"
        :key="route.meta.public ? 'public' : consoleIdentity.generation.value"
      />
    </router-view>
  </el-config-provider>
</template>
