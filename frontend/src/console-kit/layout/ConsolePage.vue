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
  gap: 22px;
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
  position: relative;
  display: flex;
  justify-content: space-between;
  gap: 24px;
  overflow: hidden;
  padding: 28px 30px;
  border: 1px solid rgba(18, 32, 51, 0.08);
  border-radius: 28px;
  background:
    radial-gradient(
      circle at 88% 12%,
      rgba(47, 124, 255, 0.2),
      transparent 30%
    ),
    linear-gradient(135deg, rgba(255, 255, 255, 0.98), rgba(244, 248, 252, 0.94));
  box-shadow: 0 22px 60px rgba(15, 23, 42, 0.1);
}

.console-page--security .console-page-hero {
  background:
    radial-gradient(
      circle at 88% 18%,
      rgba(17, 102, 58, 0.2),
      transparent 28%
    ),
    radial-gradient(
      circle at 12% 0%,
      rgba(47, 124, 255, 0.16),
      transparent 34%
    ),
    linear-gradient(135deg, #ffffff, #f5fbf8);
}

.console-page--operator .console-page-hero {
  background:
    radial-gradient(
      circle at 85% 12%,
      rgba(121, 167, 255, 0.22),
      transparent 30%
    ),
    linear-gradient(135deg, #ffffff, #f7f9ff);
}

.console-page-hero-copy {
  min-width: 0;
}

.console-page-eyebrow {
  margin: 0 0 10px;
  color: #2457c5;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.16em;
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
  color: #101828;
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 800;
  letter-spacing: -0.04em;
  line-height: 1.02;
}

.console-page-subtitle {
  max-width: 760px;
  margin: 14px 0 0;
  color: #56647a;
  font-size: 15px;
  line-height: 1.7;
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
    padding: 24px;
  }

  .console-page-actions {
    width: 100%;
  }
}
</style>
