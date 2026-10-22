import type { Inventory, Resource } from "./types";
import { defaults, type Preferences } from "./preferences";
import { InventoryIndex } from "./inventory-index";

let index = new InventoryIndex([]),
  filtered: Resource[] = [];
let preferences: Preferences = defaults(),
  selected = "",
  activeIds: string[] = [];
let start = 0,
  columns = 1,
  count = 40,
  context = 0,
  epoch = 0;
let controller: AbortController | undefined;
let inventory: Inventory | undefined;
let statisticsReady = false;
let namespaces: string[] = [];
let viewRevision = 0;
function publish() {
  start =
    Math.floor(
      Math.max(0, Math.min(start, Math.max(0, filtered.length - 1))) / columns,
    ) * columns;
  postMessage({
    type: "view",
    context,
    rows: filtered.slice(start, start + count),
    start,
    revision: viewRevision,
    total: filtered.length,
    selectedRows: activeIds.map((id) => index.byId.get(id)).filter(Boolean),
    selectedExists: index.byId.has(selected),
    firstId: filtered[0]?.id ?? "",
    inventory,
    statisticsReady,
    namespaces,
  });
}
function filter() {
  filtered = index.filter(preferences);
  publish();
}
async function load(message: {
  context: number;
  base: string;
  network: string;
  world: string;
  merged: boolean;
  zeros: boolean;
  from: number;
  to: number;
  preserve: boolean;
}) {
  controller?.abort();
  controller = new AbortController();
  const signal = controller.signal,
    ticket = ++epoch;
  context = message.context;
  statisticsReady = false;
  if (!message.preserve) {
    index = new InventoryIndex([]);
    filtered = [];
    inventory = undefined;
    start = 0;
  }
  const get = async <T>(path: string): Promise<T> => {
    const response = await fetch(new URL(`api/v1/${path}`, message.base), {
      credentials: "same-origin",
      signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]),
    });
    if (!response.ok)
      throw Object.assign(new Error("Request failed"), {
        status: response.status,
      });
    return response.json() as Promise<T>;
  };
  try {
    const all: Resource[] = [];
    let first: Inventory | undefined;
    for (let offset = 0; offset < 200000; offset += 2000) {
      const page = await get<Inventory>(
        `networks/${message.network}/inventory?merged=${message.merged}&zeros=${message.zeros}&offset=${offset}&limit=2000`,
      );
      if (ticket !== epoch || signal.aborted) return;
      if (
        page.worldId !== message.world ||
        (first && first.version !== page.version)
      )
        throw Object.assign(new Error("Snapshot changed"), { status: 409 });
      if (page.total > 200000) throw new Error("Inventory exceeds view limit");
      first ??= page;
      all.push(
        ...page.rows.map((row) => {
          const previous = message.preserve
            ? index.byId.get(row.id)
            : undefined;
          return {
            ...row,
            delta: previous?.delta ?? null,
            rate: previous?.rate ?? null,
            statisticsFrom: previous?.statisticsFrom,
            statisticsTo: previous?.statisticsTo,
            statisticsScope: previous?.statisticsScope,
            statisticsSamples: previous?.statisticsSamples,
          };
        }),
      );
      if (offset + page.rows.length >= page.total) break;
      if (!page.rows.length) throw new Error("Empty inventory page");
    }
    index = new InventoryIndex(all);
    namespaces = [
      ...new Set(all.map((r) => r.registryId.split(":")[0])),
    ].sort();
    inventory = first ? { ...first, rows: [] } : undefined;
    if (!["delta", "rate"].includes(preferences.sort)) filter();
    let version: number | undefined;
    const updates: Resource[] = [];
    for (let offset = 0; offset < 200000; offset += 2000) {
      const query = new URLSearchParams({
        merged: String(message.merged),
        from: String(message.from),
        to: String(message.to),
        offset: String(offset),
        limit: "2000",
      });
      if (version !== undefined) query.set("version", String(version));
      const page = await get<{
        worldId: string;
        version: number;
        total: number;
        rows: Resource[];
      }>(`networks/${message.network}/statistics?${query}`);
      if (ticket !== epoch || signal.aborted) return;
      if (
        page.worldId !== message.world ||
        (version !== undefined && version !== page.version)
      )
        throw Object.assign(new Error("Statistics changed"), { status: 409 });
      version = page.version;
      if (page.total > 200000) throw new Error("Statistics exceeds view limit");
      updates.push(...page.rows);
      if (offset + page.rows.length >= page.total) break;
      if (!page.rows.length) throw new Error("Empty statistics page");
    }
    for (const update of updates) {
      const row = index.byId.get(update.id);
      if (row)
        Object.assign(row, update, {
          delta: update.delta ?? null,
          rate: update.rate ?? null,
        });
    }
    statisticsReady = true;
    filter();
    postMessage({ type: "done", context });
  } catch (error) {
    if (signal.aborted || ticket !== epoch) return;
    postMessage({
      type: "error",
      context,
      status: (error as { status?: number }).status ?? 503,
    });
  }
}
onmessage = (event) => {
  const message = event.data;
  if (message.type === "load") void load(message);
  else if (message.type === "cancel") {
    controller?.abort();
    epoch++;
  } else if (message.type === "configure") {
    const changed =
      JSON.stringify(preferences) !== JSON.stringify(message.preferences);
    preferences = message.preferences;
    activeIds = message.activeIds;
    selected = message.selected;
    viewRevision = message.revision;
    if (message.resetScroll) start = 0;
    if (changed) filter();
    else publish();
  } else if (message.type === "window") {
    columns = Math.max(1, Math.min(6, message.columns ?? 1));
    start = Math.floor(message.start / columns) * columns;
    count = Math.min(
      Math.floor(120 / columns) * columns,
      Math.ceil(message.count / columns) * columns,
    );
    publish();
  }
};
