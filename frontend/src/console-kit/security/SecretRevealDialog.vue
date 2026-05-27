<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    :width="width"
    @update:model-value="handleVisibilityChange"
  >
    <el-alert
      type="warning"
      :closable="false"
      :title="warningTitle"
    />
    <el-descriptions class="secret-meta" :column="1" border>
      <el-descriptions-item label="Key ID">
        <span class="mono">{{ keyId }}</span>
      </el-descriptions-item>
      <el-descriptions-item v-if="secretPrefix" label="Secret prefix">
        <span class="mono">{{ secretPrefix }}</span>
      </el-descriptions-item>
      <el-descriptions-item v-if="principalId" label="Principal">
        {{ principalId }}
      </el-descriptions-item>
    </el-descriptions>
    <div class="field-hint">
      {{ hint }}
    </div>
    <pre class="secret-block">{{ secret }}</pre>
    <template #footer>
      <el-button type="primary" @click="closeDialog">
        {{ confirmLabel }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: boolean
    secret: string
    keyId: string
    title?: string
    secretPrefix?: string
    principalId?: string
    warningTitle?: string
    hint?: string
    confirmLabel?: string
    width?: string
  }>(),
  {
    title: 'API key secret',
    secretPrefix: '',
    principalId: '',
    warningTitle: 'Copy this secret now. The server will not return it again.',
    hint: 'Save the Key ID for identification. Save the Secret like a password; it is required for SDK/API calls and is never shown again.',
    confirmLabel: 'I copied it',
    width: '640px',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
}>()

function closeDialog(): void {
  emit('update:modelValue', false)
  emit('confirm')
}

function handleVisibilityChange(value: boolean): void {
  if (value) {
    emit('update:modelValue', true)
    return
  }
  closeDialog()
}

</script>

<style scoped>
.secret-meta {
  margin-top: 16px;
}

.field-hint {
  margin-top: 14px;
  color: #667085;
  line-height: 1.5;
}

.secret-block {
  margin: 16px 0 0;
  padding: 14px;
  border-radius: 14px;
  background: #101828;
  color: #ecfdf3;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
