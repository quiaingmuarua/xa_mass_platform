<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Control Overview</h2>
        <p class="page-subtitle">
          Lightweight control-plane landing page for orchestration health,
          operational posture, and the current permission context.
        </p>
      </div>
    </header>

    <section class="metric-grid">
      <div class="metric-tile">
        <div class="metric-label">Visible product domains</div>
        <div class="metric-value">10</div>
      </div>
      <div class="metric-tile">
        <div class="metric-label">Mock user roles</div>
        <div class="metric-value">{{ user?.roles.length ?? 0 }}</div>
      </div>
      <div class="metric-tile">
        <div class="metric-label">Granted permissions</div>
        <div class="metric-value">{{ user?.permissions.length ?? 0 }}</div>
      </div>
      <div class="metric-tile">
        <div class="metric-label">Backend integration mode</div>
        <div class="metric-value">Mock</div>
      </div>
    </section>

    <el-card class="page-card">
      <template #header>
        <strong>Design notes</strong>
      </template>
      <p class="page-subtitle">
        This shell is intentionally permission-aware, route-driven, and
        low-magic. It favors ordinary pages and explicit API modules over heavy
        admin abstractions.
      </p>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { useAuth } from '@/auth/use-auth'

const { user } = useAuth()
</script>
