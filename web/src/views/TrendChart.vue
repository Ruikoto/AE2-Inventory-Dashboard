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
import { quantity } from "../models/format";
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
  mode: "quantity" | "rate" | "both";
  envelope: boolean;
  buckets: boolean;
}>();
const host = ref<HTMLElement>();
let chart: echarts.ECharts | undefined,
  observer: ResizeObserver,
  themeObserver: MutationObserver,
  frame = 0;

const rateInteger = (text: string) => {
  const negative = text.startsWith("-");
  const [a, b = ""] = text.replace(/^[+-]/, "").split(".");
  const value = BigInt(a) * 1000000n + BigInt(b.padEnd(6, "0").slice(0, 6));
  return negative ? -value : value;
};
function scale(values: bigint[]) {
  const low = values.reduce((a, b) => (a < b ? a : b), values[0] ?? 0n);
  const high = values.reduce((a, b) => (a > b ? a : b), values[0] ?? 0n);
  const divisor =
    10n ** BigInt(Math.max(0, (high - low).toString().length - 11));
  return {
    low,
    divisor,
    plot: (value: bigint) =>
      Number((value - low) / divisor) +
      Number((value - low) % divisor) / Number(divisor),
    restore: (value: number) => low + BigInt(Math.round(value)) * divisor,
  };
}
function render() {
  if (!chart) return;
  const css = getComputedStyle(document.documentElement);
  const color = (name: string) => css.getPropertyValue(`--${name}`).trim();
  const light = document.documentElement.dataset.base === "light";
  const palette = [
    color("accent"),
    light ? "#287391" : "#77b6cf",
    light ? "#497845" : "#a6bc8a",
    light ? "#9c507e" : "#c79ab5",
    light ? "#6759a2" : "#b9b7db",
    light ? "#a55642" : "#db9885",
  ];
  const selected = new Map(props.rows.map((r) => [r.id, r]));
  const series = props.history.series.filter((s) => selected.has(s.id));
  const quantities = series.flatMap((s) =>
    s.points.flatMap((p) => [BigInt(p.min), BigInt(p.max)]),
  );
  const rates = series.flatMap((s) =>
    s.points.flatMap((p) =>
      p.smoothedRate == null ? [] : [rateInteger(p.smoothedRate)],
    ),
  );
  const qs = scale(quantities),
    rs = scale(rates);
  const unit = props.buckets && props.unit === "mB" ? "B" : props.unit;
  const rateText = (v: bigint) => {
    const denominator =
      props.buckets && props.unit === "mB" ? 1000000000n : 1000000n;
    const negative = v < 0;
    const abs = negative ? -v : v;
    const fraction = (abs % denominator)
      .toString()
      .padStart(denominator.toString().length - 1, "0")
      .replace(/0+$/, "");
    return `${negative ? "−" : ""}${(abs / denominator).toLocaleString("zh-CN")}${fraction ? `.${fraction}` : ""}`;
  };
  const plotted: object[] = [],
    legends: string[] = [];
  series.forEach((s, index) => {
    const row = selected.get(s.id)!;
    const suffix =
      !row.id.startsWith("group:") &&
      props.rows.some((r) => r.id !== row.id && r.name === row.name)
        ? ` · ${row.id.slice(0, 6)}`
        : "";
    const label = row.name + suffix;
    const lineColor = palette[index % palette.length];
    const data = (field: "quantity" | "rate" | "min" | "width") => {
      const result: object[] = [];
      let segment = "";
      for (const p of s.points) {
        if (segment && segment !== p.segment)
          result.push({ value: [p.firstAt, null] });
        const value =
          field === "rate"
            ? p.smoothedRate == null
              ? null
              : rs.plot(rateInteger(p.smoothedRate))
            : field === "width"
              ? qs.plot(BigInt(p.max)) - qs.plot(BigInt(p.min))
              : qs.plot(BigInt(field === "min" ? p.min : p.quantity));
        result.push({
          value: [p.lastAt, value],
          raw: p,
          resourceId: s.id,
          metric: field,
        });
        segment = p.segment;
      }
      return result;
    };
    for (const metric of (props.mode === "both"
      ? ["quantity", "rate"]
      : [props.mode]) as ("quantity" | "rate")[]) {
      const name = `${label} · ${metric === "quantity" ? "数量" : "速率"}`;
      legends.push(name);
      plotted.push({
        id: `${s.id}:${metric}`,
        name,
        type: "line",
        yAxisIndex: metric === "rate" && props.mode === "both" ? 1 : 0,
        data: data(metric),
        showSymbol: s.points.length === 1,
        symbolSize: 5,
        connectNulls: false,
        animation: false,
        lineStyle: {
          width: metric === "quantity" ? 2 : 1.6,
          type: metric === "rate" ? "dashed" : "solid",
        },
        itemStyle: { color: lineColor },
      });
    }
    if (props.envelope && props.mode !== "rate") {
      for (const field of ["min", "width"] as const)
        plotted.push({
          id: `${s.id}:band:${field}`,
          name: `${label} · 数量`,
          type: "line",
          stack: `${s.id}:band`,
          data: data(field),
          showSymbol: false,
          connectNulls: false,
          animation: false,
          silent: true,
          lineStyle: { opacity: 0 },
          itemStyle: { color: lineColor },
          areaStyle: {
            color: lineColor,
            opacity: field === "width" ? 0.16 : 0,
          },
          tooltip: { show: false },
          z: 1,
        });
    }
  });
  const axis = (metric: "quantity" | "rate") => ({
    type: "value",
    scale: true,
    name: metric === "rate" ? `${unit}/min` : unit,
    nameTextStyle: { color: color("muted") },
    splitNumber: 5,
    minInterval: metric === "quantity" ? 1 : undefined,
    splitLine: {
      show: metric === "quantity" || props.mode === "rate",
      lineStyle: { color: color("divider") },
    },
    axisLabel: {
      color: color("muted"),
      hideOverlap: true,
      formatter: (v: number) =>
        metric === "rate"
          ? rateText(rs.restore(v))
          : quantity(
              qs.restore(v).toString(),
              props.unit,
              props.buckets,
            ).replace(/ (个|mB|B)$/, ""),
    },
  });
  chart.setOption(
    {
      backgroundColor: "transparent",
      animation: false,
      textStyle: { fontFamily: "system-ui", color: color("muted") },
      grid: {
        left: 12,
        right: props.mode === "both" ? 12 : 16,
        top: 58,
        bottom: 60,
        containLabel: true,
      },
      legend: {
        top: 0,
        type: "scroll",
        textStyle: { color: color("text"), fontSize: 11 },
        data: legends,
      },
      xAxis: {
        type: "time",
        min: props.history.from,
        max: props.history.to,
        axisLabel: { color: color("muted"), hideOverlap: true },
        splitLine: { show: false },
        axisLine: { lineStyle: { color: color("line") } },
      },
      yAxis:
        props.mode === "both"
          ? [axis("quantity"), axis("rate")]
          : [axis(props.mode)],
      tooltip: {
        trigger: "axis",
        renderMode: "richText",
        confine: true,
        backgroundColor: color("panel"),
        borderColor: color("line"),
        textStyle: { color: color("text"), fontSize: 12 },
        formatter: (input: unknown) => {
          const seen = new Set<string>();
          return (input as { data?: { raw?: Point; resourceId: string } }[])
            .flatMap((item) => {
              const raw = item.data?.raw,
                id = item.data?.resourceId;
              if (!raw || !id || seen.has(id)) return [];
              seen.add(id);
              const smooth =
                raw.smoothedRate == null
                  ? "—"
                  : `${rateText(rateInteger(raw.smoothedRate))} ${unit}/min`;
              return [
                `${selected.get(id)?.name}\n${new Date(raw.lastAt).toLocaleString()}\n库存 ${quantity(raw.quantity, props.unit, props.buckets)}\n平均速率 ${smooth}\n桶内 ${quantity(raw.min, props.unit, props.buckets)} ～ ${quantity(raw.max, props.unit, props.buckets)}`,
              ];
            })
            .join("\n\n");
        },
      },
      dataZoom: [
        { type: "inside", filterMode: "none" },
        {
          type: "slider",
          filterMode: "none",
          bottom: 5,
          height: 18,
          borderColor: color("line"),
          backgroundColor: color("bg"),
          fillerColor: color("selected"),
          textStyle: { color: color("muted") },
          handleStyle: { color: color("focus") },
        },
      ],
      series: plotted,
    },
    { notMerge: false, replaceMerge: ["series", "yAxis"], lazyUpdate: true },
  );
}
function schedule() {
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(render);
}
watch(
  () => [props.history, props.rows, props.mode, props.envelope, props.buckets],
  schedule,
);
onMounted(() => {
  observer = new ResizeObserver(() => {
    if (!host.value?.clientWidth || !host.value.clientHeight) return;
    if (!chart) {
      chart = echarts.init(host.value, undefined, {
        renderer: "canvas",
        locale: "ZH",
      });
      schedule();
    } else chart.resize();
  });
  observer.observe(host.value!);
  themeObserver = new MutationObserver(schedule);
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["style", "data-theme"],
  });
});
onUnmounted(() => {
  observer?.disconnect();
  themeObserver?.disconnect();
  cancelAnimationFrame(frame);
  chart?.dispose();
});
</script>
<template>
  <div
    ref="host"
    class="trend-chart"
    role="img"
    :aria-label="`${unit} ${mode === 'both' ? '数量与平均净速率' : mode === 'quantity' ? '库存' : '平均净速率'}趋势图`"
  />
</template>
