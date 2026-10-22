import { test, expect } from "@playwright/test";
import { writeFile } from "node:fs/promises";
import { dashboardFixture, networks, world } from "../browser/fixture";

for (const view of ["grid", "list"] as const) {
  for (const count of [10000, 100000]) {
    test(`${view}: ${count} resources, six charts and refresh stability`, async ({
      page,
    }, info) => {
      const calls = await dashboardFixture(page.context(), {
        count,
        points: 1200,
        singleNetwork: true,
      });
      await page.addInitScript(
        ({ world, network, view }) => {
          localStorage.setItem(
            `ae2dash:1:${location.origin}:/:${world}:${network}`,
            JSON.stringify({
              inventoryView: view,
              comparisons: [0, 2, 3, 4, 5, 6].map((i) => `resource-${i}`),
            }),
          );
          const state = {
            frames: [] as number[],
            longTasks: [] as number[],
          };
          (window as any).__benchmark = state;
          let previous = performance.now();
          const frame = (now: number) => {
            state.frames.push(now - previous);
            previous = now;
            requestAnimationFrame(frame);
          };
          requestAnimationFrame(frame);
          new PerformanceObserver((list) => {
            for (const entry of list.getEntries())
              state.longTasks.push(entry.duration);
          }).observe({ type: "longtask", buffered: true });
        },
        { world, network: networks[0], view },
      );
      const errors: string[] = [];
      page.on("pageerror", (e) => errors.push(e.message));
      const began = Date.now();
      await page.goto("/");
      await expect(page.locator(".resource-count")).toHaveText(
        `${count.toLocaleString()} 项`,
        { timeout: 60000 },
      );
      await expect(
        page.getByRole("button", { name: "刷新", exact: true }),
      ).toBeEnabled({ timeout: 60000 });
      await expect(page.locator("canvas")).toHaveCount(1);
      expect(calls.historyIds).toHaveLength(6);
      const readyMs = Date.now() - began;
      const filterTimings: number[] = [];
      for (let i = 20; i < 40; i++) {
        filterTimings.push(
          await page.evaluate(
            ({ query, id }) =>
              new Promise<number>((resolve, reject) => {
                const input = document.querySelector<HTMLInputElement>(
                  '[aria-label="搜索物品"]',
                )!;
                const began = performance.now();
                const timer = setTimeout(() => {
                  observer.disconnect();
                  reject(new Error("Search did not complete"));
                }, 5000);
                const observer = new MutationObserver(() => {
                  if (!document.querySelector(`[data-resource-id="${id}"]`))
                    return;
                  clearTimeout(timer);
                  observer.disconnect();
                  resolve(performance.now() - began);
                });
                observer.observe(document.querySelector(".inventory-scroll")!, {
                  subtree: true,
                  childList: true,
                  characterData: true,
                });
                input.value = query;
                input.dispatchEvent(new Event("input", { bubbles: true }));
              }),
            { query: `minecraft:resource_${i}`, id: `resource-${i}` },
          ),
        );
      }
      await page.getByLabel("搜索物品").fill("");
      await expect(page.locator(".resource-count")).toHaveText(
        `${count.toLocaleString()} 项`,
      );
      await page.locator(".inventory-scroll").evaluate((el) => {
        el.scrollTop = 64 * 300;
      });
      await page.waitForTimeout(250);
      const scroll = await page
        .locator(".inventory-scroll")
        .evaluate((el) => el.scrollTop);
      const cdp = await page.context().newCDPSession(page);
      await cdp.send("Performance.enable");
      const samples: object[] = [];
      const duration = process.env.PERF_SECONDS
        ? Number(process.env.PERF_SECONDS) * 1000
        : count === 100000
          ? 600000
          : 10000;
      const until = Date.now() + duration;
      while (Date.now() < until) {
        await page.waitForTimeout(
          Math.min(10000, Math.max(1, until - Date.now())),
        );
        const { metrics } = await cdp.send("Performance.getMetrics");
        const values = Object.fromEntries(
          metrics.map(({ name, value }) => [name, value]),
        );
        samples.push({
          elapsedMs: Date.now() - began,
          heap: values.JSHeapUsedSize,
          nodes: values.Nodes,
          inventoryPages: calls.inventory,
          statisticsPages: calls.statistics,
        });
        expect(
          await page.locator(".inventory-row, .inventory-card").count(),
        ).toBeLessThan(121);
        expect(
          await page
            .locator(".inventory-scroll")
            .evaluate((el) => el.scrollTop),
        ).toBe(scroll);
        expect(errors).toEqual([]);
      }
      const timing = await page.evaluate(() => {
        const state = (window as any).__benchmark;
        const percentile = (data: number[], p: number) =>
          [...data].sort((a, b) => a - b)[
            Math.min(data.length - 1, Math.floor(data.length * p))
          ] ?? 0;
        return {
          frameCount: state.frames.length,
          frameP95Ms: percentile(state.frames, 0.95),
          frameP99Ms: percentile(state.frames, 0.99),
          frameMaxMs: Math.max(...state.frames),
          longTaskCount: state.longTasks.length,
          longTaskMaxMs: Math.max(0, ...state.longTasks),
          longTaskTotalMs: state.longTasks.reduce(
            (a: number, b: number) => a + b,
            0,
          ),
        };
      });
      const report = {
        view,
        count,
        series: 6,
        pointsPerSeries: 1200,
        readyMs,
        filterTimings,
        filterP95Ms: [...filterTimings].sort((a, b) => a - b)[
          Math.floor(filterTimings.length * 0.95)
        ],
        observationMs: duration,
        calls: {
          inventory: calls.inventory,
          statistics: calls.statistics,
          history: calls.history,
        },
        timing,
        samples,
        errors,
      };
      await info.attach("measurements", {
        body: JSON.stringify(report, null, 2),
        contentType: "application/json",
      });
      await writeFile(
        info.outputPath("measurements.json"),
        JSON.stringify(report),
      );
      console.log(
        JSON.stringify({
          ...report,
          samples: {
            first: samples[0],
            last: samples.at(-1),
            count: samples.length,
          },
        }),
      );
    });
  }
}
