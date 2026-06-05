<template>
  <section class="console-page" :class="[`console-page--${tone}`, widthClass]">
    <header class="console-page-hero">
      <div class="console-page-hero-copy">
        <p v-if="eyebrow" class="console-page-eyebrow">
          {{ eyebrow }}
        </p>
        <div class="console-page-title-row">
          <h2 class="console-page-title">{{ title }}</h2>
          <slot name="badge" />
        </div>
        <p v-if="subtitle" class="console-page-subtitle">
          {{ subtitle }}
        </p>
      </div>
      <div v-if="$slots.actions" class="console-page-actions">
        <slot name="actions" />
      </div>
    </header>

    <slot />
  </section>
</template>

<script setup lang="ts">
import {computed} from 'vue'

const props = withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    eyebrow?: string
    tone?: 'default' | 'security' | 'operator'
    width?: 'normal' | 'wide' | 'narrow'
  }>(),
  {
    subtitle: '',
    eyebrow: '',
    tone: 'default',
    width: 'normal',
  },
)

const widthClass = computed(() => `console-page--${props.width}`)
</script>

<style scoped>
.console-page {
  display: flex;
  flex-direction: column;
  width: 100%;
  gap: 18px;
}

.console-page--normal {
  max-width: 1180px;
}

.console-page--wide {
  max-width: 1440px;
}

.console-page--narrow {
  max-width: 960px;
}

.console-page-hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 18px 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: var(--color-surface-strong);
  box-shadow: var(--shadow-card);
}

.console-page--security .console-page-hero {
  border-left: 3px solid var(--color-danger-text);
}

.console-page--operator .console-page-hero {
  border-left: 3px solid var(--color-primary);
}

.console-page-hero-copy {
  min-width: 0;
}

.console-page-eyebrow {
  margin: 0 0 8px;
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0;
  text-transform: uppercase;
}

.console-page-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.console-page-title {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 24px;
  font-weight: 760;
  letter-spacing: 0;
  line-height: 1.2;
}

.console-page-subtitle {
  max-width: 760px;
  margin: 10px 0 0;
  color: var(--color-text-muted);
  font-size: 15px;
  line-height: 1.55;
}

.console-page-actions {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex-shrink: 0;
}

@media (max-width: 900px) {
  .console-page-hero {
    flex-direction: column;
    padding: 18px;
  }

  .console-page-actions {
    width: 100%;
  }
}
</style>
