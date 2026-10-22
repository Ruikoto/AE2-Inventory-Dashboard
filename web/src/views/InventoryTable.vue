<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import type { Resource } from "../models/types";
import {
  inventoryQuantity,
  signed,
  rate,
  statisticsLabel,
} from "../models/format";
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
  clearFilters: [];
  window: [start: number, count: number, columns: number, firstVisible: number];
}>();
const ROW_HEIGHT = 64;
const viewport = ref<HTMLElement>();
let observer: ResizeObserver,
  frame = 0;
function updateWindow() {
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(() => {
    const el = viewport.value;
    if (!el) return;
    emit(
      "window",
      Math.max(0, Math.floor(el.scrollTop / ROW_HEIGHT) - 8),
      Math.ceil(el.clientHeight / ROW_HEIGHT) + 16,
      1,
      Math.floor(el.scrollTop / ROW_HEIGHT),
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
  viewport.value!.scrollTop = props.initialIndex * ROW_HEIGHT;
  observer = new ResizeObserver(updateWindow);
  observer.observe(viewport.value!);
  updateWindow();
});
onUnmounted(() => {
  observer?.disconnect();
  cancelAnimationFrame(frame);
});
</script>
<template>
  <div class="inventory-head" role="row">
    <span></span><span>物品 / 流体</span><span>库存</span
    ><span title="所选范围内最近连续段的净变化">区间净变化</span
    ><span title="所选范围内最近连续段的平均净速率">平均净速率</span
    ><span>操作</span>
  </div>
  <div
    ref="viewport"
    class="inventory-scroll"
    role="grid"
    aria-label="AE 库存"
    :aria-rowcount="total"
    @scroll.passive="updateWindow"
  >
    <div :style="{ height: `${total * ROW_HEIGHT}px`, position: 'relative' }">
      <div
        class="virtual-rows"
        :style="{ transform: `translateY(${start * ROW_HEIGHT}px)` }"
      >
        <div
          v-for="row in rows"
          :key="row.id"
          role="row"
          class="inventory-row"
          :data-resource-id="row.id"
          :class="{ selected: selected === row.id }"
          :aria-selected="selected === row.id"
          @click="emit('select', row)"
        >
          <button
            class="favorite icon-button"
            :class="{ active: favorites.includes(row.id) }"
            :aria-label="`${favorites.includes(row.id) ? '取消收藏' : '收藏'} ${row.name}`"
            @click.stop="emit('favorite', row.id)"
          >
            {{ favorites.includes(row.id) ? "★" : "☆" }}
          </button>
          <button class="resource-name" @click.stop="emit('select', row)">
            <ResourceIcon :row="row" /><span
              ><strong>{{ row.name }}</strong
              ><small
                >{{ row.registryId
                }}<template v-if="!row.id.startsWith('group:')">
                  · {{ row.id.slice(0, 6) }}</template
                ><template v-else>
                  ·
                  {{
                    row.variantCount >= 0
                      ? `${row.variantCount} 个变体`
                      : "历史分组"
                  }}</template
                ></small
              ></span
            >
          </button>
          <span class="numeric" :title="inventoryQuantity(row, buckets)">{{
            inventoryQuantity(row, buckets)
          }}</span>
          <span
            class="numeric change"
            :title="statisticsLabel(row)"
            :class="{
              positive: row.delta && BigInt(row.delta) > 0n,
              negative: row.delta && BigInt(row.delta) < 0n,
            }"
            >{{ signed(row.delta, row.unit, buckets)
            }}<small
              v-if="row.statisticsScope === 'latest-continuous'"
              class="partial-mark"
              >*</small
            ></span
          >
          <span class="numeric rate-column" :title="statisticsLabel(row)"
            >{{ rate(row.rate, row.unit, buckets)
            }}<input
              type="checkbox"
              @click.stop
              :checked="comparisons.includes(row.id)"
              :aria-label="`对比 ${row.name}`"
              @change="emit('compare', row.id)"
          /></span>
          <span class="craft-cell"
            ><button
              v-if="row.craftable && canCraft"
              class="craft-button"
              :aria-label="`下单 ${row.name}`"
              @click.stop="emit('craft', row)"
            >
              下单
            </button></span
          >
        </div>
      </div>
    </div>
    <div v-if="!total" class="empty">
      <strong>没有匹配的资源</strong>
      <p>调整搜索或筛选条件</p>
      <button @click="emit('clearFilters')">清除筛选</button>
    </div>
  </div>
</template>
