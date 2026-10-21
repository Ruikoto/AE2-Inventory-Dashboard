<script setup lang="ts">
import type { Resource } from "../models/types";
defineProps<{ row: Resource }>();
</script>
<template>
  <span
    v-if="row.icon"
    class="resource-icon"
    role="img"
    :aria-label="row.name"
    :style="{
      backgroundImage: `url(${row.icon.url})`,
      backgroundSize: `${(row.icon.width * 28) / 64}px ${(row.icon.height * 28) / 64}px`,
      backgroundPosition: `${(-row.icon.x * 28) / 64}px ${(-row.icon.y * 28) / 64}px`,
    }"
  />
  <span v-else class="resource-icon placeholder" aria-hidden="true">{{
    row.type === "fluid" ? "◒" : row.type === "item" ? "◇" : "◈"
  }}</span>
</template>
