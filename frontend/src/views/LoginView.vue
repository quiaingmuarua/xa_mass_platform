<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { consoleIdentity } from "@/console-identity";
import { roles } from "@/access-policy";
import { loginDestination } from "@/router/access";
const router = useRouter(),
  route = useRoute();
const selected = ref("");
const error = ref("");
onMounted(() => consoleIdentity.load(true));
async function login() {
  error.value = "";
  try {
    if (!(await consoleIdentity.load(true))) return;
    consoleIdentity.login(selected.value);
    await router.replace(loginDestination(router, route.query.redirect));
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "无法进入控制台";
  }
}
</script>
<template>
  <main class="console-login">
    <form class="console-login__card" @submit.prevent="login">
      <img :src="'/logo.svg'" width="42" height="42" alt="" />
      <p class="console-login__brand">XA MASS · CONSOLE</p>
      <h1>登录控制台</h1>
      <p>选择用户，以对应角色和 Project 范围进入开发环境。</p>
      <label class="console-login__field"
        >当前用户
        <el-select
          v-model="selected"
          aria-label="选择用户"
          placeholder="请选择用户"
          :loading="consoleIdentity.loading.value"
        >
          <el-option
            v-for="user in consoleIdentity.users"
            :key="user.userId"
            :value="user.userId"
            :label="`${user.displayName} · ${user.userId} · ${roles[user.role].label}`"
          />
        </el-select>
      </label>
      <el-alert
        v-if="consoleIdentity.error.value"
        :title="consoleIdentity.error.value"
        type="error"
        :closable="false"
        role="alert"
      />
      <el-button v-if="consoleIdentity.error.value" @click="consoleIdentity.load(true)"
        >重试用户目录</el-button
      >
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      />
      <el-button
        type="primary"
        native-type="submit"
        :disabled="
          !selected || consoleIdentity.loading.value || !!consoleIdentity.error.value
        "
        >进入控制台</el-button
      >
    </form>
  </main>
</template>
<style scoped>
.console-login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--rv-bg);
  color: var(--rv-text);
}
.console-login__card {
  width: min(100%, 420px);
  padding: 36px;
  border: 1px solid var(--rv-border);
  border-radius: 16px;
  background: var(--rv-surface);
  display: grid;
  gap: 20px;
}
.console-login__card h1,
.console-login__card p {
  margin: 0;
}
.console-login__brand {
  letter-spacing: 0.12em;
  font-size: 12px;
}
.console-login__field {
  display: grid;
  gap: 10px;
}
</style>
