import { test, expect, type BrowserContext, type Page } from "@playwright/test";
import { dashboardFixture, networks } from "./fixture";
import type { CraftingOrder, CraftingPlan } from "../../src/models/crafting";

export async function craftingFixture(
  context: BrowserContext,
  options: {
    enabled?: boolean;
    missing?: boolean;
    loseSubmit?: boolean;
    delayPlan?: boolean;
    calculating?: boolean;
    cancelFailure?: boolean;
    failIntent?: boolean;
    presentation?: Record<string, unknown>;
  } = {},
) {
  await dashboardFixture(context, {
    count: 8,
    presentation: options.presentation,
    capabilities: true,
    craftingEnabled: options.enabled ?? true,
  });
  const state = {
    orders: [] as CraftingOrder[],
    plans: new Map<string, CraftingPlan>(),
    submits: 0,
    deletes: 0,
    cancelled: 0,
    polls: 0,
    requestPending: false,
    cpuMode: "available" as
      "available" | "none" | "busy" | "small" | "manual" | "error",
    statusFailure: false,
    external: false,
    crossNetwork: false,
    materialCount: 1,
    completedWork: "25",
    sampledAt: 0,
    taskId: "native-1",
    statusCalls: 0,
    statusChangedOnce: false,
    materialName: "加工材料",
    deleteFailure: false,
  };
  await context.route("**/api/v1/networks/*/crafting/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url());
    const parts = url.pathname.split("/"),
      networkId = parts[4];
    const action = parts.slice(6).join("/");
    const running = state.orders.find(
      (order) => order.networkId === networkId && order.state === "running",
    );
    if (action === "cpus") {
      if (state.cpuMode === "error")
        return route.fulfill({
          status: 503,
          json: { error: "CRAFTING_QUEUE_BUSY" },
        });
      const common = {
        taskId: "",
        outputQuantity: "0",
        totalWork: "0",
        completedWork: "0",
        elapsedNanos: "0",
        dashboardOrderId: "",
        canCancel: false,
        autoSelectable: state.cpuMode !== "manual",
      };
      return route.fulfill({
        json: {
          cpus:
            state.cpuMode === "none"
              ? []
              : [
                  {
                    ...common,
                    id: "cpu-1",
                    name: "主合成 CPU",
                    storage: state.cpuMode === "small" ? "1" : "65536",
                    coprocessors: 4,
                    busy: !!running || state.cpuMode === "busy",
                    selectable: !running && state.cpuMode !== "busy",
                    ...(running
                      ? {
                          taskId: state.taskId,
                          output: running.item,
                          outputQuantity: running.quantity,
                          totalWork: "100",
                          completedWork: state.completedWork,
                          elapsedNanos: "10000000000",
                          dashboardOrderId: running.id,
                          canCancel: true,
                        }
                      : {}),
                  },
                  {
                    ...common,
                    id: "cpu-small",
                    name: "小型 CPU",
                    storage: "1",
                    coprocessors: 0,
                    busy: state.cpuMode === "busy",
                    selectable: state.cpuMode !== "busy",
                  },
                  ...(state.external
                    ? [
                        {
                          ...common,
                          id: "cpu-external",
                          name: "外部合成 CPU",
                          storage: "65536",
                          coprocessors: 2,
                          busy: true,
                          selectable: false,
                          taskId: "external-task",
                          dashboardOrderId: state.crossNetwork
                            ? "foreign-order"
                            : "",
                          output: {
                            id: "external",
                            name: "游戏中的任务",
                            type: "item",
                            unit: "个",
                            registryId: "minecraft:iron_ingot",
                          },
                          outputQuantity: "20",
                          totalWork: "100",
                          completedWork: state.completedWork,
                          elapsedNanos: "10000000000",
                        },
                      ]
                    : []),
                ],
        },
      });
    }
    if (action === "cpus/status") {
      state.statusCalls++;
      if (state.statusChangedOnce && url.searchParams.has("version")) {
        state.statusChangedOnce = false;
        return route.fulfill({
          status: 409,
          json: { error: "STATUS_CHANGED" },
        });
      }
      if (state.statusFailure)
        return route.fulfill({
          status: 503,
          json: { error: "CRAFTING_QUEUE_BUSY" },
        });
      const cpuId = url.searchParams.get("cpuId"),
        taskId =
          cpuId === "cpu-external"
            ? "external-task"
            : running
              ? state.taskId
              : "";
      if (url.searchParams.get("taskId") !== taskId)
        return route.fulfill({ status: 409, json: { error: "TASK_CHANGED" } });
      const offset = Number(url.searchParams.get("offset") ?? 0),
        count = taskId ? state.materialCount : 0;
      return route.fulfill({
        json: {
          cpuId,
          taskId,
          version: 1,
          sampledAt: state.sampledAt || Date.now(),
          state: taskId ? "running" : "idle",
          cantStoreItems: false,
          totalWork: taskId ? "100" : "0",
          completedWork: taskId ? state.completedWork : "0",
          elapsedNanos: "10000000000",
          offset,
          total: count,
          detailsAvailable: true,
          error: "",
          entries: Array.from(
            {
              length: Math.max(
                0,
                Math.min(
                  Number(url.searchParams.get("limit") ?? 120),
                  count - offset,
                ),
              ),
            },
            (_, i) => ({
              item: {
                id: "material-" + (offset + i),
                name: state.materialName + " " + (offset + i),
                unit: "个",
                type: "item",
                registryId: "minecraft:iron_ingot",
              },
              stored: "12",
              active: "4",
              pending: "8",
            }),
          ),
        },
      });
    }
    if (action === "plans" && request.method() === "POST") {
      const input = request.postDataJSON();
      const item = {
        id: input.resourceId,
        name:
          input.resourceId === "resource-1"
            ? "甲水"
            : input.resourceId === "resource-2"
              ? "甲物品 2"
              : "甲物品 0",
        unit: input.resourceId === "resource-1" ? "mB" : "个",
        type: input.resourceId === "resource-1" ? "fluid" : "item",
        registryId: "minecraft:test",
        ...options.presentation,
      };
      const plan: CraftingPlan = {
        id: `plan-${state.plans.size + 1}`,
        networkId,
        item,
        quantity: input.quantity,
        state: options.calculating
          ? "calculating"
          : options.missing
            ? "missing"
            : "ready",
        bytes: "1024",
        materials: [
          {
            item: { ...item, id: "ingredient", name: "铁锭" },
            stored: "12",
            craft: "4",
            missing: options.missing ? "2" : "0",
          },
        ],
        createdAt: Date.now(),
      };
      state.plans.set(plan.id, plan);
      if (options.delayPlan)
        await new Promise((resolve) => setTimeout(resolve, 350));
      return route.fulfill({ status: 202, json: plan });
    }
    if (action.startsWith("plans/") && request.method() === "DELETE") {
      state.deletes++;
      if (state.deleteFailure)
        return route.fulfill({
          status: 503,
          json: { error: "CRAFTING_QUEUE_BUSY" },
        });
      return route.fulfill({ json: { cancelled: true } });
    }
    if (action.startsWith("plans/") && request.method() === "GET") {
      state.polls++;
      return route.fulfill({ json: state.plans.get(parts[7]) });
    }
    if (action === "orders" && request.method() === "POST") {
      state.submits++;
      if (options.failIntent && state.submits === 1)
        return route.fulfill({
          status: 503,
          json: { error: "CRAFTING_STORAGE_UNAVAILABLE" },
        });
      const input = request.postDataJSON(),
        plan = state.plans.get(input.planId)!;
      const order: CraftingOrder = {
        id: input.requestId,
        requestId: input.requestId,
        networkId,
        planId: plan.id,
        item: plan.item,
        quantity: plan.quantity,
        requestedCpuId: input.cpuId,
        cpuId: "cpu-1",
        cpuName: "主合成 CPU",
        nativeId: "native-1",
        state: "running",
        createdAt: Date.now(),
        updatedAt: Date.now(),
        finishedAt: 0,
        total: "100",
        progress: "25",
      };
      state.orders.push(order);
      if (options.loseSubmit) return route.abort("failed");
      return route.fulfill({ status: 202, json: order });
    }
    if (action.endsWith("/cancel")) {
      if (options.cancelFailure)
        return route.fulfill({
          status: 409,
          json: { error: "NETWORK_CHANGED" },
        });
      const order = state.orders.find((order) => order.id === parts[7])!;
      state.cancelled++;
      order.state = "cancelled";
      order.finishedAt = Date.now();
      return route.fulfill({ json: order });
    }
    if (action === "orders") {
      const requestId = url.searchParams.get("requestId");
      if (requestId && state.requestPending)
        return route.fulfill({
          status: 503,
          json: { error: "REQUEST_PENDING" },
        });
      const matches = state.orders.filter(
        (order) =>
          order.networkId === networkId &&
          (requestId
            ? order.requestId === requestId
            : ["completed", "cancelled", "failed", "unknown"].includes(
                order.state,
              ) ===
              (url.searchParams.get("history") === "true")),
      );
      const offset = Number(url.searchParams.get("cursor") || 0);
      const limit = Number(url.searchParams.get("limit") || 50);
      return route.fulfill({
        json: {
          orders: matches.slice(offset, offset + limit),
          nextCursor:
            offset + limit < matches.length ? String(offset + limit) : "",
        },
      });
    }
    if (action.startsWith("orders/")) {
      const order = state.orders.find((order) => order.id === parts[7]);
      if (order) return route.fulfill({ json: order });
    }
    return route.fulfill({ status: 404, json: { error: "ORDER_NOT_FOUND" } });
  });
  return state;
}

export async function ready(page: Page, url = "/") {
  await page.goto(url);
  await expect(page.locator(".inventory-card").first()).toBeVisible();
  await expect(
    page.getByRole("button", { name: "刷新", exact: true }),
  ).toBeEnabled();
  await expect(page.locator(".resource-stats")).toContainText("+18,000");
}
export async function preview(page: Page) {
  await page
    .getByRole("button", { name: "下单 甲物品 0", exact: true })
    .click();
  await page.getByLabel("合成数量", { exact: true }).fill("64");
  await page.getByRole("button", { name: "预览合成", exact: true }).click();
  await expect(page.getByRole("heading", { name: "材料预览" })).toBeVisible();
}

test("default grid preserves exact statistics, filters infinite sources and exposes zero-stock crafting", async ({
  page,
}) => {
  await craftingFixture(page.context());
  await ready(page);
  await expect(
    page.getByRole("button", { name: "网格", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(
    page.locator('.inventory-card[data-resource-id="resource-2"]'),
  ).toContainText("0 个");
  await expect(
    page.getByRole("button", { name: "下单 甲物品 2" }),
  ).toBeVisible();
  const water = page.locator('.inventory-card[data-resource-id="resource-1"]');
  await expect(water).toContainText("无限");
  await water.locator(".card-name").click();
  await expect(page.locator(".resource-stats")).toContainText(
    "2,147,486,591,840 mB",
  );
  await expect(water.getByLabel("区间库存上涨", { exact: true })).toBeVisible();
  await page.getByLabel("排除无限项", { exact: true }).check();
  await expect(water).toHaveCount(0);
  await expect(page.locator(".resource-stats")).toContainText(
    "2,147,486,591,840 mB",
  );
  await expect(
    page.locator('.inventory-card[data-resource-id="resource-0"]'),
  ).toContainText("9,007,199,254,740,993 个");
  await page.getByRole("button", { name: "列表", exact: true }).click();
  await expect(page.locator(".inventory-row")).toHaveCount(6);
  await expect(
    page.getByRole("button", { name: "下单 甲物品 2" }),
  ).toBeVisible();
  await page.waitForTimeout(220);
  await page.reload();
  await expect(page.getByLabel("排除无限项", { exact: true })).toBeChecked();
  await expect(
    page.getByRole("button", { name: "列表", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
});

test("merged crafting submits an exact variant and history pages remain shared", async ({
  page,
}) => {
  const state = await craftingFixture(page.context());
  await ready(page);
  await page.getByLabel("合并 NBT", { exact: true }).check();
  await preview(page);
  expect([...state.plans.values()][0].item.id).toBe("resource-0");
  await page.getByRole("button", { name: "确认下单", exact: true }).click();
  await expect(page.locator(".craft-order")).toHaveCount(1);
  const order = state.orders[0];
  state.orders = Array.from({ length: 51 }, (_, i) => ({
    ...order,
    id: `history-${i}`,
    state: "running",
    finishedAt: Date.now(),
  }));
  await page.getByRole("button", { name: "刷新订单", exact: true }).click();
  await expect(page.locator(".craft-order")).toHaveCount(50);
  await page.getByRole("button", { name: "加载更多订单" }).click();
  await expect(page.locator(".craft-order")).toHaveCount(51);
  await page.waitForTimeout(2300);
  await expect(page.locator(".craft-order")).toHaveCount(51);
  state.orders.forEach((value) => {
    value.state = "completed";
  });
  await page.getByRole("button", { name: "历史", exact: true }).click();
  await expect(page.locator(".craft-order")).toHaveCount(50);
  await page.getByRole("button", { name: "加载更多订单" }).click();
  await expect(page.locator(".craft-order")).toHaveCount(51);
  await expect(page.getByRole("button", { name: "加载更多订单" })).toHaveCount(
    0,
  );
});

test("failed cancellation preserves the active order and explains topology change", async ({
  page,
}, info) => {
  const state = await craftingFixture(page.context(), { cancelFailure: true });
  await ready(page);
  await preview(page);
  await page.getByRole("button", { name: "确认下单", exact: true }).click();
  await page.getByRole("button", { name: "取消订单 甲物品 0" }).click();
  await page.getByRole("button", { name: "确定取消", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "跨网络任务请在游戏内处理",
  );
  await expect(page.locator(".craft-order")).toHaveCount(1);
  expect(state.cancelled).toBe(0);
  await page.screenshot({
    path: info.outputPath("orders.png"),
    fullPage: true,
  });
});

test("hidden page pauses preview polling and resumes without creating another plan", async ({
  page,
}) => {
  const state = await craftingFixture(page.context(), { calculating: true });
  await ready(page);
  await page
    .getByRole("button", { name: "下单 甲物品 0", exact: true })
    .click();
  await page.getByRole("button", { name: "预览合成", exact: true }).click();
  await expect.poll(() => state.polls).toBeGreaterThan(0);
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", {
      configurable: true,
      get: () => true,
    });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  const count = state.polls;
  await page.waitForTimeout(2200);
  expect(state.polls).toBe(count);
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", {
      configurable: true,
      get: () => false,
    });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect.poll(() => state.polls).toBeGreaterThan(count);
  expect(state.plans.size).toBe(1);
});

test("craft buttons do not change selection, preview validates CPU and shared visitors can cancel", async ({
  page,
  context,
}) => {
  const state = await craftingFixture(context);
  await ready(page);
  await page
    .locator('.inventory-card[data-resource-id="resource-3"] .card-name')
    .click();
  await preview(page);
  await expect(page.locator(".detail-heading h1")).toHaveText("甲物品 3");
  await expect(page.locator('option[value="cpu-small"]')).toHaveJSProperty(
    "disabled",
    true,
  );
  await page.getByLabel("合成 CPU", { exact: true }).selectOption("cpu-1");
  await page
    .getByRole("button", { name: "确认下单", exact: true })
    .evaluate((button) => {
      (button as HTMLButtonElement).click();
      (button as HTMLButtonElement).click();
    });
  await expect(page.locator(".craft-order")).toHaveCount(1);
  expect(state.submits).toBe(1);
  await expect(page.locator(".craft-order")).toContainText("工作量 25.0%");
  const visitor = await context.newPage();
  await ready(visitor);
  await visitor.getByRole("button", { name: "合成订单", exact: true }).click();
  await expect(visitor.locator(".craft-order")).toContainText("64 个");
  await visitor.getByRole("button", { name: "取消订单 甲物品 0" }).click();
  await visitor.getByRole("button", { name: "确定取消", exact: true }).click();
  await expect(visitor.locator(".craft-order")).toHaveCount(0);
  await visitor.getByRole("button", { name: "历史", exact: true }).click();
  await expect(visitor.locator(".craft-order")).toContainText("已取消");
  expect(state.cancelled).toBe(1);
});

test("lost submission response is reconciled by requestId without resubmitting", async ({
  page,
}) => {
  const state = await craftingFixture(page.context(), { loseSubmit: true });
  await ready(page);
  await preview(page);
  await page.getByRole("button", { name: "确认下单", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("提交结果尚未确认");
  await expect(
    page.getByRole("button", { name: "确认下单", exact: true }),
  ).toBeDisabled();
  await page
    .locator("dialog[open]")
    .getByRole("button", { name: "查询订单结果" })
    .click();
  await expect(page.locator(".craft-order")).toHaveCount(1);
  expect(state.submits).toBe(1);
});

test("missing materials block submission and configuration hides new-order buttons", async ({
  page,
}) => {
  const state = await craftingFixture(page.context(), { missing: true });
  await ready(page);
  await preview(page);
  await expect(page.getByText("材料不足，补齐后请重新预览。")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "确认下单", exact: true }),
  ).toBeDisabled();
  expect(state.submits).toBe(0);
  await page.getByRole("button", { name: "关闭下单" }).click();
  await expect.poll(() => state.deletes).toBe(1);
  await page.context().route("**/api/v1/status", (route) =>
    route.fulfill({
      json: {
        authenticated: true,
        authMode: "public",
        worldId: "a96a8fc2-3411-43c6-ad89-3b51ff0e298f",
        craftingEnabled: false,
      },
    }),
  );
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(page.getByRole("button", { name: /^下单 / })).toHaveCount(0);
  await page.getByRole("button", { name: "合成订单", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "合成订单", exact: true }),
  ).toBeVisible();
});

test("failed intent persistence stays pending until definitive empty recovery and requires a new user submission", async ({
  page,
}) => {
  const state = await craftingFixture(page.context(), { failIntent: true });
  await ready(page);
  await preview(page);
  await page.getByRole("button", { name: "确认下单", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("提交结果尚未确认");
  state.requestPending = true;
  const lookup = page
    .locator("dialog[open]")
    .getByRole("button", { name: "查询订单结果" });
  await lookup.click();
  await expect(page.getByRole("alert")).toContainText("订单提交仍在处理中");
  await expect(
    page.getByRole("button", { name: "确认下单", exact: true }),
  ).toBeDisabled();
  expect(state.submits).toBe(1);
  state.requestPending = false;
  await lookup.click();
  await expect(page.getByRole("alert")).toContainText(
    "服务器已确认未接受该订单",
  );
  await expect(lookup).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "预览合成", exact: true }),
  ).toBeEnabled();
  await expect(
    page.getByRole("button", { name: "确认下单", exact: true }),
  ).toBeDisabled();
  expect(state.submits).toBe(1);
  expect(
    await page.evaluate(
      () =>
        Object.keys(sessionStorage).filter((key) =>
          key.startsWith("ae2dash:pending:"),
        ).length,
    ),
  ).toBe(0);
  await page.getByRole("button", { name: "预览合成", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "确认下单", exact: true }),
  ).toBeEnabled();
  expect(state.submits).toBe(1);
  await page.getByRole("button", { name: "确认下单", exact: true }).click();
  await expect(page.locator(".craft-order")).toHaveCount(1);
  expect(state.submits).toBe(2);
});

test("ordinary insecure HTTP uses getRandomValues to submit a UUID v4 request", async ({
  page,
  baseURL,
}) => {
  const state = await craftingFixture(page.context());
  await page.route("http://dashboard.test/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.startsWith("/api/")) return route.fallback();
    const response = await route.fetch({
      url: new URL(url.pathname + url.search, baseURL).href,
    });
    return route.fulfill({ response });
  });
  await ready(page, "http://dashboard.test/");
  expect(
    await page.evaluate(() => ({
      secure: isSecureContext,
      uuid: typeof crypto.randomUUID,
      random: typeof crypto.getRandomValues,
    })),
  ).toEqual({ secure: false, uuid: "undefined", random: "function" });
  await preview(page);
  await page.getByRole("button", { name: "确认下单", exact: true }).click();
  await expect(page.locator(".craft-order")).toHaveCount(1);
  expect(state.orders[0].requestId).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
  expect(state.submits).toBe(1);
});

test("switching networks cancels late previews and never displays old context", async ({
  page,
}) => {
  const state = await craftingFixture(page.context(), { delayPlan: true });
  await ready(page);
  await page
    .getByRole("button", { name: "下单 甲物品 0", exact: true })
    .click();
  await page.getByRole("button", { name: "预览合成", exact: true }).click();
  await page.getByRole("button", { name: "关闭下单" }).click();
  await page.getByLabel("网络", { exact: true }).selectOption(networks[1]);
  await expect.poll(() => state.deletes).toBe(1);
  await expect(page.locator("dialog[open]")).toHaveCount(0);
  await expect(page.locator(".inventory-card").first()).toContainText(
    "乙物品 0",
  );
});

test("grid window and view switching preserve the visible resource", async ({
  page,
}) => {
  await dashboardFixture(page.context(), { count: 10000 });
  await ready(page);
  await page.locator(".inventory-scroll").evaluate((el) => {
    el.scrollTop = 3600;
  });
  await page.waitForTimeout(150);
  expect(await page.locator(".inventory-card").count()).toBeLessThanOrEqual(
    120,
  );
  const first = await page
    .locator(".inventory-card")
    .evaluateAll((elements) => {
      const top = document
        .querySelector(".inventory-scroll")!
        .getBoundingClientRect().top;
      return elements
        .find((el) => el.getBoundingClientRect().top >= top - 1)
        ?.getAttribute("data-resource-id");
    });
  await page.setViewportSize({ width: 1024, height: 900 });
  await expect(
    page.locator(`.inventory-card[data-resource-id="${first}"]`),
  ).toBeInViewport();
  await page.getByRole("button", { name: "列表", exact: true }).click();
  await expect(
    page.locator(`.inventory-row[data-resource-id="${first}"]`),
  ).toBeInViewport();
  await page.getByRole("button", { name: "网格", exact: true }).click();
  await expect(
    page.locator(`.inventory-card[data-resource-id="${first}"]`),
  ).toBeInViewport();
});

for (const width of [1440, 1024, 390])
  for (const theme of ["dark", "light"]) {
    test(`grid and crafting ${theme} layout ${width}`, async ({
      page,
    }, info) => {
      await craftingFixture(page.context());
      await page.setViewportSize({ width, height: 940 });
      await ready(page);
      if (theme === "light") {
        await page.getByRole("button", { name: "切换主题" }).click();
        await page.getByRole("button", { name: "浅色", exact: true }).click();
        await page.getByRole("button", { name: "完成", exact: true }).click();
      }
      await page.screenshot({
        path: info.outputPath(`grid-${theme}-${width}.png`),
        fullPage: true,
      });
      await preview(page);
      await page.screenshot({
        path: info.outputPath(`crafting-${theme}-${width}.png`),
        fullPage: true,
      });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      await page.getByRole("button", { name: "确认下单", exact: true }).click();
      await expect(page.locator(".cpu-workspace")).toBeVisible();
      await expect(page.locator(".material-cell").first()).toBeVisible();
      await page.screenshot({
        path: info.outputPath(`cpu-${theme}-${width}.png`),
        fullPage: true,
      });
      await page.keyboard.press("Escape");
      await expect(page.locator("dialog[open]")).toHaveCount(0);
    });
  }

for (const [mode, message] of [
  ["none", "网络中没有合成 CPU"],
  ["busy", "没有空闲且在线"],
  ["small", "存储空间不足"],
  ["manual", "请手动选择"],
  ["error", "CPU 状态未确认"],
] as const) {
  test("CPU eligibility blocks " + mode, async ({ page }) => {
    const state = await craftingFixture(page.context());
    state.cpuMode = mode;
    await ready(page);
    await preview(page);
    await expect(
      page.getByRole("button", { name: "确认下单", exact: true }),
    ).toBeDisabled();
    await expect(page.locator(".cpu-problem")).toContainText(message);
    if (mode === "error") {
      state.cpuMode = "available";
      await expect(
        page.getByRole("button", { name: "确认下单", exact: true }),
      ).toBeEnabled({ timeout: 5000 });
    }
    if (mode === "manual") {
      await page.getByLabel("合成 CPU", { exact: true }).selectOption("cpu-1");
      await expect(
        page.getByRole("button", { name: "确认下单", exact: true }),
      ).toBeEnabled();
    }
  });
}
test("ready polling updates CPU occupancy and failed deletion never creates replacement previews", async ({
  page,
}) => {
  const state = await craftingFixture(page.context());
  await ready(page);
  await preview(page);
  await expect(
    page.getByRole("button", { name: "确认下单", exact: true }),
  ).toBeEnabled();
  state.cpuMode = "busy";
  await expect(
    page.getByRole("button", { name: "确认下单", exact: true }),
  ).toBeDisabled({ timeout: 5000 });
  state.deleteFailure = true;
  await page.getByLabel("合成数量", { exact: true }).fill("65");
  await page.getByRole("button", { name: "预览合成", exact: true }).click();
  await expect(page.locator(".craft-error")).toContainText("30 秒");
  expect(state.plans.size).toBe(1);
});
test("external and cross-network tasks remain readable and remote material windows stay bounded", async ({
  page,
}) => {
  const state = await craftingFixture(page.context());
  state.external = true;
  state.crossNetwork = true;
  state.materialCount = 1000;
  await ready(page);
  await page.getByRole("button", { name: "合成订单", exact: true }).click();
  await page.getByRole("button", { name: /外部合成 CPU/ }).click();
  await expect(page.locator(".cpu-detail")).toContainText(
    "任务属于其他网络，只读",
  );
  await expect(page.locator(".cpu-eta")).toContainText("预计剩余约 30 秒");
  await expect(page.locator(".material-cell").first()).toContainText("已存储");
  expect(await page.locator(".material-cell").count()).toBeLessThanOrEqual(120);
  await expect(page.getByRole("button", { name: /取消订单/ })).toHaveCount(0);
  await page
    .locator(".material-grid-scroll")
    .evaluate((el) => (el.scrollTop = 15000));
  await expect
    .poll(async () =>
      page.locator(".material-cell").first().getAttribute("data-material-id"),
    )
    .not.toBe("material-0");
  state.statusFailure = true;
  await expect(page.locator(".cpu-eta")).toHaveText("暂无法估算", {
    timeout: 5000,
  });
  await expect(page.locator(".material-cell").first()).toBeVisible();
});
test("availability segments support keyboard selection and preserve selected statistics", async ({
  page,
}) => {
  await craftingFixture(page.context());
  await ready(page);
  const segments = page.getByRole("radiogroup", { name: "库存与合成筛选" });
  await segments.getByRole("radio", { name: "全部", exact: true }).focus();
  await page.keyboard.press("ArrowRight");
  await expect(
    segments.getByRole("radio", { name: "库存", exact: true }),
  ).toHaveAttribute("aria-checked", "true");
  await expect(page.locator('[data-resource-id="resource-2"]')).toHaveCount(0);
  await page.keyboard.press("ArrowRight");
  await expect(page.locator(".inventory-card")).toHaveCount(3);
  await page.reload();
  await expect(
    segments.getByRole("radio", { name: "可合成", exact: true }),
  ).toHaveAttribute("aria-checked", "true");
  await expect(page.locator(".resource-stats")).toContainText("+18,000");
});
test("atlas crops actual pixels at 28 and 32px and quality overlays use identical coordinates", async ({
  page,
}) => {
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="192" height="192"><rect width="192" height="192" fill="#00ff00"/><rect x="64" y="64" width="64" height="64" fill="#ff0000"/></svg>';
  const overlay =
    '<svg xmlns="http://www.w3.org/2000/svg" width="192" height="192"><rect x="96" y="96" width="32" height="32" fill="#ffff00"/></svg>';
  const icon = {
    url: "data:image/svg+xml;base64," + Buffer.from(svg).toString("base64"),
    width: 192,
    height: 192,
    x: 64,
    y: 64,
  };
  const star = {
    ...icon,
    url: "data:image/svg+xml;base64," + Buffer.from(overlay).toString("base64"),
  };
  await craftingFixture(page.context(), {
    presentation: { icon, overlay: star, quality: 2 },
  });
  await ready(page);
  async function pixels(selector: string, size: number) {
    const target = page.locator(selector).first();
    await expect(target).toHaveCSS("width", size + "px");
    const png = await target.screenshot();
    const result = await page.evaluate(
      async ({ bytes, size }) => {
        const bitmap = await createImageBitmap(
          new Blob([new Uint8Array(bytes)], { type: "image/png" }),
        );
        const canvas = document.createElement("canvas");
        canvas.width = size;
        canvas.height = size;
        const ctx = canvas.getContext("2d")!;
        ctx.drawImage(bitmap, 0, 0, size, size);
        const data = ctx.getImageData(0, 0, size, size).data;
        let bad = 0;
        for (let i = 0; i < data.length; i += 4)
          if (data[i + 1] > data[i] && data[i + 1] > data[i + 2]) bad++;
        return {
          bad,
          red: Array.from(ctx.getImageData(2, 2, 1, 1).data),
          gold: Array.from(ctx.getImageData(size - 3, size - 3, 1, 1).data),
        };
      },
      { bytes: Array.from(png), size },
    );
    expect(result).toEqual({
      bad: 0,
      red: [255, 0, 0, 255],
      gold: [255, 255, 0, 255],
    });
  }
  await pixels(".inventory-card .resource-icon", 32);
  await page.getByRole("button", { name: "列表", exact: true }).click();
  await pixels(".inventory-row .resource-icon", 28);
  await page.getByRole("button", { name: "网格", exact: true }).click();
  await preview(page);
  await pixels(".material-cell .resource-icon", 32);
  await expect(page.locator(".material-cell .resource-icon")).toHaveAttribute(
    "aria-label",
    "铁锭 · 金星",
  );
});

test("CPU ETA pauses after no progress, resumes, and task replacement clears old materials", async ({
  page,
}) => {
  const now = Date.now();
  await page.clock.setFixedTime(new Date(now));
  const state = await craftingFixture(page.context());
  state.sampledAt = now;
  state.materialCount = 500;
  await ready(page);
  await preview(page);
  await page.getByRole("button", { name: "确认下单", exact: true }).click();
  await expect(page.locator(".cpu-eta")).toHaveText("预计剩余约 30 秒");
  state.sampledAt = now + 31000;
  await page.clock.setFixedTime(new Date(state.sampledAt));
  await page.getByRole("button", { name: "刷新订单", exact: true }).click();
  await expect(page.locator(".cpu-eta")).toHaveText("暂无进展，暂无法估算");
  state.completedWork = "50";
  state.sampledAt += 2000;
  await page.clock.setFixedTime(new Date(state.sampledAt));
  await page.getByRole("button", { name: "刷新订单", exact: true }).click();
  await expect(page.locator(".cpu-eta")).toHaveText("预计剩余约 10 秒");
  state.statusChangedOnce = true;
  await page
    .locator(".material-grid-scroll")
    .evaluate((el) => (el.scrollTop = 1480));
  await expect.poll(() => state.statusChangedOnce).toBe(false);
  state.taskId = "native-replacement";
  state.materialName = "新任务材料";
  await page.getByRole("button", { name: "刷新订单", exact: true }).click();
  await expect(page.locator(".material-cell").first()).toContainText(
    "新任务材料",
  );
  await expect(page.locator(".material-grid-scroll")).toHaveJSProperty(
    "scrollTop",
    0,
  );
  state.sampledAt -= 10000;
  await page.getByRole("button", { name: "刷新订单", exact: true }).click();
  await expect(page.locator(".cpu-eta")).toHaveText("暂无法估算");
});
