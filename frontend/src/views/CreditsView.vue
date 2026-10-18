<script setup lang="ts">
import CreditAccountPanel from "@/credits/CreditAccountPanel.vue";
import { consoleIdentity } from "@/console-identity";
const user = consoleIdentity.requireCurrent();
</script>
<template>
  <section>
    <h1>我的积分</h1>
    <CreditAccountPanel :user-id="user.userId" />
  </section>
</template>
