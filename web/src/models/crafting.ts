export interface CraftingItem {
  id: string;
  name: string;
  unit: string;
  type: string;
  registryId: string;
}
export interface CraftingCpu {
  id: string;
  name: string;
  storage: string;
  coprocessors: number;
  busy: boolean;
  selectable: boolean;
}
export interface CraftingMaterial {
  item: CraftingItem;
  stored: string;
  craft: string;
  missing: string;
}
export interface CraftingPlan {
  id: string;
  networkId: string;
  item: CraftingItem;
  quantity: string;
  state:
    "calculating" | "ready" | "missing" | "failed" | "cancelled" | "expired";
  bytes: string;
  materials: CraftingMaterial[];
  error?: string;
  createdAt: number;
}
export interface CraftingOrder {
  id: string;
  requestId: string;
  networkId: string;
  planId: string;
  item: CraftingItem;
  quantity: string;
  requestedCpuId: string;
  cpuId: string;
  cpuName: string;
  nativeId: string;
  state:
    | "submitting"
    | "running"
    | "recovering"
    | "completed"
    | "cancelled"
    | "failed"
    | "unknown";
  createdAt: number;
  updatedAt: number;
  finishedAt: number;
  error?: string;
  total: string;
  progress: string;
}
export interface OrderPage {
  orders: CraftingOrder[];
  nextCursor?: string;
}
export function newRequestId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  // 普通 HTTP 也提供 getRandomValues；保留 UUID v4 的随机性与位格式。
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) =>
    value.toString(16).padStart(2, "0"),
  ).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
export const orderStates: Record<CraftingOrder["state"], string> = {
  submitting: "提交中",
  running: "合成中",
  recovering: "等待网络恢复",
  completed: "已完成",
  cancelled: "已取消",
  failed: "失败",
  unknown: "结果未知",
};
export function progressPercent(order: CraftingOrder): number | undefined {
  if (!order.total || !order.progress || BigInt(order.total) <= 0n)
    return undefined;
  return Number((BigInt(order.progress) * 10000n) / BigInt(order.total)) / 100;
}
export function craftingError(code: string): string {
  return (
    (
      {
        UNAUTHORIZED: "访问已过期，请重新登录",
        CRAFTING_DISABLED: "服务器已关闭新订单",
        CRAFTING_UNAVAILABLE: "合成服务暂不可用",
        CRAFTING_INITIALIZING: "合成服务正在恢复订单，请稍后重试",
        CRAFTING_STORAGE_UNAVAILABLE: "订单暂时无法保存，请稍后重试",
        CRAFTING_BUSY: "合成服务忙碌，请稍后重试",
        CRAFTING_FAILED: "合成操作失败，请稍后重试",
        NETWORK_UNAVAILABLE: "网络当前不可用，请恢复供电或加载网络",
        NETWORK_CHANGED: "网络结构已改变，请重新预览；跨网络任务请在游戏内处理",
        NETWORK_NOT_FOUND: "网络已不存在",
        PLAN_NOT_FOUND: "预览已失效，请重新计算",
        PLAN_EXPIRED: "预览已失效，请重新计算",
        PLAN_NOT_READY: "合成预览尚未完成",
        PLAN_TOO_LARGE: "合成方案过大，请减少数量后重试",
        CALCULATION_TIMEOUT: "计算超时，请减少数量后重试",
        CALCULATION_FAILED: "无法计算合成方案",
        MISSING_INGREDIENT: "材料已不足，请重新预览",
        INCOMPLETE_PLAN: "合成方案不完整，请重新预览",
        NO_CPU_FOUND: "网络中没有合成 CPU",
        NO_SUITABLE_CPU_FOUND: "没有合适的空闲合成 CPU",
        CPU_BUSY: "所选 CPU 已被占用，请重新选择",
        CPU_OFFLINE: "所选 CPU 已离线",
        CPU_TOO_SMALL: "所选 CPU 的合成存储空间不足",
        NOT_CRAFTABLE: "该资源当前不可合成",
        RESOURCE_NOT_FOUND: "资源已不可用，请刷新库存",
        INVALID_PARAMETER: "数量或参数不正确",
        INVALID_REQUEST: "请求参数不正确",
        INVALID_RESOURCE: "请选择可合成的精确资源变体",
        INVALID_QUANTITY:
          "数量必须是 1 至 9,223,372,036,854,775,807 之间的整数",
        INVALID_CPU: "CPU 参数不正确，请重新选择",
        REQUEST_TOO_LARGE: "请求内容超出限制",
        UNSUPPORTED_MEDIA_TYPE: "请求格式不受支持，请刷新页面后重试",
        SNAPSHOT_CHANGED: "资源目录已更新，请重新选择资源",
        QUERY_CANCELLED: "查询超时，请稍后重试",
        TEMPORARILY_UNAVAILABLE: "服务暂不可用，请稍后重试",
        REQUEST_CONFLICT: "该请求已用于另一订单，请重新预览",
        REQUEST_PENDING: "订单提交仍在处理中，请稍后再次查询；不要重复下单",
        ORDER_NOT_FOUND: "订单不存在或已过保留期",
        ORDER_UNAVAILABLE: "原合成任务当前不可用，未取消其他任务",
        NATIVE_JOB_NOT_FOUND:
          "原合成任务已无法找到，结果未知；请检查游戏内 CPU",
        SUBMISSION_OUTCOME_UNKNOWN:
          "无法确认提交结果，请检查游戏内 CPU，不要重复下单",
        TRACKING_UNAVAILABLE:
          "订单已提交但无法跟踪进度，请检查游戏内 CPU，不要重复下单",
        ORIGIN_REJECTED: "访问地址与服务器配置不匹配",
      } as Record<string, string>
    )[code] ?? `操作失败（${code}）`
  );
}
