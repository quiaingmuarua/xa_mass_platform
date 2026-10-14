<script setup lang="ts">
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { onMounted, provide } from "vue";
import { consoleIdentity } from "@/console-identity";
import { createConsoleMock, consoleMockKey } from "@/console-mock";
import { useThemeStore } from "@/stores/theme";
import { isPublicRoute } from "@/router/access";
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
        v-if="isPublicRoute(route) || consoleIdentity.current.value"
        :key="isPublicRoute(route) ? 'public' : consoleIdentity.generation.value"
      />
    </router-view>
  </el-config-provider>
</template>
