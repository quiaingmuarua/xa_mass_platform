<template>
  <section class="login-page">
    <div class="login-panel page-card">
      <p class="login-eyebrow">XA Mass Platform</p>
      <h1>Operator login</h1>
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
      />
      <el-form
        class="login-form"
        label-position="top"
        :model="form"
        @submit.prevent="submitLogin"
      >
        <el-form-item label="User ID">
          <el-input
            v-model="form.userId"
            autocomplete="username"
            placeholder="ops-admin"
          />
        </el-form-item>
        <el-form-item label="Password">
          <el-input
            v-model="form.password"
            autocomplete="current-password"
            placeholder="Password"
            show-password
            type="password"
          />
        </el-form-item>
        <el-button
          class="login-submit"
          type="primary"
          native-type="button"
          :loading="submitting"
          @click="submitLogin"
        >
          Sign in
        </el-button>
      </el-form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { login } from '@/auth/use-auth'

const router = useRouter()
const route = useRoute()

const form = reactive({
  userId: '',
  password: '',
})
const submitting = ref(false)
const errorMessage = ref('')

async function submitLogin(): Promise<void> {
  errorMessage.value = ''
  if (!form.userId.trim() || !form.password) {
    errorMessage.value = 'User ID and password are required.'
    return
  }

  submitting.value = true
  try {
    await login({
      userId: form.userId.trim(),
      password: form.password,
    })
    await router.push(redirectTarget())
  } catch (error) {
    errorMessage.value = loginErrorMessage(error)
  } finally {
    submitting.value = false
  }
}

function redirectTarget(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/')) {
    return redirect
  }
  return '/'
}

function loginErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 401) {
    return 'Invalid user ID or password.'
  }
  if (error instanceof Error && error.message.trim().length > 0) {
    return error.message
  }
  return 'Login failed.'
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 64px);
  width: 100%;
}

.login-panel {
  width: min(420px, 100%);
  padding: 28px;
}

.login-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #64748b;
}

.login-panel h1 {
  margin: 0 0 22px;
  font-size: 28px;
  color: #122033;
}

.login-form {
  margin-top: 18px;
}

.login-submit {
  width: 100%;
}
</style>
