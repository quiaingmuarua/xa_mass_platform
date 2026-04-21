<template>
  <section class="app-page">
    <header class="page-header">
      <div>
        <h2 class="page-title">Page not found</h2>
        <p class="page-subtitle">
          The route does not exist in this control console.
        </p>
      </div>
    </header>

    <el-card class="page-card">
      <el-button type="primary" @click="$router.push('/')"
        >Back to overview</el-button
      >
    </el-card>
  </section>
</template>
