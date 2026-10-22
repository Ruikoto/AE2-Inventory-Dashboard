<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import type { Resource } from "../models/types";
import { inventoryQuantity, statisticsLabel, trend } from "../models/format";
import ResourceIcon from "./ResourceIcon.vue";

const props = defineProps<{
  rows: Resource[];
  total: number;
  start: number;
  resetScroll: number;
  favorites: string[];
  comparisons: string[];
  selected: string;
  buckets: boolean;
  canCraft: boolean;
  initialIndex: number;
}>();
const emit = defineEmits<{
  favorite: [id: string];
  compare: [id: string];
  select: [row: Resource];
  craft: [row: Resource];
  window: [start: number, count: number, columns: number, firstVisible: number];
}>();
const STRIDE = 180;
const viewport = ref<HTMLElement>();
const columns = ref(1);
const height = computed(() => Math.ceil(props.total / columns.value) * STRIDE);
let observer: ResizeObserver;
let frame = 0;
function updateWindow() {
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(() => {
    const el = viewport.value;
    if (!el) return;
    const first = Math.floor(el.scrollTop / STRIDE) * columns.value;
    emit(
      "window",
      Math.max(0, first - columns.value * 2),
      Math.min(120, (Math.ceil(el.clientHeight / STRIDE) + 4) * columns.value),
      columns.value,
      first,
    );
  });
}
watch(
  () => props.resetScroll,
  () => {
    if (viewport.value) viewport.value.scrollTop = 0;
    updateWindow();
  },
);
watch(() => props.total, updateWindow);
onMounted(() => {
  const el = viewport.value!;
  const resize = (initial = false) => {
    const first = initial
      ? props.initialIndex
      : Math.floor(el.scrollTop / STRIDE) * columns.value;
    columns.value = Math.max(
      1,
      Math.min(6, Math.floor((el.clientWidth + 8) / 168)),
    );
    el.scrollTop = Math.floor(first / columns.value) * STRIDE;
    updateWindow();
  };
  resize(true);
  observer = new ResizeObserver(() => resize());
  observer.observe(el);
});
onUnmounted(() => {
  observer?.disconnect();
  cancelAnimationFrame(frame);
});
</script>

<template>
  <div
    ref="viewport"
    class="inventory-scroll inventory-grid-scroll"
    role="grid"
    aria-label="AE 库存网格"
    :aria-rowcount="Math.ceil(total / columns)"
    :aria-colcount="columns"
    @scroll.passive="updateWindow"
  >
    <div :style="{ height: `${height}px`, position: 'relative' }">
      <div
        class="inventory-cards"
        :style="{
          gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
          transform: `translateY(${Math.floor(start / columns) * STRIDE}px)`,
        }"
      >
        <div
          v-for="row in rows"
          :key="row.id"
          class="inventory-card"
          role="gridcell"
          :data-resource-id="row.id"
          :class="{ selected: selected === row.id }"
          :aria-selected="selected === row.id"
          @click="emit('select', row)"
        >
          <div class="card-top">
            <ResourceIcon :row="row" />
            <div class="card-controls">
              <button
                class="favorite icon-button"
                :class="{ active: favorites.includes(row.id) }"
                :aria-label="`${favorites.includes(row.id) ? '取消收藏' : '收藏'} ${row.name}`"
                @click.stop="emit('favorite', row.id)"
              >
                {{ favorites.includes(row.id) ? "★" : "☆" }}
              </button>
              <input
                type="checkbox"
                :checked="comparisons.includes(row.id)"
                :aria-label="`对比 ${row.name}`"
                @click.stop
                @change="emit('compare', row.id)"
              />
            </div>
          </div>
          <button
            class="card-name"
            :title="`${row.name}\n${row.registryId}`"
            @click.stop="emit('select', row)"
          >
            {{ row.name }}
          </button>
          <div class="card-quantity">
            <strong :title="inventoryQuantity(row, buckets)">{{
              inventoryQuantity(row, buckets)
            }}</strong>
            <span
              class="trend-arrow"
              :class="trend(row).className"
              :aria-label="trend(row).label"
              :title="`${trend(row).label} · ${statisticsLabel(row)}`"
              >{{ trend(row).symbol }}</span
            >
          </div>
          <div class="card-bottom">
            <small :title="row.registryId">{{
              row.id.startsWith("group:")
                ? `${row.variantCount} 个变体`
                : row.registryId.split(":")[0]
            }}</small>
            <button
              v-if="row.craftable && canCraft"
              class="craft-button"
              :aria-label="`下单 ${row.name}`"
              @click.stop="emit('craft', row)"
            >
              下单
            </button>
          </div>
        </div>
      </div>
    </div>
    <div v-if="!total" class="empty">
      <strong>没有匹配的资源</strong>
      <p>调整搜索或筛选条件</p>
    </div>
  </div>
</template>
