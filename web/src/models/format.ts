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
export function signed(value: string | null | undefined): string {
  if (value == null) return "—";
  return `${BigInt(value) > 0n ? "+" : ""}${quantity(value)}`;
}
export function rate(value: string | null | undefined): string {
  if (value == null) return "—";
  return `${!value.startsWith("-") && value !== "0" ? "+" : ""}${value}/min`;
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

export function decimalOrder(a: string, b: string): number {
  const scale = (value: string) => {
    const negative = value.startsWith("-");
    const [integer, fraction = ""] = value.replace(/^[+-]/, "").split(".");
    const scaled =
      BigInt(integer || "0") * 1000000n +
      BigInt(fraction.padEnd(6, "0").slice(0, 6));
    return negative ? -scaled : scaled;
  };
  const av = scale(a),
    bv = scale(b);
  return av < bv ? -1 : av > bv ? 1 : 0;
}
