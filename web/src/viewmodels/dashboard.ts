import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
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
  const status = ref<Status>();
  const networks = ref<Network[]>([]);
  const networkId = ref("");
  const rows = ref<Resource[]>([]);
  const history = ref<History>();
  const events = ref<NetworkEvent[]>([]);
  const inventory = ref<Inventory>();
  const preferences = reactive(defaults());
  const selected = ref("");
  const settings = ref(false);
  const mode = ref<"quantity" | "rate">("quantity");
  const envelope = ref(false);
  const error = ref("");
  const busy = ref(false);
  const mobileDetail = ref(false);
  const storageMessage = ref("");
  const aliases = ref<Record<string, string>>({});
  const customFrom = ref("");
  const customTo = ref("");
  const password = ref("");
  let controller: AbortController | undefined;
  let chartController: AbortController | undefined;
  let timer: ReturnType<typeof setInterval>;
  let generation = 0;
  let restoring = false;
  let world = "";
  const currentNetwork = computed(() =>
    networks.value.find((n) => n.id === networkId.value),
  );
  const key = () => preferenceKey(world, networkId.value);
  const save = () => {
    if (restoring || !world || !networkId.value) return;
    try {
      localStorage.setItem(key(), JSON.stringify(preferences));
    } catch {
      storageMessage.value = "浏览器无法保存设置，本次会话仍可使用";
    }
  };
  const fail = (e: unknown) => {
    if (e instanceof DOMException && e.name === "AbortError") return;
    if (e instanceof ApiError && e.status === 401) {
      if (status.value) status.value.authenticated = false;
      error.value = "请重新登录";
      return;
    }
    error.value =
      e instanceof ApiError && e.code === "INVALID_PARAMETER"
        ? "当前时间范围或粒度不可用，请选择自动粒度"
        : "读取失败，稍后自动重试";
  };
  const visibleRows = computed(() => {
    const query = preferences.search.trim().toLocaleLowerCase();
    const favoriteOrder = new Map(
      preferences.favorites.map((id, i) => [id, i]),
    );
    return rows.value
      .filter(
        (row) =>
          row.id.startsWith("group:") === preferences.merged &&
          (!query ||
            `${row.name} ${row.registryId} ${row.id}`
              .toLocaleLowerCase()
              .includes(query)) &&
          (preferences.type === "all" ||
            (preferences.type === "other"
              ? !["item", "fluid"].includes(row.type)
              : row.type === preferences.type)) &&
          (!preferences.namespace ||
            row.registryId.split(":")[0] === preferences.namespace) &&
          (!preferences.onlyFavorites || favoriteOrder.has(row.id)) &&
          (preferences.zeros || BigInt(row.quantity) !== 0n),
      )
      .sort((a, b) => {
        const ai = favoriteOrder.get(a.id),
          bi = favoriteOrder.get(b.id);
        if (ai !== undefined || bi !== undefined)
          return ai === undefined ? 1 : bi === undefined ? -1 : ai - bi;
        if (preferences.sort === "name")
          return a.name.localeCompare(b.name, "zh-CN");
        const field = preferences.sort;
        if (field === "rate") {
          const av = a.rate ?? "",
            bv = b.rate ?? "";
          return Number(bv || 0) - Number(av || 0);
        }
        const av = BigInt(a[field] ?? "0"),
          bv = BigInt(b[field] ?? "0");
        return av < bv ? 1 : av > bv ? -1 : a.id.localeCompare(b.id);
      });
  });
  const namespaces = computed(() =>
    [...new Set(rows.value.map((r) => r.registryId.split(":")[0]))].sort(),
  );
  const selectedRows = computed(() =>
    rows.value.filter((r) => activeIds.value.includes(r.id)),
  );
  const activeIds = computed(() => {
    const ids = preferences.comparisons.filter(
      (id) => id.startsWith("group:") === preferences.merged,
    );
    return ids.length ? ids : selected.value ? [selected.value] : [];
  });
  function toggleFavorite(id: string) {
    const i = preferences.favorites.indexOf(id);
    if (i >= 0) preferences.favorites.splice(i, 1);
    else preferences.favorites.push(id);
  }
  function compare(id: string) {
    const i = preferences.comparisons.indexOf(id);
    if (i >= 0) preferences.comparisons.splice(i, 1);
    else if (preferences.comparisons.length < 6)
      preferences.comparisons.push(id);
    else error.value = "最多同时对比 6 项";
  }
  function select(row: Resource) {
    selected.value = row.id;
    mobileDetail.value = true;
  }
  async function refreshHistory() {
    chartController?.abort();
    chartController = new AbortController();
    const signal = chartController.signal;
    if (!networkId.value || !activeIds.value.length) {
      history.value = undefined;
      return;
    }
    const id = networkId.value;
    const to = customTo.value ? new Date(customTo.value).getTime() : Date.now();
    const from = customFrom.value
      ? new Date(customFrom.value).getTime()
      : to - preferences.range * 1000;
    try {
      const query = new URLSearchParams({
        ids: activeIds.value.join(","),
        from: String(from),
        to: String(to),
        step: String(preferences.step * 1000),
      });
      const result = await request<{ worldId: string; history: History }>(
        `networks/${id}/history?${query}`,
        signal,
      );
      if (!signal.aborted && id === networkId.value && result.worldId === world)
        history.value = result.history;
    } catch (e) {
      fail(e);
    }
  }
  async function refresh() {
    if (document.hidden) return;
    controller?.abort();
    controller = new AbortController();
    const signal = controller.signal;
    const stamp = ++generation;
    busy.value = true;
    try {
      const next = await request<Status>("status", signal);
      if (signal.aborted) return;
      status.value = next;
      if (!next.authenticated || !next.worldId) return;
      if (world !== next.worldId) {
        world = next.worldId;
        networkId.value = "";
        rows.value = [];
        history.value = undefined;
        try {
          aliases.value = JSON.parse(
            localStorage.getItem(preferenceKey(world, "aliases")) ?? "{}",
          );
        } catch {
          aliases.value = {};
        }
      }
      const list = await request<{ worldId: string; networks: Network[] }>(
        "networks",
        signal,
      );
      if (signal.aborted || list.worldId !== world) return;
      networks.value = list.networks;
      if (!networkId.value) {
        networkId.value = list.networks[0]?.id ?? "";
        return;
      }
      const id = networkId.value;
      const all: Resource[] = [];
      let first: Inventory | undefined;
      for (let offset = 0; offset < 100000; offset += 2000) {
        const page = await request<Inventory>(
          `networks/${id}/inventory?merged=${preferences.merged}&zeros=${preferences.zeros}&offset=${offset}&limit=2000`,
          signal,
        );
        if (
          signal.aborted ||
          stamp !== generation ||
          id !== networkId.value ||
          page.worldId !== world
        )
          return;
        if (first && page.version !== first.version) {
          error.value = "库存已更新，正在重新读取";
          return;
        }
        first ??= page;
        all.push(...page.rows);
        if (offset + page.rows.length >= page.total) break;
      }
      if (signal.aborted) return;
      rows.value = all;
      inventory.value = first;
      if (!rows.value.some((r) => r.id === selected.value))
        selected.value = rows.value[0]?.id ?? "";
      const eventResult = await request<{ events: NetworkEvent[] }>(
        `networks/${id}/events`,
        signal,
      );
      if (!signal.aborted) events.value = eventResult.events;
      error.value = "";
      await refreshHistory();
    } catch (e) {
      fail(e);
    } finally {
      if (stamp === generation) busy.value = false;
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
    } catch {
      error.value = "密码错误或尝试过于频繁";
      password.value = "";
    }
  }
  async function logout() {
    await request("session", undefined, { method: "DELETE" });
    rows.value = [];
    history.value = undefined;
    if (status.value) status.value.authenticated = false;
  }
  function alias(value: string) {
    aliases.value[networkId.value] = value.slice(0, 100);
    try {
      localStorage.setItem(
        preferenceKey(world, "aliases"),
        JSON.stringify(aliases.value),
      );
    } catch {
      storageMessage.value = "网络别名仅在本次会话保留";
    }
  }
  function stepDisabled(step: number) {
    const to = customTo.value ? new Date(customTo.value).getTime() : Date.now();
    const from = customFrom.value
      ? new Date(customFrom.value).getTime()
      : to - preferences.range * 1000;
    const age = Date.now() - from;
    const minimum = age > 7 * 86400000 ? 900 : age > 86400000 ? 60 : 10;
    return (
      step !== 0 &&
      (step < minimum || Math.ceil((to - from) / (step * 1000)) > 1200)
    );
  }
  watch(networkId, () => {
    generation++;
    controller?.abort();
    chartController?.abort();
    rows.value = [];
    history.value = undefined;
    selected.value = "";
    restoring = true;
    Object.assign(preferences, loadPreferences(key()));
    restoring = false;
    void refresh();
  });
  watch(preferences, save, { deep: true, flush: "sync" });
  watch(
    () => [preferences.merged, preferences.zeros],
    () => {
      selected.value = "";
      void refresh();
    },
  );
  watch(
    () => [
      activeIds.value.join(","),
      preferences.range,
      preferences.step,
      customFrom.value,
      customTo.value,
    ],
    () => void refreshHistory(),
  );
  const visibility = () => {
    if (document.hidden) {
      controller?.abort();
      chartController?.abort();
    } else void refresh();
  };
  onMounted(() => {
    void refresh();
    timer = setInterval(() => void refresh(), 10000);
    document.addEventListener("visibilitychange", visibility);
  });
  onUnmounted(() => {
    clearInterval(timer);
    controller?.abort();
    chartController?.abort();
    document.removeEventListener("visibilitychange", visibility);
  });
  return {
    status,
    networks,
    networkId,
    currentNetwork,
    rows,
    visibleRows,
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
  };
}
