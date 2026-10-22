<script setup lang="ts">
import { computed } from "vue";
import type { Icon } from "../models/types";
const props = withDefaults(
  defineProps<{
    row: {
      name: string;
      type: string;
      icon?: Icon;
      overlay?: Icon;
      quality?: number;
    };
    size?: number;
  }>(),
  { size: 28 },
);
const qualityName = computed(
  () => ["", "银星", "金星", "钻石星"][props.row.quality ?? 0] ?? "",
);
const description = computed(
  () =>
    `${props.row.name}${qualityName.value ? ` · ${qualityName.value}` : ""}`,
);
function atlas(icon: Icon) {
  return {
    backgroundImage: `url(${icon.url})`,
    backgroundSize: `${(icon.width * props.size) / 64}px ${(icon.height * props.size) / 64}px`,
    backgroundPosition: `${(-icon.x * props.size) / 64}px ${(-icon.y * props.size) / 64}px`,
  };
}
</script>
<template>
  <span
    class="resource-icon"
    :class="{ placeholder: !row.icon }"
    role="img"
    :aria-label="description"
    :title="description"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      ...(row.icon ? atlas(row.icon) : {}),
    }"
  >
    <template v-if="!row.icon">{{
      row.type === "fluid" ? "◒" : row.type === "item" ? "◇" : "◈"
    }}</template>
    <span
      v-if="row.overlay"
      class="resource-icon-overlay"
      :style="atlas(row.overlay)"
    />
    <small v-else-if="qualityName && !row.icon" class="quality-badge">{{
      qualityName
    }}</small>
  </span>
</template>
