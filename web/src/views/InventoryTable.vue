<script setup lang="ts">
import { computed, ref } from "vue";
import type { Resource } from "../models/types";
import { quantity, signed, rate } from "../models/format";
import ResourceIcon from "./ResourceIcon.vue";
const props = defineProps<{
  rows: Resource[];
  favorites: string[];
  comparisons: string[];
  selected: string;
  buckets: boolean;
}>();
const emit = defineEmits<{
  favorite: [id: string];
  compare: [id: string];
  select: [row: Resource];
}>();
const scrollTop = ref(0);
const viewport = ref<HTMLElement>();
const start = computed(() => Math.max(0, Math.floor(scrollTop.value / 64) - 8));
const visible = computed(() =>
  props.rows.slice(
    start.value,
    start.value + Math.ceil((viewport.value?.clientHeight ?? 800) / 64) + 16,
  ),
);
</script>
<template>
  <div class="inventory-head" role="row">
    <span></span><span>物品 / 流体</span><span>库存</span
    ><span title="相邻两次采样的净变化">净变化</span
    ><span title="相邻两次采样的净变化速率">净速率</span>
  </div>
  <div
    ref="viewport"
    class="inventory-scroll"
    role="grid"
    aria-label="AE 库存"
    :aria-rowcount="rows.length"
    @scroll="scrollTop = ($event.target as HTMLElement).scrollTop"
  >
    <div :style="{ height: `${rows.length * 64}px`, position: 'relative' }">
      <div
        class="virtual-rows"
        :style="{ transform: `translateY(${start * 64}px)` }"
      >
        <div
          v-for="row in visible"
          :key="row.id"
          role="row"
          class="inventory-row"
          :class="{ selected: selected === row.id }"
          :aria-selected="selected === row.id"
        >
          <button
            class="favorite icon-button"
            :class="{ active: favorites.includes(row.id) }"
            :aria-label="`${favorites.includes(row.id) ? '取消收藏' : '收藏'} ${row.name}`"
            @click="emit('favorite', row.id)"
          >
            {{ favorites.includes(row.id) ? "★" : "☆" }}
          </button>
          <button class="resource-name" @click="emit('select', row)">
            <ResourceIcon :row="row" /><span
              ><strong>{{ row.name }}</strong
              ><small
                >{{ row.registryId
                }}<template v-if="!row.id.startsWith('group:')">
                  · {{ row.id.slice(0, 6) }}</template
                ><template v-else>
                  · {{ row.variantCount }} 个变体</template
                ></small
              ></span
            >
          </button>
          <span
            class="numeric"
            :title="quantity(row.quantity, row.unit, buckets)"
            >{{ quantity(row.quantity, row.unit, buckets) }}</span
          >
          <span
            class="numeric change"
            :class="{
              positive: row.delta && BigInt(row.delta) > 0n,
              negative: row.delta && BigInt(row.delta) < 0n,
            }"
            >{{ signed(row.delta) }}</span
          >
          <span class="numeric rate-column"
            >{{ rate(row.rate)
            }}<input
              type="checkbox"
              :checked="comparisons.includes(row.id)"
              :aria-label="`对比 ${row.name}`"
              @change="emit('compare', row.id)"
          /></span>
        </div>
      </div>
    </div>
    <div v-if="!rows.length" class="empty">
      <strong>没有匹配的资源</strong>
      <p>调整搜索或筛选条件</p>
    </div>
  </div>
</template>
