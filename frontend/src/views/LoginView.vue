<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { consoleIdentity } from "@/console-identity";
import { loginDestination } from "@/router/access";
const router = useRouter(),
  route = useRoute();
const accessKey = ref("");
const error = ref("");
const mock = import.meta.env.VITE_RUNTIME_DATA_SOURCE === "mock";
/** Retries restoring the stored key after a transient directory failure. */
async function retry() {
  if ((await consoleIdentity.load(true)) && consoleIdentity.current.value)
    await router.replace(loginDestination(router, route.query.redirect));
}
async function login() {
  error.value = "";
  try {
    await consoleIdentity.login(accessKey.value);
    accessKey.value = "";
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
      <p>输入管理员为你签发的访问密钥。角色和 Project 范围由用户目录决定。</p>
      <label class="console-login__field"
        >访问密钥
        <el-input
          v-model="accessKey"
          type="password"
          show-password
          autocomplete="off"
          aria-label="访问密钥"
          placeholder="xak_…"
        />
      </label>
      <p v-if="mock" class="console-login__hint">
        Mock 演示可使用公开开发密钥，例如 <code>xak_dev_1000</code>（管理员）。
      </p>
      <el-alert
        v-if="consoleIdentity.error.value"
        :title="consoleIdentity.error.value"
        type="error"
        :closable="false"
        role="alert"
      />
      <el-button v-if="consoleIdentity.error.value" @click="retry"
        >重试恢复登录</el-button
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
        :loading="consoleIdentity.loading.value"
        :disabled="!accessKey.trim()"
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
.console-login__hint {
  color: var(--rv-muted);
  font-size: 13px;
}
</style>
