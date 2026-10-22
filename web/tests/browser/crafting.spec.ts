import { test, expect, type BrowserContext, type Page } from "@playwright/test";
import { dashboardFixture, networks } from "./fixture";
import type { CraftingOrder, CraftingPlan } from "../../src/models/crafting";

async function craftingFixture(
  context: BrowserContext,
  options: {
    enabled?: boolean;
    missing?: boolean;
    loseSubmit?: boolean;
    delayPlan?: boolean;
    calculating?: boolean;
    cancelFailure?: boolean;
    failIntent?: boolean;
  } = {},
) {
  await dashboardFixture(context, {
    count: 8,
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
  };
  await context.route("**/api/v1/networks/*/crafting/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url());
    const parts = url.pathname.split("/"),
      networkId = parts[4];
    const action = parts.slice(6).join("/");
    if (action === "cpus")
      return route.fulfill({
        json: {
          cpus: [
            {
              id: "cpu-1",
              name: "主合成 CPU",
              storage: "65536",
              coprocessors: 4,
              busy: false,
              selectable: true,
            },
            {
              id: "cpu-small",
              name: "小型 CPU",
              storage: "1",
              coprocessors: 0,
              busy: false,
              selectable: true,
            },
          ],
        },
      });
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
    return route.fulfill({ status: 404, json: { error: "ORDER_NOT_FOUND" } });
  });
  return state;
}

async function ready(page: Page, url = "/") {
  await page.goto(url);
  await expect(page.locator(".inventory-card").first()).toBeVisible();
  await expect(
    page.getByRole("button", { name: "刷新", exact: true }),
  ).toBeEnabled();
  await expect(page.locator(".resource-stats")).toContainText("+18,000");
}
async function preview(page: Page) {
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
      await page.keyboard.press("Escape");
      await expect(page.locator("dialog[open]")).toHaveCount(0);
    });
  }
