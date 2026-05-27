<template>
  <el-card class="credential-input-card page-card">
    <template #header>
      <strong>{{ title }}</strong>
    </template>
    <p v-if="description" class="credential-description">
      {{ description }}
    </p>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item :label="label">
        <el-input
          :model-value="modelValue"
          type="password"
          show-password
          :placeholder="placeholder"
          autocomplete="off"
          @update:model-value="updateValue"
          @keyup.enter="submit"
        />
        <div v-if="hint" class="field-hint">
          {{ hint }}
        </div>
      </el-form-item>
      <el-button
        type="primary"
        :disabled="disabled || !modelValue.trim()"
        :loading="loading"
        @click="submit"
      >
        {{ actionLabel }}
      </el-button>
      <slot name="actions" />
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    modelValue: string
    title: string
    label: string
    actionLabel: string
    description?: string
    hint?: string
    placeholder?: string
    disabled?: boolean
    loading?: boolean
  }>(),
  {
    description: '',
    hint: '',
    placeholder: '',
    disabled: false,
    loading: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: []
}>()

function updateValue(value: string | number): void {
  emit('update:modelValue', String(value))
}

function submit(): void {
  if (props.disabled || props.loading || !props.modelValue.trim()) {
    return
  }
  emit('submit')
}
</script>

<style scoped>
.credential-input-card {
  height: fit-content;
}

.credential-description {
  margin: 0 0 16px;
  color: #667085;
  font-size: 14px;
  line-height: 1.5;
}

.field-hint {
  margin-top: 8px;
  color: #667085;
  font-size: 13px;
  line-height: 1.5;
}
</style>
