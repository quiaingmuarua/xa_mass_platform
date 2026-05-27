<template>
  <div class="filter-toolbar">
    <slot />
  </div>
</template>

<style scoped>
.filter-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 18px;
}

.filter-toolbar :deep(.el-input) {
  width: 220px;
}

.filter-toolbar :deep(.el-select) {
  width: 170px;
}
</style>
