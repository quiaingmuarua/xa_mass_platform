<script setup lang="ts">
import { useRoute } from "vue-router";
import { ElEmpty } from "element-plus";
import "element-plus/es/components/empty/style/css";
import { isPublicRoute } from "@/router/access";
import LoginView from "./LoginView.vue";
import UserManagementView from "./UserManagementView.vue";
import CreditsView from "./CreditsView.vue";
import ConsoleSessionProvider from "@/layouts/ConsoleSessionProvider.vue";
const route = useRoute();
</script>
<template>
  <LoginView v-if="isPublicRoute(route)" />
  <ConsoleSessionProvider v-else>
    <UserManagementView v-if="route.query.view === 'users'" />
    <CreditsView v-else-if="route.query.view === 'credits'" />
    <el-empty v-else description="尚未分配可用的业务 Project，请联系管理员。" />
  </ConsoleSessionProvider>
</template>
