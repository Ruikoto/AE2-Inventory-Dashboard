import type { BrowserContext } from "@playwright/test";

export const world = "a96a8fc2-3411-43c6-ad89-3b51ff0e298f";
export const networks = [
  "8b7cc408-99b6-450f-ab76-d895c078f73c",
  "e57c14cd-6d32-4577-ad6b-96b667011023",
  "ba405276-23ab-40c5-91da-63cd3389a410",
  "b0a406ed-e7c1-4ed1-befc-dff5f5418198",
];
export async function dashboardFixture(
  context: BrowserContext,
  options: {
    count?: number;
    points?: number;
    segmentedHistory?: boolean;
    inventoryDelay?: number;
    statisticsDelay?: number;
    singleNetwork?: boolean;
    capabilities?: boolean;
    craftingEnabled?: boolean;
    presentation?: Record<string, unknown>;
  } = {},
) {
  const count = options.count ?? 120;
  const calls = {
    inventory: 0,
    statistics: 0,
    history: 0,
    historyIds: [] as string[],
    ranges: [] as number[],
  };
  await context.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const now = Date.now();
    const net = url.pathname.split("/")[4];
    const prefix = net === networks[1] ? "乙" : "甲";
    const merged = url.searchParams.get("merged") === "true";
    const offset = Number(url.searchParams.get("offset") ?? 0);
    const limit = Number(url.searchParams.get("limit") ?? 2000);
    const total = net === networks[2] ? 0 : count;
    const resource = (i: number) => {
      const fluid = i === 1;
      const registryId = `minecraft:${fluid ? "water" : `resource_${i}`}`;
      const groupId = `group:${fluid ? "fluid" : "item"}:${registryId}`;
      return {
        id: merged ? groupId : `resource-${i}`,
        groupId,
        registryId,
        name: `${prefix}${fluid ? "水" : `物品 ${i}`}`,
        type: fluid ? "fluid" : "item",
        quantity:
          i === 0
            ? "9007199254740993"
            : options.capabilities && i === 1
              ? "2147486591840"
              : options.capabilities && i === 2
                ? "0"
                : options.capabilities && i === 3
                  ? "2147483647"
                  : String((total - i) * 1000),
        craftable: !!options.capabilities && [0, 1, 2].includes(i),
        infinite: !!options.capabilities && [1, 3].includes(i),
        unit: fluid ? "mB" : "个",
        amountPerUnit: fluid ? 1000 : 1,
        delta: "-120",
        rate: "-720",
        variantCount: merged ? 2 : 1,
        ...options.presentation,
      };
    };
    let json: object;
    if (url.pathname.endsWith("/status"))
      json = {
        authenticated: true,
        authMode: "public",
        craftingEnabled: options.craftingEnabled ?? false,
        craftingStatus: "available",
        worldId: world,
        historyStatus: "recording",
        assets: { status: "missing", entries: 0 },
      };
    else if (url.pathname.endsWith("/networks"))
      json = {
        worldId: world,
        networks: networks
          .slice(0, options.singleNetwork ? 1 : 4)
          .map((id, i) => ({
            id,
            label: ["甲网络", "乙网络", "空网络", "未知网络"][i],
            status: i === 3 ? "unloaded" : "online",
            lastSeen: now,
            anchors: ["minecraft:overworld 0,64,0"],
            nonZeroResources: i === 2 ? 0 : i === 3 ? null : count,
            craftableResources:
              i === 3 ? null : options.capabilities && i < 2 ? 3 : 0,
          })),
      };
    else if (url.pathname.endsWith("/inventory")) {
      calls.inventory++;
      if (options.inventoryDelay)
        await new Promise((resolve) =>
          setTimeout(resolve, options.inventoryDelay),
        );
      const group = url.searchParams.get("group");
      const rows = group
        ? [
            resource(
              group.includes("water") ? 1 : Number(group.split("_").at(-1)),
            ),
          ]
        : Array.from(
            { length: Math.min(limit, Math.max(0, total - offset)) },
            (_, i) => resource(offset + i),
          );
      json = {
        worldId: world,
        networkId: net,
        available: true,
        timestamp: now,
        version: 1,
        status: "online",
        rows,
        total: group ? rows.length : total,
        offset,
      };
    } else if (url.pathname.endsWith("/statistics")) {
      calls.statistics++;
      if (options.statisticsDelay)
        await new Promise((resolve) =>
          setTimeout(resolve, options.statisticsDelay),
        );
      const from = Number(url.searchParams.get("from")),
        to = Number(url.searchParams.get("to"));
      calls.ranges.push(to - from);
      json = {
        worldId: world,
        version: 1,
        total,
        rows: Array.from(
          { length: Math.min(limit, Math.max(0, total - offset)) },
          (_, n) => ({
            id: resource(offset + n).id,
            delta: "18000",
            rate: "300",
            statisticsScope: "latest-continuous",
            statisticsFrom: from + 60000,
            statisticsTo: to,
            statisticsSamples: 300,
          }),
        ),
      };
    } else if (url.pathname.endsWith("/history")) {
      calls.history++;
      const from = Number(url.searchParams.get("from")),
        to = Number(url.searchParams.get("to"));
      const ids = (url.searchParams.get("ids") ?? "")
        .split(",")
        .filter(Boolean);
      calls.historyIds = ids;
      const points = options.points ?? 60;
      const step = Math.floor((to - from) / points);
      json = {
        worldId: world,
        history: {
          from,
          to,
          step,
          availableFrom: from + 60000,
          truncated: true,
          series: ids.map((id) => ({
            id,
            delta: "18000",
            rate: "300",
            complete: false,
            statisticsScope: "latest-continuous",
            statisticsFrom: options.segmentedHistory
              ? from + Math.floor(points / 2) * step
              : from + 60000,
            statisticsTo: to,
            statisticsSamples: options.segmentedHistory
              ? points - Math.floor(points / 2)
              : points,
            points: Array.from({ length: points }, (_, i) => ({
              timestamp: from + i * step,
              firstAt: from + i * step,
              lastAt: from + i * step,
              firstQuantity: String(100000 + i * 300),
              quantity: String(100000 + i * 300),
              min: String(99900 + i * 300),
              max: String(100300 + i * 300),
              samples: 6,
              segment:
                options.segmentedHistory && i >= Math.floor(points / 2)
                  ? "two"
                  : "one",
              rate: "-720",
              smoothedRate:
                options.segmentedHistory && i === Math.floor(points / 2)
                  ? null
                  : String(300 + Math.round(Math.sin(i / 10) * 20)),
              rateWindowMs:
                Number(url.searchParams.get("rateWindowMs")) || 300000,
            })),
          })),
        },
      };
    } else if (url.pathname.endsWith("/events")) json = { events: [] };
    else json = {};
    await route.fulfill({ json });
  });
  return calls;
}
