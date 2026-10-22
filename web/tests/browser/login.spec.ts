import { test, expect, type Page } from "@playwright/test";
import { dashboardFixture, world } from "./fixture";
async function loginFixture(page: Page) {
  await dashboardFixture(page.context(), { count: 4 });
  const state = {
    authenticated: false,
    posts: 0,
    mode: "success",
    logoutFailure: false,
  };
  await page.route("**/api/v1/status", (route) =>
    route.fulfill({
      json: {
        authenticated: state.authenticated,
        authMode: "password",
        worldId: world,
        historyStatus: "recording",
        assets: { status: "missing", entries: 0 },
      },
    }),
  );
  await page.route("**/api/v1/session", async (route) => {
    if (route.request().method() === "DELETE") {
      if (state.logoutFailure)
        return route.fulfill({
          status: 503,
          json: { error: "SESSION_STORAGE_UNAVAILABLE" },
        });
      state.authenticated = false;
      return route.fulfill({ json: { authenticated: false } });
    }
    state.posts++;
    await new Promise((resolve) => setTimeout(resolve, 150));
    if (state.mode === "network") return route.abort("failed");
    if (state.mode !== "success")
      return route.fulfill({
        status:
          state.mode === "password" ? 401 : state.mode === "rate" ? 429 : 503,
        json: {
          error:
            state.mode === "storage"
              ? "SESSION_STORAGE_UNAVAILABLE"
              : "UNAUTHORIZED",
        },
      });
    state.authenticated = true;
    return route.fulfill({ json: { authenticated: true } });
  });
  await page.goto("/");
  await expect(page.getByLabel("访问密码")).toBeVisible();
  return state;
}
test("login guards duplicates and persistence failure preserves typed password", async ({
  page,
}) => {
  const state = await loginFixture(page);
  state.mode = "storage";
  await page.getByLabel("访问密码").fill("browser-secret-123");
  await page.locator("form.login").evaluate((el) => {
    el.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    el.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  });
  await expect(page.getByRole("button", { name: "正在登录…" })).toBeDisabled();
  await expect(page.getByRole("alert")).toContainText("暂时无法保存");
  expect(state.posts).toBe(1);
  await expect(page.getByLabel("访问密码")).toHaveValue("browser-secret-123");
  state.mode = "success";
  await page.getByRole("button", { name: "进入", exact: true }).click();
  await expect(page.locator(".inventory-card").first()).toBeVisible();
  expect(
    await page.evaluate(() =>
      JSON.stringify({ ...localStorage, ...sessionStorage }),
    ),
  ).not.toContain("browser-secret-123");
  await page.reload();
  await expect(page.locator(".inventory-card").first()).toBeVisible();
  expect(state.posts).toBe(2);
  await page.getByRole("button", { name: "设置", exact: true }).click();
  state.logoutFailure = true;
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("退出失败");
  await expect(page.locator(".inventory-card").first()).toBeVisible();
  state.logoutFailure = false;
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page.getByLabel("访问密码")).toHaveValue("");
});
for (const mode of ["network", "rate", "password"])
  test(
    "login handles " + mode + " without losing recoverable input",
    async ({ page }) => {
      const state = await loginFixture(page);
      state.mode = mode;
      await page.getByLabel("访问密码").fill("kept-secret");
      await page.getByRole("button", { name: "进入", exact: true }).click();
      await expect(page.getByRole("alert")).toBeVisible();
      await expect(page.getByLabel("访问密码")).toHaveValue(
        mode === "password" ? "" : "kept-secret",
      );
    },
  );
