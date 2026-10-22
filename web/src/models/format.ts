import type { Resource } from "./types";

export function inventoryQuantity(row: Resource, buckets = false): string {
  return row.infinite ? "无限" : quantity(row.quantity, row.unit, buckets);
}

export function trend(row: Resource): {
  symbol: string;
  label: string;
  className: string;
} {
  if (row.delta == null)
    return { symbol: "—", label: "暂无趋势", className: "" };
  const delta = BigInt(row.delta);
  return delta > 0n
    ? { symbol: "↑", label: "区间库存上涨", className: "positive" }
    : delta < 0n
      ? { symbol: "↓", label: "区间库存下降", className: "negative" }
      : { symbol: "→", label: "区间库存持平", className: "" };
}

export function quantity(
  value: string | null | undefined,
  unit = "",
  buckets = false,
): string {
  if (value == null) return "—";
  const integer = BigInt(value);
  if (buckets && unit === "mB") {
    const negative = integer < 0n;
    const abs = negative ? -integer : integer;
    return `${negative ? "−" : ""}${(abs / 1000n).toLocaleString("zh-CN")}.${(abs % 1000n).toString().padStart(3, "0")} B`;
  }
  return `${integer.toLocaleString("zh-CN")}${unit ? ` ${unit}` : ""}`;
}
export function signed(
  value: string | null | undefined,
  unit = "",
  buckets = false,
): string {
  if (value == null) return "—";
  return `${BigInt(value) > 0n ? "+" : ""}${quantity(value, unit, buckets)}`;
}
export function rate(
  value: string | null | undefined,
  unit = "",
  buckets = false,
): string {
  if (value == null) return "—";
  const negative = value.startsWith("-");
  const [integer, fraction = ""] = value.replace(/^[+-]/, "").split(".");
  let text = BigInt(integer).toLocaleString("zh-CN");
  let decimal = fraction;
  if (unit === "mB" && buckets) {
    text = (BigInt(integer) / 1000n).toLocaleString("zh-CN");
    decimal = (BigInt(integer) % 1000n).toString().padStart(3, "0") + fraction;
    unit = "B";
  }
  decimal = decimal.replace(/0+$/, "");
  return `${negative ? "−" : BigInt(integer + fraction) !== 0n ? "+" : ""}${text}${decimal ? `.${decimal}` : ""}${unit ? ` ${unit}` : ""}/min`;
}
export function statisticsLabel(value: {
  statisticsScope?: string;
  statisticsFrom?: number;
  statisticsTo?: number;
}): string {
  if (!value.statisticsScope) return "正在计算所选范围";
  if (value.statisticsScope === "insufficient")
    return "连续观测不足，至少需要两个不同时间的样本";
  return `${value.statisticsScope === "full" ? "统计时段" : "最近连续段"}：${new Date(value.statisticsFrom!).toLocaleString()} — ${new Date(value.statisticsTo!).toLocaleString()}`;
}
export const stateNames: Record<string, string> = {
  online: "在线",
  booting: "重组中",
  unpowered: "断电",
  conflict: "控制器冲突",
  unloaded: "未加载",
  error: "采集失败",
  merged: "已合并",
};
export function historyReason(status: string | undefined): string {
  return (
    (
      {
        "paused:write": "写入失败",
        "paused:space": "磁盘空间不足",
        "paused:batch": "本次采样超出容量限制",
        "paused:queue": "记录队列拥堵",
        "paused:network": "网络目录暂时无法保存",
      } as Record<string, string>
    )[status ?? ""] ?? "等待存储恢复"
  );
}
export const timeRanges = [
  60, 300, 600, 1800, 3600, 14400, 28800, 43200, 86400, 172800, 259200, 604800,
  2592000,
];
export const steps = [
  10, 60, 300, 600, 900, 1800, 3600, 14400, 28800, 43200, 86400, 172800, 259200,
  604800,
];
export function duration(seconds: number): string {
  return seconds >= 86400
    ? `${seconds / 86400}d`
    : seconds >= 3600
      ? `${seconds / 3600}h`
      : seconds >= 60
        ? `${seconds / 60}m`
        : `${seconds}s`;
}

export function decimalValue(value: string): bigint {
  const negative = value.startsWith("-");
  const [integer, fraction = ""] = value.replace(/^[+-]/, "").split(".");
  const scaled =
    BigInt(integer || "0") * 1000000n +
    BigInt(fraction.padEnd(6, "0").slice(0, 6));
  return negative ? -scaled : scaled;
}
export function decimalOrder(a: string, b: string): number {
  const av = decimalValue(a),
    bv = decimalValue(b);
  return av < bv ? -1 : av > bv ? 1 : 0;
}
