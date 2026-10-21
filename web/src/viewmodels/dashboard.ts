import {
  computed,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  shallowRef,
  toRef,
  watch,
} from "vue";
import { ApiError, request } from "../models/api";
import {
  defaults,
  loadPreferences,
  preferenceKey,
} from "../models/preferences";
import type {
  History,
  Inventory,
  Network,
  NetworkEvent,
  Resource,
  Status,
} from "../models/types";

export function useDashboard() {
  const status = shallowRef<Status>(),
    allNetworks = shallowRef<Network[]>([]),
    networkId = ref("");
  const rows = shallowRef<Resource[]>([]),
    totalRows = ref(0),
    windowStart = ref(0),
    resetScroll = ref(0);
  const history = shallowRef<History>(),
    events = shallowRef<NetworkEvent[]>([]),
    inventory = shallowRef<Inventory>();
  const variants = shallowRef<Resource[]>([]),
    variantsOpen = ref(false),
    selectedRows = shallowRef<Resource[]>([]);
  const preferences = reactive(defaults()),
    selected = ref(""),
    settings = ref(false);
  const mode = toRef(preferences, "mode"),
    envelope = toRef(preferences, "envelope");
  const error = ref(""),
    busy = ref(false),
    mobileDetail = ref(false),
    storageMessage = ref("");
  const aliases = ref<Record<string, string>>({}),
    customFrom = ref(""),
    customTo = ref(""),
    password = ref("");
  const namespaces = shallowRef<string[]>([]),
    showEmptyNetworks = ref(false),
    statisticsReady = ref(false);
  const networks = computed(() =>
    allNetworks.value.filter(
      (n) => showEmptyNetworks.value || n.nonZeroResources !== 0,
    ),
  );
  const hiddenNetworks = computed(
    () => allNetworks.value.length - networks.value.length,
  );
  const currentNetwork = computed(() =>
    allNetworks.value.find((n) => n.id === networkId.value),
  );
  const activeIds = computed(() => {
    const ids = preferences.comparisons.filter(
      (id) => id.startsWith("group:") === preferences.merged,
    );
    return ids.length ? ids : selected.value ? [selected.value] : [];
  });
  let world = "",
    restoring = false,
    generation = 0,
    viewRevision = 0;
  let controller: AbortController | undefined,
    chartController: AbortController | undefined,
    variantController: AbortController | undefined;
  let worker: Worker | undefined, finishWorker: (() => void) | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined,
    chartTimer: ReturnType<typeof setTimeout> | undefined;
  let saveTimer: ReturnType<typeof setTimeout> | undefined,
    filterTimer: ReturnType<typeof setTimeout> | undefined;
  let range = { from: 0, to: 0 },
    lastLoadKey = "",
    disposed = false;
  const key = () => preferenceKey(world, networkId.value);
  const sessionPreferences = new Map<string, string>();
  function store(key: string, value: unknown) {
    const encoded = JSON.stringify(value);
    sessionPreferences.set(key, encoded);
    try {
      localStorage.setItem(key, encoded);
    } catch {
      storageMessage.value ||= "浏览器无法保存设置，本次会话仍可使用";
    }
  }
  function read<T>(key: string, fallback: T): T {
    try {
      return (
        JSON.parse(
          sessionPreferences.get(key) ?? localStorage.getItem(key) ?? "null",
        ) ?? fallback
      );
    } catch {
      return fallback;
    }
  }
  function save() {
    if (!restoring && world && networkId.value) store(key(), preferences);
  }
  function fail(e: unknown) {
    if (e instanceof DOMException && e.name === "AbortError") return;
    if (e instanceof ApiError && e.status === 401) {
      if (status.value)
        status.value = { ...status.value, authenticated: false };
      clearData();
      error.value = "请重新登录";
      return;
    }
    error.value =
      e instanceof ApiError && e.status === 400
        ? "时间范围或粒度不可用，请调整范围或选择自动粒度"
        : e instanceof ApiError && e.status === 409
          ? "数据已更新，正在重新读取"
          : "读取失败，稍后自动重试";
  }
  function configure(reset = false) {
    if (!worker) return;
    if (reset) resetScroll.value++;
    worker.postMessage({
      type: "configure",
      revision: ++viewRevision,
      preferences: JSON.parse(JSON.stringify(preferences)),
      activeIds: [...activeIds.value],
      selected: selected.value,
      resetScroll: reset,
    });
  }
  function setWindow(start: number, count: number) {
    worker?.postMessage({ type: "window", start, count });
  }
  function cancel() {
    generation++;
    controller?.abort();
    chartController?.abort();
    worker?.postMessage({ type: "cancel" });
    finishWorker?.();
    finishWorker = undefined;
    clearTimeout(timer);
    clearTimeout(chartTimer);
  }
  function clearData() {
    cancel();
    rows.value = [];
    totalRows.value = 0;
    inventory.value = undefined;
    history.value = undefined;
    events.value = [];
    selectedRows.value = [];
    variantController?.abort();
    variants.value = [];
    variantsOpen.value = false;
    statisticsReady.value = false;
    busy.value = false;
  }
  function toggleFavorite(id: string) {
    const i = preferences.favorites.indexOf(id);
    if (i >= 0) preferences.favorites.splice(i, 1);
    else preferences.favorites.push(id);
  }
  function compare(id: string) {
    const i = preferences.comparisons.indexOf(id);
    if (i >= 0) preferences.comparisons.splice(i, 1);
    else if (
      preferences.comparisons.filter(
        (x) => x.startsWith("group:") === preferences.merged,
      ).length < 6
    )
      preferences.comparisons.push(id);
    else error.value = "最多同时对比 6 项";
  }
  function select(row: Resource) {
    selected.value = row.id;
    mobileDetail.value = true;
    variants.value = [];
    variantsOpen.value = false;
  }
  async function expandVariants() {
    variantController?.abort();
    variants.value = [];
    variantsOpen.value = !variantsOpen.value;
    if (!variantsOpen.value || !selectedRows.value[0]?.id.startsWith("group:"))
      return;
    const network = networkId.value,
      id = selectedRows.value[0].id;
    variantController = new AbortController();
    const signal = variantController.signal;
    try {
      const response = await request<Inventory>(
        `networks/${network}/inventory?merged=false&zeros=true&limit=2000&group=${encodeURIComponent(id)}`,
        signal,
      );
      if (
        !signal.aborted &&
        network === networkId.value &&
        id === selectedRows.value[0]?.id &&
        variantsOpen.value
      )
        variants.value = response.rows;
    } catch (e) {
      if (
        !signal.aborted &&
        network === networkId.value &&
        id === selectedRows.value[0]?.id
      )
        fail(e);
    }
  }
  function rangeNow() {
    const to = customTo.value ? new Date(customTo.value).getTime() : Date.now();
    const from = customFrom.value
      ? new Date(customFrom.value).getTime()
      : to - preferences.range * 1000;
    if (
      !Number.isFinite(from) ||
      !Number.isFinite(to) ||
      from >= to ||
      to - from > 30 * 86400000 ||
      to > Date.now() + 60000
    )
      throw new ApiError("INVALID_PARAMETER", 400);
    return { from, to };
  }
  async function refreshHistory() {
    chartController?.abort();
    chartController = new AbortController();
    const signal = chartController.signal,
      id = networkId.value;
    if (
      !id ||
      !activeIds.value.length ||
      document.hidden ||
      !status.value?.authenticated ||
      !range.to
    ) {
      history.value = undefined;
      return;
    }
    try {
      const query = new URLSearchParams({
        ids: activeIds.value.join(","),
        from: String(range.from),
        to: String(range.to),
        step: String(preferences.step * 1000),
        rateWindowMs: String(preferences.rateWindow),
      });
      const result = await request<{ worldId: string; history: History }>(
        `networks/${id}/history?${query}`,
        signal,
      );
      if (!signal.aborted && id === networkId.value && result.worldId === world)
        history.value = result.history;
    } catch (e) {
      if (!signal.aborted) fail(e);
    }
  }
  function scheduleHistory() {
    clearTimeout(chartTimer);
    chartTimer = setTimeout(() => void refreshHistory(), 60);
  }
  function restoreNetwork() {
    if (!networks.value.some((n) => n.id === networkId.value)) {
      const remembered = read<string>(preferenceKey(world, "last-network"), "");
      networkId.value = networks.value.some((n) => n.id === remembered)
        ? remembered
        : (networks.value[0]?.id ?? "");
    }
  }
  async function refresh() {
    if (disposed || document.hidden) return;
    if (!worker) {
      error.value = "库存处理失败，请刷新页面";
      return;
    }
    cancel();
    const stamp = generation;
    controller = new AbortController();
    const signal = controller.signal;
    busy.value = true;
    try {
      const next = await request<Status>("status", signal);
      if (signal.aborted) return;
      status.value = next;
      if (!next.authenticated || !next.worldId) return;
      if (world !== next.worldId) {
        world = next.worldId;
        restoring = true;
        networkId.value = "";
        rows.value = [];
        totalRows.value = 0;
        selected.value = "";
        selectedRows.value = [];
        history.value = undefined;
        const raw = read<Record<string, unknown>>(
          preferenceKey(world, "aliases"),
          {},
        );
        aliases.value =
          raw && typeof raw === "object" && !Array.isArray(raw)
            ? Object.fromEntries(
                Object.entries(raw)
                  .filter(
                    (e): e is [string, string] => typeof e[1] === "string",
                  )
                  .map(([id, name]) => [id, name.slice(0, 100)]),
              )
            : {};
        showEmptyNetworks.value =
          read<boolean>(preferenceKey(world, "show-empty"), false) === true;
        restoring = false;
      }
      const list = await request<{ worldId: string; networks: Network[] }>(
        "networks",
        signal,
      );
      if (signal.aborted || list.worldId !== world) return;
      allNetworks.value = list.networks;
      const old = networkId.value;
      restoreNetwork();
      if (networkId.value !== old) return;
      if (!networkId.value) {
        rows.value = [];
        totalRows.value = 0;
        selectedRows.value = [];
        history.value = undefined;
        return;
      }
      range = rangeNow();
      const id = networkId.value,
        loadKey = `${world}:${id}:${preferences.merged}:${preferences.zeros}:${preferences.range}:${customFrom.value}:${customTo.value}`;
      configure();
      const work = new Promise<void>((resolve) => {
        finishWorker = resolve;
      });
      worker?.postMessage({
        type: "load",
        context: stamp,
        base: new URL("./", document.baseURI).href,
        network: id,
        world,
        merged: preferences.merged,
        zeros: preferences.zeros,
        ...range,
        preserve: loadKey === lastLoadKey,
      });
      lastLoadKey = loadKey;
      const eventResult = await request<{ events: NetworkEvent[] }>(
        `networks/${id}/events`,
        signal,
      );
      if (!signal.aborted) events.value = eventResult.events;
      await work;
      if (!signal.aborted && stamp === generation) scheduleHistory();
    } catch (e) {
      if (!signal.aborted) fail(e);
    } finally {
      if (stamp === generation) {
        busy.value = false;
        timer = setTimeout(() => void refresh(), 10000);
      }
    }
  }
  async function login() {
    try {
      await request("session", undefined, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password: password.value }),
      });
      password.value = "";
      error.value = "";
      await refresh();
    } catch (e) {
      password.value = "";
      error.value =
        e instanceof ApiError && e.status === 429
          ? "尝试过于频繁，请稍后重试"
          : e instanceof ApiError && e.status === 401
            ? "密码错误"
            : "登录失败，请检查访问地址或稍后重试";
    }
  }
  async function logout() {
    try {
      await request("session", undefined, { method: "DELETE" });
      clearData();
      if (status.value)
        status.value = { ...status.value, authenticated: false };
    } catch (e) {
      fail(e);
    }
  }
  function alias(value: string) {
    aliases.value[networkId.value] = value.slice(0, 100);
    store(preferenceKey(world, "aliases"), aliases.value);
  }
  function stepDisabled(step: number) {
    try {
      const { from, to } = rangeNow(),
        age = Date.now() - from;
      const minimum = age > 7 * 86400000 ? 900 : age > 86400000 ? 60 : 10;
      return (
        step !== 0 &&
        (step < minimum || Math.ceil((to - from) / (step * 1000)) > 1200)
      );
    } catch {
      return step !== 0;
    }
  }
  watch(
    networkId,
    (_next, previous) => {
      if (restoring) return;
      if (world && previous) store(preferenceKey(world, previous), preferences);
      clearTimeout(saveTimer);
      clearData();
      selected.value = "";
      restoring = true;
      Object.assign(
        preferences,
        loadPreferences(key(), sessionPreferences.get(key())),
      );
      restoring = false;
      resetScroll.value++;
      if (world && networkId.value)
        store(preferenceKey(world, "last-network"), networkId.value);
      void refresh();
    },
    { flush: "sync" },
  );
  watch(
    showEmptyNetworks,
    () => {
      if (!restoring && world) {
        store(preferenceKey(world, "show-empty"), showEmptyNetworks.value);
        restoreNetwork();
      }
    },
    { flush: "sync" },
  );
  watch(
    preferences,
    () => {
      if (!restoring) {
        clearTimeout(saveTimer);
        saveTimer = setTimeout(save, 180);
      }
    },
    { deep: true },
  );
  watch(
    () => [
      preferences.search,
      preferences.type,
      preferences.namespace,
      preferences.onlyFavorites,
      preferences.sort,
      preferences.favorites.join("\0"),
    ],
    () => {
      clearTimeout(filterTimer);
      filterTimer = setTimeout(() => configure(true), 40);
    },
  );
  watch(
    () => [
      preferences.merged,
      preferences.zeros,
      preferences.range,
      customFrom.value,
      customTo.value,
    ],
    () => {
      if (restoring) return;
      if (stepDisabled(preferences.step)) preferences.step = 0;
      history.value = undefined;
      statisticsReady.value = false;
      void refresh();
    },
    { flush: "sync" },
  );
  watch(
    () => [activeIds.value.join(","), preferences.step, preferences.rateWindow],
    () => {
      variantController?.abort();
      variants.value = [];
      variantsOpen.value = false;
      configure();
      scheduleHistory();
    },
  );
  const visibility = () => {
    if (document.hidden) {
      cancel();
      save();
    } else void refresh();
  };
  onMounted(() => {
    worker = new Worker(
      new URL("../models/inventory-worker.ts", import.meta.url),
      { type: "module" },
    );
    worker.onmessage = (event) => {
      const value = event.data;
      if (value.context !== generation || disposed) return;
      if (value.type === "view") {
        if (value.revision !== viewRevision) return;
        rows.value = value.rows;
        totalRows.value = value.total;
        windowStart.value = value.start;
        inventory.value = value.inventory;
        namespaces.value = value.namespaces;
        statisticsReady.value = value.statisticsReady;
        if (
          JSON.stringify(selectedRows.value) !==
          JSON.stringify(value.selectedRows)
        )
          selectedRows.value = value.selectedRows;
        if (!value.selectedExists && value.firstId) {
          selected.value = value.firstId;
          configure();
        }
      } else if (value.type === "done") {
        error.value = "";
        finishWorker?.();
        finishWorker = undefined;
      } else if (value.type === "error") {
        fail(new ApiError("REQUEST_FAILED", value.status));
        finishWorker?.();
        finishWorker = undefined;
      }
    };
    worker.onerror = () => {
      worker?.terminate();
      worker = undefined;
      clearData();
      error.value = "库存处理失败，请刷新页面";
    };
    void refresh();
    document.addEventListener("visibilitychange", visibility);
    window.addEventListener("pagehide", save);
  });
  onUnmounted(() => {
    save();
    disposed = true;
    cancel();
    clearTimeout(saveTimer);
    clearTimeout(filterTimer);
    variantController?.abort();
    worker?.terminate();
    document.removeEventListener("visibilitychange", visibility);
    window.removeEventListener("pagehide", save);
  });
  return {
    status,
    networks,
    networkId,
    currentNetwork,
    visibleRows: rows,
    totalRows,
    windowStart,
    resetScroll,
    namespaces,
    history,
    events,
    inventory,
    preferences,
    selected,
    selectedRows,
    settings,
    mode,
    envelope,
    error,
    busy,
    mobileDetail,
    storageMessage,
    aliases,
    customFrom,
    customTo,
    password,
    refresh,
    refreshHistory,
    login,
    logout,
    alias,
    compare,
    select,
    toggleFavorite,
    stepDisabled,
    variants,
    variantsOpen,
    expandVariants,
    showEmptyNetworks,
    hiddenNetworks,
    setWindow,
    statisticsReady,
  };
}
