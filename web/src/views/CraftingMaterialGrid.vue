<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import type { CraftingItem } from "../models/crafting";
import { quantity } from "../models/format";
import ResourceIcon from "./ResourceIcon.vue";
interface Material {
  item: CraftingItem;
  stored: string;
  craft?: string;
  missing?: string;
  active?: string;
  pending?: string;
}
const props = withDefaults(
  defineProps<{
    entries: Material[];
    mode: "preview" | "status";
    total?: number;
    offset?: number;
    remote?: boolean;
    resetKey?: string;
    loading?: boolean;
  }>(),
  { offset: 0, remote: false, resetKey: "", loading: false },
);
const emit = defineEmits<{ window: [offset: number, count: number] }>();
const viewport = ref<HTMLElement>(),
  columns = ref(1),
  start = ref(0),
  count = ref(20);
const totalRows = computed(() => props.total ?? props.entries.length);
const visible = computed(() =>
  props.remote
    ? props.entries.slice(0, 120)
    : props.entries.slice(start.value, start.value + count.value),
);
const offset = computed(() => (props.remote ? props.offset : start.value));
const STRIDE = 148;
let observer: ResizeObserver,
  frame = 0;
const tooltip = ref<{ text: string; left: number; top: number }>();
function metrics(row: Material) {
  return props.mode === "preview"
    ? [
        { label: "库存使用", value: row.stored },
        { label: "需要合成", value: row.craft ?? "0" },
        { label: "缺少", value: row.missing ?? "0", kind: "negative" },
      ]
    : [
        { label: "已存储", value: row.stored },
        { label: "合成中", value: row.active ?? "0", kind: "positive" },
        { label: "待合成", value: row.pending ?? "0", kind: "scheduled" },
      ];
}
function description(row: Material) {
  const quality = ["", "银星", "金星", "钻石星"][row.item.quality ?? 0];
  return `${row.item.name}${quality ? ` · ${quality}` : ""}\n${row.item.registryId}\n${metrics(
    row,
  )
    .map((m) => `${m.label} ${quantity(m.value, row.item.unit)}`)
    .join("\n")}`;
}
function showTooltip(event: MouseEvent | FocusEvent, row: Material) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  tooltip.value = {
    text: description(row),
    left: Math.max(8, Math.min(rect.left, innerWidth - 288)),
    top:
      rect.bottom + 148 < innerHeight
        ? rect.bottom + 6
        : Math.max(8, rect.top - 142),
  };
}
function updateWindow() {
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(() => {
    const el = viewport.value;
    if (!el) return;
    start.value = Math.max(
      0,
      (Math.floor(el.scrollTop / STRIDE) - 1) * columns.value,
    );
    count.value = Math.min(
      120,
      (Math.ceil(el.clientHeight / STRIDE) + 3) * columns.value,
    );
    emit("window", start.value, count.value);
  });
}
watch(
  () => props.resetKey,
  () => {
    if (viewport.value) viewport.value.scrollTop = 0;
    tooltip.value = undefined;
    updateWindow();
  },
);
watch(totalRows, updateWindow);
onMounted(() => {
  const el = viewport.value!;
  const resize = () => {
    const first = Math.floor(el.scrollTop / STRIDE) * columns.value;
    columns.value = Math.max(
      1,
      Math.min(6, Math.floor((el.clientWidth + 8) / 148)),
    );
    el.scrollTop = Math.floor(first / columns.value) * STRIDE;
    updateWindow();
  };
  resize();
  observer = new ResizeObserver(resize);
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
    class="material-grid-scroll"
    :style="{
      height: `${Math.min(306, Math.max(80, Math.ceil(totalRows / columns) * STRIDE))}px`,
    }"
    role="grid"
    :aria-label="mode === 'preview' ? '合成材料预览' : 'CPU 材料状态'"
    :aria-rowcount="Math.ceil(totalRows / columns)"
    :aria-colcount="columns"
    :aria-busy="loading"
    @scroll.passive="
      tooltip = undefined;
      updateWindow();
    "
  >
    <div
      :style="{
        height: `${Math.ceil(totalRows / columns) * STRIDE}px`,
        position: 'relative',
      }"
    >
      <div
        class="material-grid"
        :style="{
          gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
          transform: `translateY(${Math.floor(offset / columns) * STRIDE}px)`,
        }"
      >
        <div
          v-for="row in visible"
          :key="row.item.id"
          class="material-cell"
          :class="{
            missing: BigInt(row.missing ?? '0') > 0n,
            active: BigInt(row.active ?? '0') > 0n,
          }"
          role="gridcell"
          tabindex="0"
          :aria-label="description(row)"
          :data-material-id="row.item.id"
          @mouseenter="showTooltip($event, row)"
          @mouseleave="tooltip = undefined"
          @focus="showTooltip($event, row)"
          @blur="tooltip = undefined"
        >
          <div class="material-heading">
            <ResourceIcon :row="row.item" :size="32" /><strong>{{
              row.item.name
            }}</strong>
          </div>
          <dl>
            <template v-for="metric in metrics(row)" :key="metric.label"
              ><div v-if="BigInt(metric.value) > 0n" :class="metric.kind">
                <dt>{{ metric.label }}</dt>
                <dd>{{ quantity(metric.value, row.item.unit) }}</dd>
              </div></template
            >
          </dl>
        </div>
      </div>
    </div>
    <p v-if="!totalRows" class="plan-empty">
      {{ loading ? "正在读取材料…" : "没有符合条件的材料" }}
    </p>
  </div>
  <div
    v-if="tooltip"
    class="material-tooltip"
    role="tooltip"
    :style="{ left: `${tooltip.left}px`, top: `${tooltip.top}px` }"
  >
    {{ tooltip.text }}
  </div>
</template>
