<script setup lang="ts">
import { ApiReference } from "@scalar/api-reference";
import "@scalar/api-reference/style.css";

import { apiReferenceConfiguration } from "@/api-reference/config";
</script>

<template>
  <div class="api-reference-page">
    <aside class="api-reference-notice" role="note">
      <strong>Build-time API snapshot</strong>
      <span>
        This reference is read-only. Start XA Mass Server and open
        <code>/scalar</code> for the live API document and request tools.
      </span>
    </aside>
    <ApiReference :configuration="apiReferenceConfiguration" />
  </div>
</template>

<style scoped>
.api-reference-page {
  min-height: 100vh;
  background: var(--scalar-background-1, #fff);
}

.api-reference-notice {
  position: relative;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 42px;
  padding: 8px 18px;
  border-bottom: 1px solid #e5e7eb;
  color: #475569;
  background: #f8fafc;
  font-size: 13px;
}

.api-reference-notice strong {
  color: #0f172a;
}

.api-reference-notice code {
  color: #0f766e;
}

@media (max-width: 720px) {
  .api-reference-notice {
    align-items: flex-start;
    flex-direction: column;
    gap: 2px;
  }
}
</style>
