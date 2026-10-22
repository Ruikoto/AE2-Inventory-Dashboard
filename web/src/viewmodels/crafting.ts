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
  estimatedRemaining,
  workPercent,
  newRequestId,
  type CraftingCpu,
  type CraftingOrder,
  type CraftingPlan,
  type OrderPage,
  type CpuStatus,
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
  const orderCpus = shallowRef<CraftingCpu[]>([]),
    selectedCpuId = ref(""),
    cpuStatus = shallowRef<CpuStatus>();
  const cpuFresh = ref(false),
    statusStale = ref(true),
    statusLoading = ref(false),
    statusError = ref("");
  const materialSearch = ref(""),
    onlyMissing = ref(false);
  const previewMaterials = computed(() => {
    const query = materialSearch.value.trim().toLocaleLowerCase();
    return (plan.value?.materials ?? [])
      .filter(
        (m) =>
          (!onlyMissing.value || BigInt(m.missing) > 0n) &&
          (!query ||
            `${m.item.name} ${m.item.registryId}`
              .toLocaleLowerCase()
              .includes(query)),
      )
      .slice()
      .sort(
        (a, b) =>
          Number(BigInt(b.missing) > 0n) - Number(BigInt(a.missing) > 0n) ||
          a.item.name.localeCompare(b.item.name, "zh-CN") ||
          a.item.id.localeCompare(b.item.id),
      );
  });
  const selectedCpu = computed(() =>
    orderCpus.value.find((cpu) => cpu.id === selectedCpuId.value),
  );
  const selectedOrderDetail = shallowRef<CraftingOrder>();
  const selectedOrder = computed(
    () =>
      orders.value.find(
        (order) => order.id === selectedCpu.value?.dashboardOrderId,
      ) ??
      (selectedOrderDetail.value?.id === selectedCpu.value?.dashboardOrderId
        ? selectedOrderDetail.value
        : undefined),
  );
  const unboundOrders = computed(() =>
    orders.value.filter(
      (order) =>
        !orderCpus.value.some((cpu) => cpu.dashboardOrderId === order.id),
    ),
  );
  const noProgress = ref(false);
  const cpuProgress = computed(() =>
    cpuStatus.value
      ? workPercent(cpuStatus.value.totalWork, cpuStatus.value.completedWork)
      : undefined,
  );
  const eta = computed(() => {
    const current = cpuStatus.value;
    if (
      !current ||
      statusStale.value ||
      current.state !== "running" ||
      current.cantStoreItems
    )
      return "暂无法估算";
    if (noProgress.value) return "暂无进展，暂无法估算";
    return estimatedRemaining(
      current.totalWork,
      current.completedWork,
      current.elapsedNanos,
    );
  });
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
  const eligibleCpus = computed(() =>
    cpus.value.filter(
      (cpu) =>
        cpu.selectable &&
        !cpu.busy &&
        BigInt(cpu.storage) >= BigInt(plan.value?.bytes || "0"),
    ),
  );
  const cpuProblem = computed(() => {
    if (plan.value?.state !== "ready") return "";
    if (!cpuFresh.value) return "CPU 状态未确认，正在刷新；暂不能下单";
    if (!cpus.value.length) return "网络中没有合成 CPU";
    if (!cpus.value.some((cpu) => cpu.selectable && !cpu.busy))
      return "没有空闲且在线的合成 CPU";
    if (!eligibleCpus.value.length) return "空闲 CPU 的合成存储空间不足";
    if (
      cpuId.value &&
      !eligibleCpus.value.some((cpu) => cpu.id === cpuId.value)
    )
      return "所选 CPU 当前不可用，请重新选择";
    if (!cpuId.value && !eligibleCpus.value.some((cpu) => cpu.autoSelectable))
      return "没有允许自动选择的合适 CPU，请手动选择";
    return "";
  });
  const canSubmit = computed(
    () =>
      enabled.value &&
      plan.value?.state === "ready" &&
      !busy.value &&
      !submitting.value &&
      !pendingRequest.value &&
      !document.hidden &&
      cpuFresh.value &&
      !cpuProblem.value &&
      plan.value.materials.every((material) => BigInt(material.missing) === 0n),
  );
  let epoch = 0,
    orderEpoch = 0;
  let openingResource: Resource | undefined;
  let reads = new AbortController(),
    orderReads = new AbortController(),
    cpuReads = new AbortController();
  let planTimer: ReturnType<typeof setTimeout> | undefined,
    orderTimer: ReturnType<typeof setTimeout> | undefined,
    freshnessTimer: ReturnType<typeof setTimeout> | undefined,
    statusFreshnessTimer: ReturnType<typeof setTimeout> | undefined;
  let cpuEpoch = 0,
    statusOffset = 0,
    statusLimit = 24,
    progressTask = "",
    lastProgress = "",
    lastProgressAt = 0;
  let discarding: Promise<boolean> | undefined;
  let previewBlockedUntil = 0;
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
      return true;
    } catch (e) {
      if (e instanceof ApiError && [404, 410].includes(e.status)) return true;
      if (net === network.value) {
        notice.value =
          "预览取消未确认，服务器将在停止读取后最多 30 秒内释放名额";
        previewBlockedUntil = Date.now() + 30000;
      }
      return false;
    }
  }
  function stopDialogReads() {
    clearTimeout(planTimer);
    reads.abort();
    reads = new AbortController();
    cpuFresh.value = false;
    clearTimeout(freshnessTimer);
    epoch++;
  }
  function closeDialog() {
    const old = plan.value;
    stopDialogReads();
    dialogOpen.value = false;
    openingResource = undefined;
    busy.value = false;
    if (old && !submitting.value && !pendingRequest.value)
      discarding = discard(old.id, old.networkId);
    plan.value = undefined;
  }
  function closeOrders() {
    ordersOpen.value = false;
    clearTimeout(orderTimer);
    orderReads.abort();
    orderEpoch++;
    orderBusy.value = false;
    cpuReads.abort();
    cpuEpoch++;
    statusStale.value = true;
    clearTimeout(statusFreshnessTimer);
  }
  async function loadCpus(stamp: number, net: string) {
    if (document.hidden) return;
    const signal = reads.signal;
    const result = await request<{ cpus: CraftingCpu[] }>(
      `${base(net)}/cpus`,
      signal,
    );
    if (stamp === epoch && net === network.value && !signal.aborted) {
      cpus.value = result.cpus;
      cpuFresh.value = true;
      clearTimeout(freshnessTimer);
      freshnessTimer = setTimeout(() => {
        cpuFresh.value = false;
      }, 6000);
    }
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
    materialSearch.value = "";
    onlyMissing.value = false;
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
      !["calculating", "ready", "missing"].includes(plan.value.state)
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
      error.value = result.error ? craftingError(result.error) : "";
      if (["ready", "missing"].includes(result.state))
        await loadCpus(stamp, net);
    } catch (e) {
      if (stamp === epoch && !signal.aborted) {
        error.value = fail(e);
        cpuFresh.value = false;
        if (
          e instanceof ApiError &&
          ["PLAN_NOT_FOUND", "PLAN_EXPIRED"].includes(e.code) &&
          plan.value
        )
          plan.value = { ...plan.value, state: "expired" };
      }
    } finally {
      if (
        stamp === epoch &&
        dialogOpen.value &&
        plan.value &&
        ["calculating", "ready", "missing"].includes(plan.value.state) &&
        !document.hidden
      )
        planTimer = setTimeout(
          () => void pollPlan(),
          plan.value.state === "calculating" ? 1000 : 2000,
        );
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
    if (Date.now() < previewBlockedUntil) {
      error.value = "旧预览取消尚未确认，请在最多 30 秒的租期结束后重试";
      return;
    }
    const old = plan.value;
    stopDialogReads();
    if (old) discarding = discard(old.id, old.networkId);
    const stamp = epoch,
      net = network.value;
    plan.value = undefined;
    busy.value = true;
    error.value = "";
    try {
      if (discarding) {
        const cancelled = await discarding;
        if (stamp !== epoch || !dialogOpen.value) return;
        discarding = undefined;
        if (!cancelled && Date.now() < previewBlockedUntil) {
          error.value = notice.value;
          return;
        }
      }
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
      if (result.state !== "calculating") await loadCpus(stamp, net);
    } catch (e) {
      if (stamp === epoch) error.value = fail(e);
    } finally {
      if (stamp === epoch) {
        busy.value = false;
        if (
          dialogOpen.value &&
          !document.hidden &&
          plan.value &&
          ["calculating", "ready", "missing"].includes(plan.value.state)
        )
          planTimer = setTimeout(
            () => void pollPlan(),
            plan.value.state === "calculating" ? 1000 : 2000,
          );
      }
    }
  }
  function received(order: CraftingOrder) {
    persistPending("");
    if (order.state === "failed" && plan.value)
      discarding = discard(plan.value.id, plan.value.networkId);
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
    if (order.cpuId) selectedCpuId.value = order.cpuId;
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
      if (!history.value) {
        const result = await request<{ cpus: CraftingCpu[] }>(
          `${base(net)}/cpus`,
          signal,
        );
        if (stamp !== orderEpoch || signal.aborted) return;
        orderCpus.value = result.cpus;
        const previous = cpuStatus.value;
        if (!result.cpus.some((cpu) => cpu.id === selectedCpuId.value))
          selectedCpuId.value =
            result.cpus.find((cpu) => cpu.busy)?.id ?? result.cpus[0]?.id ?? "";
        if (
          previous &&
          (previous.cpuId !== selectedCpuId.value ||
            previous.taskId !== selectedCpu.value?.taskId)
        )
          resetCpuStatus();
        await loadCpuStatus(true);
      }
    } catch (e) {
      if (stamp === orderEpoch && !signal.aborted) {
        orderError.value = fail(e);
        statusStale.value = true;
      }
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
    resetCpuStatus();
    void loadOrders();
  }
  function resetCpuStatus() {
    cpuReads.abort();
    cpuEpoch++;
    cpuStatus.value = undefined;
    selectedOrderDetail.value = undefined;
    statusOffset = 0;
    statusStale.value = true;
    progressTask = "";
    noProgress.value = false;
    clearTimeout(statusFreshnessTimer);
  }
  async function loadCpuStatus(refresh = false) {
    const cpu = selectedCpu.value;
    if (!cpu || !ordersOpen.value || history.value || document.hidden) return;
    cpuReads.abort();
    cpuReads = new AbortController();
    const signal = cpuReads.signal,
      stamp = ++cpuEpoch,
      net = network.value;
    statusLoading.value = true;
    const query = new URLSearchParams({
      cpuId: cpu.id,
      taskId: cpu.taskId,
      offset: String(statusOffset),
      limit: String(statusLimit),
    });
    if (!refresh && cpuStatus.value)
      query.set("version", String(cpuStatus.value.version));
    try {
      let result: CpuStatus;
      try {
        result = await request<CpuStatus>(
          `${base(net)}/cpus/status?${query}`,
          signal,
        );
      } catch (e) {
        if (
          !(e instanceof ApiError && e.code === "STATUS_CHANGED") ||
          signal.aborted
        )
          throw e;
        query.delete("version");
        result = await request<CpuStatus>(
          `${base(net)}/cpus/status?${query}`,
          signal,
        );
      }
      if (
        stamp !== cpuEpoch ||
        signal.aborted ||
        net !== network.value ||
        selectedCpu.value?.id !== result.cpuId ||
        selectedCpu.value.taskId !== result.taskId
      )
        return;
      const task = `${result.cpuId}/${result.taskId}`;
      if (progressTask !== task || lastProgress !== result.completedWork) {
        progressTask = task;
        lastProgress = result.completedWork;
        lastProgressAt = result.sampledAt;
      }
      noProgress.value = result.sampledAt - lastProgressAt >= 30000;
      cpuStatus.value = result;
      statusStale.value =
        result.state === "unavailable" || Date.now() - result.sampledAt > 6000;
      clearTimeout(statusFreshnessTimer);
      statusFreshnessTimer = setTimeout(() => {
        statusStale.value = true;
      }, 6000);
      statusError.value = result.error ? craftingError(result.error) : "";
      if (cpu.canCancel && cpu.dashboardOrderId && !selectedOrder.value) {
        try {
          const order = await request<CraftingOrder>(
            `${base(net)}/orders/${encodeURIComponent(cpu.dashboardOrderId)}`,
            signal,
          );
          if (
            stamp === cpuEpoch &&
            !signal.aborted &&
            order.networkId === net &&
            order.nativeId === result.taskId
          )
            selectedOrderDetail.value = order;
        } catch (e) {
          if (stamp === cpuEpoch && !signal.aborted) orderError.value = fail(e);
        }
      }
    } catch (e) {
      if (stamp !== cpuEpoch || signal.aborted) return;
      statusStale.value = true;
      statusError.value = fail(e);
      if (e instanceof ApiError && e.code === "TASK_CHANGED") {
        resetCpuStatus();
        void loadOrders();
      }
    } finally {
      if (stamp === cpuEpoch) statusLoading.value = false;
    }
  }
  function selectCpu(id: string) {
    if (id === selectedCpuId.value) return;
    selectedCpuId.value = id;
    resetCpuStatus();
    void loadCpuStatus(true);
  }
  function setCpuWindow(offset: number, count: number) {
    if (statusOffset === offset && statusLimit === count) return;
    statusOffset = offset;
    statusLimit = count;
    void loadCpuStatus();
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
      discarding = discard(plan.value.id, plan.value.networkId);
      plan.value = undefined;
      stopDialogReads();
    }
  });
  watch(history, () => {
    orders.value = [];
    nextCursor.value = "";
    resetCpuStatus();
    void loadOrders();
  });
  watch(context, () => {
    closeDialog();
    closeOrders();
    variants.value = [];
    orders.value = [];
    cpus.value = [];
    orderCpus.value = [];
    selectedCpuId.value = "";
    resetCpuStatus();
    discarding = undefined;
    previewBlockedUntil = 0;
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
      cpuReads.abort();
      cpuFresh.value = false;
      statusStale.value = true;
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
    cpuProblem,
    eligibleCpus,
    materialSearch,
    onlyMissing,
    previewMaterials,
    orderCpus,
    selectedCpuId,
    selectedCpu,
    selectedOrder,
    unboundOrders,
    cpuStatus,
    statusStale,
    statusLoading,
    statusError,
    cpuProgress,
    eta,
    selectCpu,
    setCpuWindow,
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
