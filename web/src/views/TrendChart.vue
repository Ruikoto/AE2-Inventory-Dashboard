<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { LineChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { History, Resource, Point } from "../models/types";
import { quantity, rate } from "../models/format";
echarts.use([
  LineChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  CanvasRenderer,
]);
const props = defineProps<{
  history: History;
  rows: Resource[];
  unit: string;
  mode: "quantity" | "rate";
  envelope: boolean;
}>();
const host = ref<HTMLElement>();
let chart: echarts.ECharts | undefined;
let observer: ResizeObserver;
const palette = [
  "#D8AA62",
  "#77B6CF",
  "#A6BC8A",
  "#C79AB5",
  "#B9B7DB",
  "#DB9885",
];
function render() {
  if (!chart) return;
  const series = props.history.series.filter((s) =>
    props.rows.some((r) => r.id === s.id),
  );
  const quantities = series.flatMap((s) =>
    s.points.flatMap((p) => [BigInt(p.min), BigInt(p.max)]),
  );
  const base = quantities.length
    ? quantities.reduce((a, b) => (a < b ? a : b))
    : 0n;
  const max = quantities.length
    ? quantities.reduce((a, b) => (a > b ? a : b))
    : 0n;
  const divisor =
    10n ** BigInt(Math.max(0, (max - base).toString().length - 12));
  const coordinate = (value: string) =>
    Number((BigInt(value) - base) / divisor) +
    Number((BigInt(value) - base) % divisor) / Number(divisor);
  const plotted: object[] = [];
  series.forEach((s, index) => {
    const row = props.rows.find((r) => r.id === s.id)!;
    const data: ({ value: [number, number | null]; raw?: Point } | null)[] = [];
    let segment = "";
    s.points.forEach((p) => {
      if (segment && segment !== p.segment)
        data.push({ value: [p.firstAt, null] });
      data.push({
        value: [
          p.lastAt,
          props.mode === "rate"
            ? p.rate == null
              ? null
              : Number(p.rate)
            : coordinate(p.quantity),
        ],
        raw: p,
      });
      segment = p.segment;
    });
    const name = `${row.name}${row.variantCount > 1 ? "" : ` · ${row.id.slice(0, 6)}`}`;
    plotted.push({
      name,
      type: "line",
      data,
      showSymbol: false,
      connectNulls: false,
      animation: false,
      lineStyle: { width: 2 },
      itemStyle: { color: palette[index % palette.length] },
    });
    if (props.envelope && props.mode === "quantity") {
      for (const field of ["min", "max"] as const) {
        const values: object[] = [];
        let last = "";
        for (const p of s.points) {
          if (last && last !== p.segment)
            values.push({ value: [p.firstAt, null] });
          values.push({ value: [p.lastAt, coordinate(p[field])], raw: p });
          last = p.segment;
        }
        plotted.push({
          name: `${name} ${field === "min" ? "最小" : "最大"}`,
          type: "line",
          data: values,
          showSymbol: false,
          connectNulls: false,
          lineStyle: {
            type: "dashed",
            width: 1,
            opacity: 0.5,
            color: palette[index % palette.length],
          },
          animation: false,
          tooltip: { show: false },
        });
      }
    }
  });
  chart.setOption(
    {
      backgroundColor: "transparent",
      animation: false,
      textStyle: { fontFamily: "system-ui", color: "#A3B2C0" },
      grid: { left: 78, right: 20, top: 44, bottom: 72 },
      legend: {
        top: 0,
        type: "scroll",
        textStyle: { color: "#E6EDF3", fontSize: 11 },
        data: series.map((s) => {
          const r = props.rows.find((r) => r.id === s.id)!;
          return `${r.name}${r.variantCount > 1 ? "" : ` · ${r.id.slice(0, 6)}`}`;
        }),
      },
      xAxis: {
        type: "time",
        min: props.history.from,
        max: props.history.to,
        axisLabel: { color: "#A3B2C0", hideOverlap: true },
        splitLine: { show: false },
        axisLine: { lineStyle: { color: "#354555" } },
      },
      yAxis: {
        type: "value",
        scale: true,
        name: props.mode === "rate" ? `${props.unit}/min` : props.unit,
        nameTextStyle: { color: "#A3B2C0" },
        splitLine: { lineStyle: { color: "#2A3947" } },
        axisLabel: {
          color: "#A3B2C0",
          formatter: (value: number) =>
            props.mode === "rate"
              ? value.toLocaleString("zh-CN")
              : (BigInt(Math.round(value)) * divisor + base).toLocaleString(
                  "zh-CN",
                  { notation: "compact" },
                ),
        },
      },
      tooltip: {
        trigger: "axis",
        renderMode: "richText",
        backgroundColor: "#263544",
        borderColor: "#465969",
        textStyle: { color: "#E6EDF3" },
        formatter: (input: unknown) => {
          const items = input as {
            seriesName: string;
            data: { raw?: Point };
          }[];
          return items
            .filter(
              (i) =>
                i.data?.raw &&
                !i.seriesName.endsWith("最小") &&
                !i.seriesName.endsWith("最大"),
            )
            .map((i) => {
              const p = i.data.raw!;
              return `${i.seriesName}\n${new Date(p.lastAt).toLocaleString()}\n${props.mode === "quantity" ? quantity(p.quantity, props.unit) : rate(p.rate)}\n区间 ${quantity(p.min)} ～ ${quantity(p.max)}`;
            })
            .join("\n\n");
        },
      },
      dataZoom: [
        { type: "inside", filterMode: "none" },
        {
          type: "slider",
          bottom: 8,
          height: 18,
          borderColor: "#354555",
          backgroundColor: "#18212B",
          fillerColor: "rgba(119,182,207,.12)",
          textStyle: { color: "#A3B2C0" },
          handleStyle: { color: "#77B6CF" },
        },
      ],
      series: plotted,
    },
    true,
  );
}
watch(() => [props.history, props.rows, props.mode, props.envelope], render, {
  deep: true,
});
onMounted(() => {
  observer = new ResizeObserver(() => {
    if (!host.value?.clientWidth || !host.value.clientHeight) return;
    if (!chart) {
      chart = echarts.init(host.value, undefined, { renderer: "canvas" });
      render();
    } else chart.resize();
  });
  observer.observe(host.value!);
});
onUnmounted(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <div
    ref="host"
    class="trend-chart"
    role="img"
    :aria-label="`${unit} ${mode === 'quantity' ? '库存' : '净速率'}趋势图`"
  />
</template>
