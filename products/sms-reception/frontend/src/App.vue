<script setup lang="ts">
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { onMounted } from "vue";
import { useThemeStore } from "@/stores/theme";
const theme = useThemeStore();
onMounted(() => theme.apply());
</script>
<template>
  <el-config-provider :locale="zhCn"><router-view /></el-config-provider>
</template>
