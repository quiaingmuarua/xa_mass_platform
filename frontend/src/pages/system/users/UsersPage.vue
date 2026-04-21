<template>
  <PagePlaceholder
    title="Users"
    description="Internal operator accounts and permission assignments."
    heading="User administration placeholder"
    body="Backend auth remains the source of truth; this page will consume current-user and RBAC APIs."
    permission="user:view"
  />
</template>

<script setup lang="ts">
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>
