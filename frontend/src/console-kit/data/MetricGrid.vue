<template>
  <section class="metric-grid-kit" :class="[`metric-grid-kit--${columns}`]">
    <slot />
  </section>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    columns?: 2 | 3 | 4
  }>(),
  {
    columns: 4,
  },
)
</script>

<style scoped>
.metric-grid-kit {
  display: grid;
  gap: 16px;
}

.metric-grid-kit--2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metric-grid-kit--3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.metric-grid-kit--4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

@media (max-width: 980px) {
  .metric-grid-kit {
    grid-template-columns: 1fr;
  }
}
</style>
