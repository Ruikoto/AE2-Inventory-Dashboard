import { test, expect, type Page } from "@playwright/test";
import { dashboardFixture, networks, world } from "./fixture";

// 这些回归专门验证原有列表与图表；新默认网格另有独立覆盖。
test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    ({ world, networks }) => {
      for (const network of networks) {
        const key = `ae2dash:1:${location.origin}:/:${world}:${network}`;
        if (!localStorage.getItem(key))
          localStorage.setItem(key, JSON.stringify({ inventoryView: "list" }));
      }
    },
    { world, networks },
  );
});

async function ready(page: Page) {
  await page.goto("/");
  await expect(page.locator(".inventory-row").first()).toBeVisible();
  await expect(
    page.getByRole("button", { name: "刷新", exact: true }),
  ).toBeEnabled();
  await expect(page.locator(".resource-stats")).toContainText("+18,000");
}

test("themes persist, custom colors validate and favicon is present", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  await ready(page);
  await page.getByRole("button", { name: "切换主题" }).click();
  await page.getByRole("button", { name: "浅色", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.getByRole("button", { name: "切换主题" }).click();
  await page.getByRole("button", { name: "自定义", exact: true }).click();
  await page.getByLabel("基础方案").selectOption("light");
  await page.getByLabel("背景颜色").fill("#f0f4f8");
  await expect(page.locator("html")).toHaveCSS("--bg", "#f0f4f8");
  await page.getByLabel("文字颜色").fill("#f0f4f8");
  await expect(page.getByRole("status")).toContainText("对比度不足");
  await expect(page.locator("html")).toHaveCSS("--text", "#243241");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "custom");
  await expect(page.locator("html")).toHaveCSS("--bg", "#f0f4f8");
  const favicon = await page.locator('link[rel="icon"]').getAttribute("href");
  expect(favicon).toBeTruthy();
  expect((await page.request.get(favicon!)).ok()).toBe(true);
});

test("remembers network, hides only confirmed empty networks and preserves immediate preference edits", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  await ready(page);
  await expect(page.locator("#network option")).toHaveCount(3);
  await expect(page.locator("#network option")).toContainText([
    "甲网络",
    "乙网络",
    "未知网络",
  ]);
  await page
    .getByRole("button", { name: "收藏 甲物品 0", exact: true })
    .click();
  await page.getByLabel("网络", { exact: true }).selectOption(networks[1]);
  await expect(page.locator(".inventory-row").first()).toContainText(
    "乙物品 0",
  );
  await page.getByLabel("网络", { exact: true }).selectOption(networks[0]);
  await expect(
    page.getByRole("button", { name: "取消收藏 甲物品 0", exact: true }),
  ).toBeVisible();
  await page.getByLabel("网络", { exact: true }).selectOption(networks[1]);
  await expect(page.locator(".inventory-row").first()).toContainText(
    "乙物品 0",
  );
  await page.reload();
  await expect(page.getByLabel("网络", { exact: true })).toHaveValue(
    networks[1],
  );
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByLabel("显示空网络", { exact: true }).check();
  await expect(page.locator("#network option")).toHaveCount(4);
  await page.getByLabel("网络", { exact: true }).selectOption(networks[2]);
  await expect(page.locator(".resource-count")).toHaveText("0 项");
  await expect(page.locator(".inventory-row")).toHaveCount(0);
});

test("whole row is clickable while favorite and compare remain independent", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  await ready(page);
  const row = page.locator(".inventory-row").nth(2);
  const box = (await row.boundingBox())!;
  await page.mouse.click(box.x + box.width * 0.7, box.y + 1);
  await expect(row).toHaveClass(/selected/);
  const next = page.locator(".inventory-row").nth(3);
  const nextBox = (await next.boundingBox())!;
  await page.mouse.click(
    nextBox.x + nextBox.width * 0.7,
    nextBox.y + nextBox.height - 1,
  );
  await expect(next).toHaveClass(/selected/);
  await page
    .getByRole("button", { name: "收藏 甲物品 4", exact: true })
    .click();
  await expect(page.locator(".inventory-row.selected")).toContainText(
    "甲物品 3",
  );
  await page.getByLabel("对比 甲物品 5", { exact: true }).check();
  await expect(page.locator(".inventory-row.selected")).toContainText(
    "甲物品 3",
  );
  await expect(page.locator(".detail-heading h1")).toHaveText("甲物品 5");
});

test("table and detail use positive interval statistics despite negative instantaneous data", async ({
  page,
}) => {
  const calls = await dashboardFixture(page.context());
  await ready(page);
  await expect(
    page.locator(".inventory-row").first().locator(".change"),
  ).toContainText("+18,000");
  await expect(
    page.locator(".inventory-row").first().locator(".rate-column"),
  ).toContainText("+300");
  await expect(page.locator(".resource-stats")).toContainText("+18,000");
  await expect(page.locator(".resource-stats")).toContainText("+300");
  await expect(page.locator(".coverage-note").first()).toContainText(
    "最近连续段",
  );
  await page.getByLabel("时间范围", { exact: true }).selectOption("300");
  await expect.poll(() => calls.ranges.at(-1)).toBe(300000);
  await expect(page.locator(".resource-stats")).toContainText("+18,000");
  await page.getByLabel("波动范围", { exact: true }).check();
  await expect(page.getByLabel("波动范围", { exact: true })).toBeChecked();
  await page.getByRole("button", { name: "净速率", exact: true }).click();
  await expect(page.getByLabel("波动范围", { exact: true })).toBeDisabled();
});

test("fluid display converts inventory, interval delta and rate together", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  await ready(page);
  await page
    .getByRole("radiogroup", { name: "资源类型" })
    .getByRole("radio", { name: "流体", exact: true })
    .click();
  await expect(page.locator(".inventory-row")).toHaveCount(1);
  await expect(page.locator(".inventory-row").first()).toContainText("甲水");
  await page.locator(".inventory-row").first().click();
  await expect(page.locator(".detail-heading h1")).toHaveText("甲水");
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByLabel("流体用 B 显示").check();
  await expect(
    page.locator(".inventory-row").first().locator(".change"),
  ).toContainText("+18.000 B");
  await expect(
    page.locator(".inventory-row").first().locator(".rate-column"),
  ).toContainText("+0.3 B/min");
  await expect(page.locator(".resource-stats")).toContainText("+18.000 B");
  await expect(page.locator(".resource-stats")).toContainText("+0.3 B/min");
});

test("rapid network switching rejects previous responses", async ({ page }) => {
  await dashboardFixture(page.context(), {
    inventoryDelay: 250,
    statisticsDelay: 300,
  });
  await ready(page);
  await page.getByLabel("网络", { exact: true }).selectOption(networks[1]);
  await page.getByLabel("网络", { exact: true }).selectOption(networks[0]);
  await page.getByLabel("网络", { exact: true }).selectOption(networks[1]);
  await expect(
    page.getByRole("button", { name: "刷新", exact: true }),
  ).toBeEnabled();
  await expect(page.locator(".inventory-row").first()).toContainText(
    "乙物品 0",
  );
  await page.waitForTimeout(800);
  await expect(page.locator(".inventory-row").first()).toContainText(
    "乙物品 0",
  );
  await expect(
    page.locator(".inventory-row").filter({ hasText: "甲" }),
  ).toHaveCount(0);
});

test("chart keeps dual axes, zoom and legend while theme and smoothing change", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  await ready(page);
  await expect(page.locator("canvas")).toHaveCount(1);
  const chartState = () =>
    page.evaluate(async () => {
      const modulePath = "/node_modules/.vite/deps/echarts_core.js";
      const echarts = await import(modulePath);
      const chart = echarts.getInstanceByDom(
        document.querySelector(".trend-chart"),
      );
      const option = chart.getOption();
      return {
        axes: option.yAxis.length,
        series: option.series.length,
        zoom: option.dataZoom[0].start,
        selected: option.legend[0].selected,
        background: option.dataZoom[1].backgroundColor,
      };
    });
  await expect.poll(async () => (await chartState()).axes).toBe(2);
  await page.getByLabel("波动范围", { exact: true }).check();
  await expect.poll(async () => (await chartState()).series).toBe(4);
  await page.evaluate(async () => {
    const modulePath = "/node_modules/.vite/deps/echarts_core.js";
    const echarts = await import(modulePath);
    const chart = echarts.getInstanceByDom(
      document.querySelector(".trend-chart"),
    );
    chart.dispatchAction({ type: "dataZoom", start: 20, end: 80 });
    chart.dispatchAction({ type: "legendUnSelect", name: "甲物品 0 · 速率" });
  });
  await page.getByRole("button", { name: "切换主题" }).click();
  await page.getByRole("button", { name: "浅色", exact: true }).click();
  await page.getByRole("button", { name: "完成", exact: true }).click();
  await expect
    .poll(async () => (await chartState()).background)
    .toBe("#f4f6f8");
  await page.getByLabel("速率平滑窗口").selectOption("900000");
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "刷新", exact: true }),
  ).toBeEnabled();
  await expect.poll(async () => (await chartState()).zoom).toBe(20);
  expect((await chartState()).selected["甲物品 0 · 速率"]).toBe(false);
});

test("single comparison expands variants for the displayed group", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  await ready(page);
  await page.getByLabel("合并 NBT").check();
  await expect(page.locator(".inventory-row").first()).toContainText(
    "2 个变体",
  );
  await page.getByLabel("对比 甲水", { exact: true }).check();
  await expect(page.locator(".detail-heading h1")).toHaveText("甲水");
  await page.getByRole("button", { name: "查看精确变体" }).click();
  await expect(page.locator(".variant-row")).toHaveCount(1);
  await expect(page.locator(".variant-row")).toContainText("甲水");
});

test("initial connection error offers a working retry", async ({ page }) => {
  await dashboardFixture(page.context());
  let fail = true;
  await page.context().route("**/api/v1/status", async (route) => {
    if (fail)
      await route.fulfill({ status: 503, json: { error: "unavailable" } });
    else await route.fallback();
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "重新连接" })).toBeVisible();
  fail = false;
  await page.getByRole("button", { name: "重新连接" }).click();
  await expect(page.locator(".inventory-row").first()).toBeVisible();
});

test("stalled Worker request times out and allows manual recovery", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  let stall = true;
  await page
    .context()
    .route("**/api/v1/networks/*/inventory?*", async (route) => {
      if (stall) await new Promise((resolve) => setTimeout(resolve, 16000));
      await route.fallback();
    });
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("读取失败", {
    timeout: 20000,
  });
  await expect(
    page.getByRole("button", { name: "刷新", exact: true }),
  ).toBeEnabled();
  stall = false;
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(page.locator(".inventory-row").first()).toBeVisible();
});

test("omitted null statistics clear the previous interval metrics on refresh", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  let insufficient = false;
  await page
    .context()
    .route("**/api/v1/networks/*/statistics?*", async (route) => {
      if (!insufficient) return route.fallback();
      await route.fulfill({
        json: {
          worldId: world,
          version: 1,
          total: 120,
          rows: Array.from({ length: 120 }, (_, i) => ({
            id: `resource-${i}`,
            statisticsScope: "insufficient",
            statisticsSamples: 1,
          })),
        },
      });
    });
  await ready(page);
  await expect(page.locator(".inventory-row .change").first()).toContainText(
    "+18,000",
  );
  insufficient = true;
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "刷新", exact: true }),
  ).toBeEnabled();
  await expect(page.locator(".inventory-row .change").first()).toHaveText("—");
  await expect(page.locator(".inventory-row .rate-column").first()).toHaveText(
    "—",
  );
});

test("failed local storage preserves network preferences within the session", async ({
  page,
}) => {
  await dashboardFixture(page.context());
  await page.addInitScript(() => {
    Storage.prototype.setItem = () => {
      throw new DOMException("Storage unavailable", "QuotaExceededError");
    };
  });
  await ready(page);
  await page
    .getByRole("button", { name: "收藏 甲物品 0", exact: true })
    .click();
  await page.getByLabel("网络", { exact: true }).selectOption(networks[1]);
  await expect(page.locator(".inventory-row").first()).toContainText(
    "乙物品 0",
  );
  await page.getByLabel("网络", { exact: true }).selectOption(networks[0]);
  await expect(
    page.getByRole("button", { name: "取消收藏 甲物品 0", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await expect(
    page.getByText("浏览器无法保存设置，本次会话仍可使用"),
  ).toBeVisible();
});

test("refresh preserves scroll, selection, chart canvas and populated statistics", async ({
  page,
}) => {
  await dashboardFixture(page.context(), {
    count: 10000,
    statisticsDelay: 120,
  });
  await ready(page);
  await page.locator(".inventory-scroll").evaluate((el) => {
    el.scrollTop = 64 * 90;
  });
  await page.waitForTimeout(100);
  const target = page.locator(".inventory-row").nth(10);
  const title = await target.locator(".resource-name strong").textContent();
  await target.click();
  await expect(page.locator(".detail-heading h1")).toHaveText(title!);
  const scroll = await page
    .locator(".inventory-scroll")
    .evaluate((el) => el.scrollTop);
  await page.locator("canvas").evaluate((el) => {
    (el as HTMLElement).dataset.testIdentity = "preserved";
  });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(page.locator(".inventory-row .change").first()).not.toHaveText(
    "—",
  );
  await expect(
    page.getByRole("button", { name: "刷新", exact: true }),
  ).toBeEnabled();
  await expect(page.locator(".detail-heading h1")).toHaveText(title!);
  expect(
    await page.locator(".inventory-scroll").evaluate((el) => el.scrollTop),
  ).toBe(scroll);
  await expect(page.locator("canvas")).toHaveAttribute(
    "data-test-identity",
    "preserved",
  );
});

for (const width of [1440, 1024, 390]) {
  for (const theme of ["dark", "light"]) {
    test(`${theme} screenshot ${width}`, async ({ page }, info) => {
      await dashboardFixture(page.context());
      await page.setViewportSize({ width, height: 940 });
      await ready(page);
      if (theme === "light") {
        await page.getByRole("button", { name: "切换主题" }).click();
        await page.getByRole("button", { name: "浅色", exact: true }).click();
        await page.getByRole("button", { name: "完成", exact: true }).click();
      }
      await page.screenshot({
        path: info.outputPath(`${theme}-${width}-inventory.png`),
        fullPage: true,
      });
      if (width < 760) {
        await page.locator(".inventory-row .resource-name").first().click();
        await expect(
          page.getByRole("button", { name: "‹ 返回库存" }),
        ).toBeVisible();
        await expect(page.locator("canvas")).toBeVisible();
        await page.screenshot({
          path: info.outputPath(`${theme}-${width}-detail.png`),
          fullPage: true,
        });
      }
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      await page.getByRole("button", { name: "切换主题" }).focus();
      await expect(
        page.getByRole("button", { name: "切换主题" }),
      ).toBeFocused();
    });
  }
}
