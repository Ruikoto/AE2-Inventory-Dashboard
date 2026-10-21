import { test, expect } from "@playwright/test";
const network = "8b7cc408-99b6-450f-ab76-d895c078f73c";
const world = "a96a8fc2-3411-43c6-ad89-3b51ff0e298f";
async function mock(page: import("@playwright/test").Page) {
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const now = Date.now();
    let json: object;
    if (url.pathname.endsWith("/status"))
      json = {
        authenticated: true,
        authMode: "public",
        worldId: world,
        historyStatus: "recording",
        historyBytes: 127926272,
        assets: { status: "missing", entries: 0 },
      };
    else if (url.pathname.endsWith("/networks"))
      json = {
        worldId: world,
        networks: [
          {
            id: network,
            label: "minecraft:overworld 128,64,-240",
            status: "online",
            lastSeen: now,
            anchors: ["minecraft:overworld 128,64,-240"],
          },
        ],
      };
    else if (url.pathname.endsWith("/inventory")) {
      const merged = url.searchParams.get("merged") === "true";
      const names = [
        "铁锭",
        "铜锭",
        "金锭",
        "红石粉",
        "安山合金",
        "石英",
        "钻石",
        "精密构件",
        "电子处理器",
        "水",
        "熔融铁",
        "蒸汽",
      ];
      const rows = Array.from({ length: 120 }, (_, i) => {
        const name = names[i % names.length];
        const type = i % names.length > 8 ? "fluid" : "item";
        const registryId = `${i % 4 ? "minecraft" : "create"}:${i % names.length === 0 ? "iron_ingot" : `resource_${i}`}`;
        const id = merged ? `group:${type}:${registryId}` : `resource-${i}`;
        return {
          id,
          groupId: `group:${type}:${registryId}`,
          registryId,
          name: i < 12 ? name : `${name} ${i}`,
          type,
          quantity: i === 0 ? "9007199254740993" : String(1964480 - i * 1259),
          unit: type === "fluid" ? "mB" : "个",
          amountPerUnit: type === "fluid" ? 1000 : 1,
          delta: String(i % 3 ? i * 20 : -i * 11),
          rate: String(i % 3 ? i * 120 : -i * 66),
          variantCount: merged ? 3 : 1,
        };
      });
      json = {
        worldId: world,
        networkId: network,
        available: true,
        timestamp: now,
        version: 1,
        status: "online",
        rows,
        total: rows.length,
        windowMs: 10000,
      };
    } else if (url.pathname.endsWith("/history")) {
      const to = Number(url.searchParams.get("to"));
      const from = Number(url.searchParams.get("from"));
      json = {
        worldId: world,
        networkId: network,
        history: {
          from,
          to,
          step: 60000,
          availableFrom: from,
          truncated: false,
          series: (url.searchParams.get("ids") ?? "")
            .split(",")
            .filter(Boolean)
            .map((id) => ({
              id,
              complete: true,
              delta: "18000",
              rate: "300",
              points: Array.from({ length: 60 }, (_, i) => ({
                timestamp: from + i * 60000,
                firstAt: from + i * 60000,
                lastAt: from + i * 60000,
                quantity: String(
                  1500000 + i * 300 + Math.round(Math.sin(i / 4) * 8000),
                ),
                min: String(1490000 + i * 300),
                max: String(1520000 + i * 300),
                segment: "one",
                samples: 6,
                rate: String(250 + Math.round(Math.cos(i / 4) * 200)),
              })),
            })),
        },
      };
    } else if (url.pathname.endsWith("/events"))
      json = {
        events: [
          { timestamp: now - 100000, type: "discovered", related: "[]" },
        ],
      };
    else json = {};
    await route.fulfill({ json });
  });
}
test.beforeEach(async ({ page }) => {
  await mock(page);
});
test("works without client resources, preserves exact quantities and local favorites", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByRole("grid")).toBeVisible();
  await expect(
    page.getByText("9,007,199,254,740,993 个", { exact: true }).first(),
  ).toBeVisible();
  await page.getByRole("button", { name: "收藏 铁锭", exact: true }).click();
  await page.getByLabel("仅收藏").check();
  await expect(
    page
      .getByRole("row")
      .filter({ has: page.getByRole("button", { name: /铁锭/ }) }),
  ).toHaveCount(1);
  await page.reload();
  await expect(page.getByLabel("仅收藏")).toBeChecked();
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await expect(page.getByText("未加载名称与图标包")).toBeVisible();
});
test("comparison limit and NBT switch", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("grid")).toBeVisible();
  const comparisons = page.getByRole("checkbox", { name: /^对比 / });
  for (let i = 0; i < 7; i++) await comparisons.nth(i).check();
  await expect(page.getByRole("alert")).toContainText("最多同时对比 6 项");
  await page.getByLabel("合并 NBT").check();
  await expect(
    page.getByText("3 个变体", { exact: false }).first(),
  ).toBeVisible();
});
for (const width of [1440, 1024, 390]) {
  test(`layout ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 940 });
    await page.goto("/");
    await expect(page.getByRole("grid")).toBeVisible();
    if (width < 760) {
      await page
        .getByRole("button", { name: /铁锭 minecraft:|铁锭 create:/ })
        .first()
        .click();
      await expect(
        page.getByRole("button", { name: "‹ 返回库存" }),
      ).toBeVisible();
    }
    await expect
      .poll(async () => page.locator("canvas").count())
      .toBeGreaterThan(0);
    await page.screenshot({
      path: info.outputPath(`dashboard-${width}.png`),
      fullPage: true,
    });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
  });
}
