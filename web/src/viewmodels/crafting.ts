import {
  computed,
  onMounted,
  onUnmounted,
  ref,
  shallowRef,
  watch,
  type Ref,
} from "vue";
import { ApiError, request } from "../models/api";
import {
  craftingError,
  newRequestId,
  type CraftingCpu,
  type CraftingOrder,
  type CraftingPlan,
  type OrderPage,
} from "../models/crafting";
import type { Inventory, Resource, Status } from "../models/types";

export function useCrafting(
  status: Ref<Status | undefined>,
  network: Ref<string>,
) {
  const dialogOpen = ref(false),
    ordersOpen = ref(false),
    history = ref(false);
  const variants = shallowRef<Resource[]>([]),
    resourceId = ref(""),
    amount = ref("1"),
    cpuId = ref("");
  const cpus = shallowRef<CraftingCpu[]>([]),
    plan = shallowRef<CraftingPlan>();
  const orders = shallowRef<CraftingOrder[]>([]),
    nextCursor = ref("");
  const busy = ref(false),
    submitting = ref(false),
    orderBusy = ref(false),
    cancelling = ref("");
  const error = ref(""),
    orderError = ref(""),
    notice = ref(""),
    pendingRequest = ref("");
  const enabled = computed(
    () => status.value?.authenticated && status.value.craftingEnabled === true,
  );
  const item = computed(() =>
    variants.value.find((row) => row.id === resourceId.value),
  );
  const validAmount = computed(
    () =>
      /^[1-9]\d{0,18}$/.test(amount.value) &&
      BigInt(amount.value) <= 9223372036854775807n,
  );
  const canSubmit = computed(
    () =>
      enabled.value &&
      plan.value?.state === "ready" &&
      !busy.value &&
      !submitting.value &&
      !pendingRequest.value &&
      (!cpuId.value ||
        cpus.value.some(
          (cpu) =>
            cpu.id === cpuId.value &&
            cpu.selectable &&
            !cpu.busy &&
            BigInt(cpu.storage) >= BigInt(plan.value?.bytes || "0"),
        )),
  );
  let epoch = 0,
    orderEpoch = 0;
  let openingResource: Resource | undefined;
  let reads = new AbortController(),
    orderReads = new AbortController();
  let planTimer: ReturnType<typeof setTimeout> | undefined,
    orderTimer: ReturnType<typeof setTimeout> | undefined;
  const base = (id = network.value) => `networks/${id}/crafting`;
  const context = () =>
    `${status.value?.worldId}:${network.value}:${status.value?.authenticated}`;
  const pendingKey = () =>
    `ae2dash:pending:${location.origin}:${new URL("./", document.baseURI).pathname}:${status.value?.worldId}:${network.value}`;
  function persistPending(value: string) {
    pendingRequest.value = value;
    try {
      if (value) sessionStorage.setItem(pendingKey(), value);
      else sessionStorage.removeItem(pendingKey());
    } catch {
      notice.value = "浏览器无法保存提交凭据，请保留此页直到确认订单结果";
    }
  }
  function fail(e: unknown) {
    return e instanceof ApiError
      ? craftingError(e.code)
      : "连接中断，请稍后重试";
  }
  const write = <T>(path: string, method: string, body?: object) =>
    request<T>(path, undefined, {
      method,
      headers: { "Content-Type": "application/json" },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  async function discard(id: string, net: string) {
    try {
      await request(`${base(net)}/plans/${id}`, undefined, {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        // 页面离开后仍尽力送达预览撤销，不延长提交或轮询的生命周期。
        keepalive: true,
      });
    } catch (e) {
      if (!(e instanceof ApiError && [404, 410].includes(e.status)))
        notice.value = "预览取消未确认，将由服务器在超时后清理";
    }
  }
  function stopDialogReads() {
    clearTimeout(planTimer);
    reads.abort();
    reads = new AbortController();
    epoch++;
  }
  function closeDialog() {
    const old = plan.value;
    stopDialogReads();
    dialogOpen.value = false;
    openingResource = undefined;
    busy.value = false;
    if (old && !submitting.value && !pendingRequest.value)
      void discard(old.id, old.networkId);
    plan.value = undefined;
  }
  function closeOrders() {
    ordersOpen.value = false;
    clearTimeout(orderTimer);
    orderReads.abort();
    orderEpoch++;
    orderBusy.value = false;
  }
  async function loadCpus(stamp: number, net: string) {
    if (document.hidden) return;
    const signal = reads.signal;
    const result = await request<{ cpus: CraftingCpu[] }>(
      `${base(net)}/cpus`,
      signal,
    );
    if (stamp === epoch && net === network.value && !signal.aborted)
      cpus.value = result.cpus;
  }
  async function open(row: Resource) {
    closeDialog();
    closeOrders();
    openingResource = row;
    dialogOpen.value = true;
    variants.value = [];
    resourceId.value = "";
    amount.value = "1";
    cpuId.value = "";
    cpus.value = [];
    error.value = "";
    busy.value = true;
    const stamp = epoch,
      net = network.value,
      world = status.value?.worldId,
      signal = reads.signal;
    try {
      if (row.id.startsWith("group:")) {
        const found: Resource[] = [];
        let version: number | undefined;
        for (let offset = 0; offset < 200000; offset += 2000) {
          const response = await request<Inventory>(
            `networks/${net}/inventory?merged=false&zeros=true&limit=2000&offset=${offset}&group=${encodeURIComponent(row.id)}`,
            signal,
          );
          if (stamp !== epoch || signal.aborted) return;
          if (
            response.worldId !== world ||
            response.networkId !== net ||
            (version !== undefined && version !== response.version)
          )
            throw new ApiError("NETWORK_CHANGED", 409);
          version = response.version;
          found.push(...response.rows.filter((value) => value.craftable));
          if (offset + response.rows.length >= response.total) break;
          if (!response.rows.length || response.total > 200000)
            throw new ApiError("TEMPORARILY_UNAVAILABLE", 503);
        }
        variants.value = found;
      } else variants.value = [row];
      if (stamp !== epoch || signal.aborted) return;
      resourceId.value = variants.value[0]?.id ?? "";
      if (!variants.value.length)
        error.value = "该资源当前没有可合成的精确变体";
      await loadCpus(stamp, net);
    } catch (e) {
      if (stamp === epoch && !signal.aborted) error.value = fail(e);
    } finally {
      if (stamp === epoch) busy.value = false;
    }
  }
  async function pollPlan() {
    if (
      !dialogOpen.value ||
      document.hidden ||
      !plan.value ||
      plan.value.state !== "calculating"
    )
      return;
    const stamp = epoch,
      net = network.value,
      id = plan.value.id,
      signal = reads.signal;
    try {
      const result = await request<CraftingPlan>(
        `${base(net)}/plans/${id}`,
        signal,
      );
      if (
        stamp !== epoch ||
        signal.aborted ||
        result.networkId !== net ||
        result.id !== id
      )
        return;
      plan.value = result;
      if (result.error) error.value = craftingError(result.error);
      if (result.state === "ready") await loadCpus(stamp, net);
    } catch (e) {
      if (stamp === epoch && !signal.aborted) error.value = fail(e);
    } finally {
      if (
        stamp === epoch &&
        dialogOpen.value &&
        plan.value?.state === "calculating" &&
        !document.hidden
      )
        planTimer = setTimeout(() => void pollPlan(), 1000);
    }
  }
  async function preview() {
    if (
      !enabled.value ||
      !validAmount.value ||
      !resourceId.value ||
      busy.value ||
      submitting.value ||
      pendingRequest.value
    )
      return;
    const old = plan.value;
    stopDialogReads();
    if (old) void discard(old.id, old.networkId);
    const stamp = epoch,
      net = network.value;
    plan.value = undefined;
    busy.value = true;
    error.value = "";
    try {
      const result = await write<CraftingPlan>(`${base(net)}/plans`, "POST", {
        resourceId: resourceId.value,
        quantity: amount.value,
      });
      if (stamp !== epoch || !dialogOpen.value) {
        void discard(result.id, net);
        return;
      }
      if (result.networkId !== net) throw new ApiError("NETWORK_CHANGED", 409);
      plan.value = result;
      if (result.error) error.value = craftingError(result.error);
      if (result.state === "calculating")
        planTimer = setTimeout(() => void pollPlan(), 1000);
      else await loadCpus(stamp, net);
    } catch (e) {
      if (stamp === epoch) error.value = fail(e);
    } finally {
      if (stamp === epoch) busy.value = false;
    }
  }
  function received(order: CraftingOrder) {
    persistPending("");
    plan.value = undefined;
    closeDialog();
    notice.value =
      order.state === "failed"
        ? craftingError(order.error || "TEMPORARILY_UNAVAILABLE")
        : "订单已提交，可在合成订单中查看进度";
    history.value = ["completed", "cancelled", "failed", "unknown"].includes(
      order.state,
    );
    ordersOpen.value = true;
    void loadOrders();
  }
  async function reconcile() {
    if (!pendingRequest.value || submitting.value || document.hidden) return;
    const requestId = pendingRequest.value,
      net = network.value,
      stamp = context(),
      signal = reads.signal;
    submitting.value = true;
    error.value = "";
    try {
      const page = await request<OrderPage>(
        `${base(net)}/orders?requestId=${encodeURIComponent(requestId)}`,
        signal,
      );
      if (stamp !== context() || signal.aborted) return;
      const order = page.orders.find(
        (o) => o.requestId === requestId && o.networkId === net,
      );
      if (order) received(order);
      else if (!page.orders.length) {
        // 服务端只有在请求已结束且没有保存意图时才返回空结果。
        persistPending("");
        const old = plan.value;
        plan.value = undefined;
        if (old) void discard(old.id, old.networkId);
        error.value = "服务器已确认未接受该订单，请重新预览后手动下单";
        notice.value = error.value;
      } else throw new ApiError("NETWORK_CHANGED", 409);
    } catch (e) {
      if (stamp === context() && !signal.aborted) error.value = fail(e);
    } finally {
      if (stamp === context()) submitting.value = false;
    }
  }
  async function submit() {
    if (!canSubmit.value || !plan.value) return;
    const requestId = newRequestId(),
      net = network.value,
      stamp = context();
    const input = { planId: plan.value.id, cpuId: cpuId.value, requestId };
    persistPending(requestId);
    submitting.value = true;
    error.value = "";
    try {
      const order = await write<CraftingOrder>(
        `${base(net)}/orders`,
        "POST",
        input,
      );
      if (stamp !== context()) return;
      if (order.networkId !== net || order.requestId !== requestId)
        throw new ApiError("NETWORK_CHANGED", 409);
      received(order);
    } catch (e) {
      if (stamp !== context()) return;
      // 明确的校验拒绝没有创建订单；连接失败或服务错误必须先查询提交凭据。
      if (e instanceof ApiError && e.status < 500 && e.status !== 408)
        persistPending("");
      error.value = pendingRequest.value
        ? "提交结果尚未确认，请查询订单结果，避免重复下单"
        : fail(e);
    } finally {
      if (stamp === context()) submitting.value = false;
    }
  }
  async function loadOrders(append = false) {
    if (
      !ordersOpen.value ||
      !network.value ||
      document.hidden ||
      !status.value?.authenticated
    )
      return;
    clearTimeout(orderTimer);
    orderReads.abort();
    orderReads = new AbortController();
    const stamp = ++orderEpoch,
      net = network.value,
      signal = orderReads.signal;
    orderBusy.value = true;
    orderError.value = "";
    const query = new URLSearchParams({
      history: String(history.value),
      limit: "50",
    });
    if (append && nextCursor.value) query.set("cursor", nextCursor.value);
    try {
      // 自动刷新保留已经展开的进行中分页，避免每两秒折回第一页。
      const pages =
        append || history.value
          ? 1
          : Math.max(1, Math.ceil(orders.value.length / 50));
      const fetched: CraftingOrder[] = [];
      let cursor = "";
      for (let index = 0; index < pages; index++) {
        const page = await request<OrderPage>(
          `${base(net)}/orders?${query}`,
          signal,
        );
        if (
          stamp !== orderEpoch ||
          signal.aborted ||
          page.orders.some((order) => order.networkId !== net)
        )
          return;
        fetched.push(...page.orders);
        cursor = page.nextCursor ?? "";
        if (!cursor) break;
        query.set("cursor", cursor);
      }
      orders.value = append
        ? [
            ...orders.value,
            ...fetched.filter(
              (o) => !orders.value.some((old) => old.id === o.id),
            ),
          ]
        : fetched;
      nextCursor.value = cursor;
    } catch (e) {
      if (stamp === orderEpoch && !signal.aborted) orderError.value = fail(e);
    } finally {
      if (stamp === orderEpoch) {
        orderBusy.value = false;
        if (ordersOpen.value && !history.value && !document.hidden)
          orderTimer = setTimeout(() => void loadOrders(), 2000);
      }
    }
  }
  function showOrders() {
    closeDialog();
    ordersOpen.value = true;
    orders.value = [];
    nextCursor.value = "";
    void loadOrders();
  }
  async function cancelOrder(order: CraftingOrder) {
    if (cancelling.value) return;
    const stamp = context();
    cancelling.value = order.id;
    orderError.value = "";
    try {
      await write(
        `${base(order.networkId)}/orders/${order.id}/cancel`,
        "POST",
        {},
      );
      if (stamp === context()) await loadOrders();
    } catch (e) {
      if (stamp === context()) orderError.value = fail(e);
    } finally {
      if (stamp === context()) cancelling.value = "";
    }
  }
  watch([resourceId, amount], () => {
    if (plan.value && !submitting.value && !pendingRequest.value) {
      void discard(plan.value.id, plan.value.networkId);
      plan.value = undefined;
      stopDialogReads();
    }
  });
  watch(history, () => {
    orders.value = [];
    nextCursor.value = "";
    void loadOrders();
  });
  watch(context, () => {
    closeDialog();
    closeOrders();
    variants.value = [];
    orders.value = [];
    cpus.value = [];
    cancelling.value = "";
    submitting.value = false;
    error.value = "";
    notice.value = "";
    try {
      pendingRequest.value = sessionStorage.getItem(pendingKey()) ?? "";
    } catch {
      pendingRequest.value = "";
    }
  });
  function visibility() {
    if (document.hidden) {
      clearTimeout(planTimer);
      clearTimeout(orderTimer);
      reads.abort();
      reads = new AbortController();
      orderReads.abort();
    } else {
      if (dialogOpen.value && openingResource && !variants.value.length)
        void open(openingResource);
      else if (dialogOpen.value) {
        void pollPlan();
        const stamp = epoch,
          signal = reads.signal;
        void loadCpus(stamp, network.value).catch((e) => {
          if (stamp === epoch && !signal.aborted) error.value = fail(e);
        });
      }
      void loadOrders();
    }
  }
  const pagehide = () => closeDialog();
  onMounted(() => {
    document.addEventListener("visibilitychange", visibility);
    window.addEventListener("pagehide", pagehide);
  });
  onUnmounted(() => {
    closeDialog();
    closeOrders();
    document.removeEventListener("visibilitychange", visibility);
    window.removeEventListener("pagehide", pagehide);
  });
  return {
    dialogOpen,
    ordersOpen,
    history,
    variants,
    resourceId,
    amount,
    cpuId,
    cpus,
    plan,
    orders,
    nextCursor,
    busy,
    submitting,
    orderBusy,
    cancelling,
    error,
    orderError,
    notice,
    pendingRequest,
    enabled,
    item,
    validAmount,
    canSubmit,
    open,
    closeDialog,
    preview,
    submit,
    reconcile,
    showOrders,
    closeOrders,
    loadOrders,
    cancelOrder,
  };
}
export type CraftingViewModel = ReturnType<typeof useCrafting>;
