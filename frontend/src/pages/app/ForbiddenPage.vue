<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Access denied</h2>
        <p class="page-subtitle">
          This route exists, but the current frontend permission snapshot does
          not allow it.
        </p>
      </div>
    </header>

    <el-card class="page-card">
      <p class="page-subtitle">
        Frontend visibility is only UX. Backend authorization remains the real
        enforcement boundary.
      </p>
      <el-button type="primary" @click="$router.push('/')"
        >Back to overview</el-button
      >
    </el-card>
  </section>
</template>
